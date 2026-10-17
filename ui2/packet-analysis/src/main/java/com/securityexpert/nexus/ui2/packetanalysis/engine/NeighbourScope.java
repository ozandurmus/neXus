package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.NatMapping;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Neighbour capture scope. Not in the frozen reference: an intentional divergence (README "Intentional divergences
 * from the reference", DETERMINISM.md), SQL in {@code sql/neighbour_scope.sql}.
 *
 * <p>A hop A -> B compares the packets of A with those of B. When B's capture never observed a flow at all (no packet
 * of it in either direction, flow identity as the engine canonicalizes it, so confirmed NAT mappings apply) while
 * other packets prove that the flow crossed B, the flow lies outside B's capture scope (a capture filter or a
 * narrower SPAN): its packets at A are not missing at B, they were never in what B could show. The proof is a packet
 * of the same direction at a point after B on that direction's path, or reply traffic of the other direction at a
 * point after B on the reply path that is not a reset and carries payload or an ACK without SYN (a device answering a
 * blocked handshake sends neither). Such a flow is out of the hop's loss, capture-miss and delay accounting. A flow
 * without that proof stays in scope, so a handshake blocked at the boundary is still reported.
 *
 * <p>Two places apply it. {@link #restrictSinglePointFlows}: a flow seen at one capture point only, which the
 * reference keeps because it is a client tuple, is dropped from the index scope when it is out of scope on every hop
 * that would assess it; it is then counted in {@code analysis_scope} / {@code quality[].index_scope} like the
 * reference's other single-point exclusions. {@link #prepareHop}: per hop, a flow present at several points but out
 * of one hop's scope is left out of that hop's events and denominators ({@code scope_excluded_flows} on the segment).
 *
 * <p>Flow identity is not compared across a hop whose identity the engine cannot assert: a full-proxy or vendor
 * evidence point, an unconfirmed NAT suggestion, undecided or mismatched tunnel matching layers, or a NAT hop
 * (translation {@code nat}) except for flows carrying one of that hop's confirmed mapping tuples.
 */
final class NeighbourScope {
    static final String LIMITATION = "Flows a capture point carries but its neighbour never observed, while other packets "
            + "prove they crossed that neighbour, are outside the neighbour's capture scope: they are left out of that "
            + "hop's loss, capture-miss and delay accounting (analysis_scope excluded_single_point_flows, segment "
            + "scope_excluded_flows) and are never reported as loss.";
    static final String SEGMENT_NOTE = "Flows not observed at %s but proven to cross it are outside its capture scope: "
            + "%d flow(s), %d packet(s) at %s left out of this hop's loss, capture-miss and delay accounting";

    private NeighbourScope() {
    }

    /** How flow identity may be compared across a hop; {@code null} from {@link #identity}: not at all. */
    record Identity(boolean restricted, List<String> tuples) {
    }

    static List<String> evidencePoints(Topology topology) {
        List<String> out = new ArrayList<>();
        for (Point p : topology.points) {
            if (p.translation().equals("full_proxy") || List.of("f5", "paloalto", "checkpoint").contains(p.vendor())) {
                out.add(p.id());
            }
        }
        return out;
    }

    static Set<String> points(Db db, String statement) {
        Set<String> out = new HashSet<>();
        for (Object[] r : db.tuples(Sql.get("segments", statement))) {
            out.add((String) r[0]);
        }
        return out;
    }

    static Identity identity(EngineContext ctx, String a, String b, Set<String> outer, Set<String> unselected,
            List<String> evidence) {
        Point pa = ctx.points.get(a);
        Point pb = ctx.points.get(b);
        if (evidence.contains(a) || evidence.contains(b) || unselected.contains(a) || unselected.contains(b)
                || outer.contains(a) != outer.contains(b) || Segments.pendingSuggestion(ctx, a, b)) {
            return null;
        }
        if (!pa.translation().equals("nat") && !pb.translation().equals("nat")) {
            return new Identity(false, List.of());
        }
        List<String> tuples = new ArrayList<>();
        for (NatMapping m : ctx.topology.natMappings) {
            if (Set.of(m.pointA(), m.pointB()).equals(Set.of(a, b))) {
                tuples.addAll(List.of(m.tupleA(), m.tupleB(), Tuples.reverseTuple(m.tupleA()), Tuples.reverseTuple(m.tupleB())));
            }
        }
        return tuples.isEmpty() ? null : new Identity(true, tuples);
    }

    private static List<String> after(List<String> path, String point) {
        int i = path.indexOf(point);
        return i < 0 ? List.of() : new ArrayList<>(path.subList(i + 1, path.size()));
    }

    /** Flows of A in {@code direction} out of the scope of hop A -> B: rows (flow, packets at A). */
    static List<Object[]> unobserved(EngineContext ctx, String a, String b, String direction, Identity id) {
        String other = direction.equals("forward") ? "reverse" : "forward";
        return ctx.db.tuples(Sql.get("neighbour_scope", "hop_unobserved"), a, direction, b, id.restricted(), id.tuples(),
                direction, after(ctx.topology.path(direction), b), other, after(ctx.topology.path(other), b));
    }

    /**
     * restrict_index_scope: drops from {@code scoped_flows} the single-point client flows that are out of scope on
     * every hop that would assess them (a hop from their point in a direction they have packets in). Returns the
     * number of flows dropped.
     */
    static long restrictSinglePointFlows(EngineContext ctx, List<String> evidence) {
        Db db = ctx.db;
        db.exec(Sql.get("neighbour_scope", "scope_excluded"));
        db.exec(Sql.get("neighbour_scope", "single_point"), evidence);
        if (db.count(Sql.get("neighbour_scope", "single_point_count")) == 0) {
            return 0;
        }
        Set<String> outer = points(db, "outer_points");
        Set<String> unselected = points(db, "unselected_points");
        Map<String, Boolean> outOfScope = new TreeMap<>();
        for (String direction : List.of("forward", "reverse")) {
            List<String> path = ctx.topology.path(direction);
            for (int i = 0; i + 1 < path.size(); i++) {
                String a = path.get(i);
                String b = path.get(i + 1);
                List<Object[]> assessed = db.tuples(Sql.get("neighbour_scope", "single_point_assessed"), a, direction);
                if (assessed.isEmpty()) {
                    continue;
                }
                Set<String> proven = new HashSet<>();
                Identity id = identity(ctx, a, b, outer, unselected, evidence);
                if (id != null) {
                    for (Object[] r : unobserved(ctx, a, b, direction, id)) {
                        proven.add((String) r[0]);
                    }
                }
                for (Object[] r : assessed) {
                    String flow = (String) r[0];
                    outOfScope.merge(flow, proven.contains(flow), Boolean::logicalAnd);
                }
            }
        }
        List<Object[]> dropped = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : outOfScope.entrySet()) {
            if (e.getValue()) {
                dropped.add(new Object[] {e.getKey()});
            }
        }
        if (dropped.isEmpty()) {
            return 0;
        }
        db.execMany(Sql.get("neighbour_scope", "scope_excluded_insert"), dropped);
        db.exec(Sql.get("neighbour_scope", "scope_excluded_delete"));
        return dropped.size();
    }

    /**
     * segments: records the flows out of the scope of hop A -> B in {@code hop_scope} and returns {flows, packets};
     * {0, 0} when the hop keeps every flow (the reference statements then run unchanged).
     */
    static long[] prepareHop(EngineContext ctx, String a, String b, String direction, Set<String> outer,
            Set<String> unselected) {
        Identity id = identity(ctx, a, b, outer, unselected, evidencePoints(ctx.topology));
        if (id == null) {
            return new long[] {0, 0};
        }
        List<Object[]> rows = unobserved(ctx, a, b, direction, id);
        long packets = 0;
        List<Object[]> insert = new ArrayList<>();
        for (Object[] r : rows) {
            packets += ((Number) r[1]).longValue();
            insert.add(new Object[] {a, b, direction, r[0], r[1]});
        }
        if (!insert.isEmpty()) {
            ctx.db.execMany(Sql.get("neighbour_scope", "hop_scope_insert"), insert);
        }
        return new long[] {rows.size(), packets};
    }

    static boolean excludes(EngineContext ctx, String a, String b, String direction) {
        return ctx.hopScope.containsKey(direction + ":" + a + ":" + b);
    }

    /** A statement that ends in a WHERE condition, restricted to the hop's scope; params gain A, B, direction. */
    static String filtered(String sql) {
        return sql + " " + Sql.get("neighbour_scope", "hop_filter");
    }

    static Object[] params(Object[] params, String a, String b, String direction) {
        Object[] out = java.util.Arrays.copyOf(params, params.length + 3);
        out[params.length] = a;
        out[params.length + 1] = b;
        out[params.length + 2] = direction;
        return out;
    }

    static String missingCondition(String condition, String a, String b, String direction) {
        return "(" + condition + ") AND " + Sql.fmt("neighbour_scope", "missing_scope", "a", literal(a), "b", literal(b),
                "direction", literal(direction));
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
