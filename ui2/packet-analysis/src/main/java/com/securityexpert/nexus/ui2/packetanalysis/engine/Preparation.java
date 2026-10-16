package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.NatMapping;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Observation selection and matching preparation: reference {@code analysis/selection.py},
 * {@code analysis/matching.py} ({@code prepare}), {@code fragments.normalize_fragments},
 * {@code translation.normalize_sequences}, {@code matching.prepare_occurrences} / {@code partition_proxy_legs}.
 */
final class Preparation {
    private Preparation() {
    }

    // ------------------------------------------------------------------ selection.py

    static void selectObservations(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        db.exec(Sql.get("selection", "drop_matches_view"));
        db.exec(Sql.get("selection", "byte_flows"));
        db.exec(Sql.get("selection", "drop_obs"));
        db.exec(Sql.get("selection", "create_obs"));
        F5Vendor.prepareConnections(db, topology);
        Set<String> ready = new LinkedHashSet<>();
        for (Object[] r : db.tuples(Sql.get("selection", "ready_captures"))) {
            ready.add((String) r[0]);
        }
        Set<String> used = new LinkedHashSet<>(topology.forward);
        used.addAll(topology.reverse.isEmpty() ? Topology.reversed(topology.forward) : topology.reverse);
        for (Point p : topology.points) {
            if (p.vendor().equals("paloalto")) {
                used.add(p.id());
            }
        }
        db.exec(Sql.get("selection", "analysis_scope"));
        // sorted(topology.points, key=lambda p: p.host_capture): stable, non-host points first.
        List<Point> ordered = new ArrayList<>(topology.points);
        ordered.sort((x, y) -> Boolean.compare(x.hostCapture(), y.hostCapture()));
        for (Point point : ordered) {
            if (!used.contains(point.id())) {
                continue;
            }
            if (!ready.contains(point.captureId())) {
                throw new IllegalArgumentException(point.label() + ": capture is not ready");
            }
            String clause = "capture_id=?";
            List<Object> params = new ArrayList<>(List.of(point.captureId()));
            Object[] selector = pointSelector(point);
            clause += (String) selector[0];
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) selector[1];
            params.addAll(values);
            if (point.iface() != null) {
                Map<?, ?> inv = (Map<?, ?>) Evidence.loads((String) db.scalar(Sql.get("selection", "capture_inventory"),
                        point.captureId()));
                if (Evidence.truthy(inv.get("multiple_sections"))) {
                    throw new IllegalArgumentException(
                            "Interface IDs repeat across pcapng sections; split sections before assigning interface filters");
                }
                clause += " AND iface=?";
                params.add(point.iface());
            }
            long inputFrames = db.count(Sql.fmt("selection", "input_frames", "clause", clause), params.toArray());
            long inputFlows = db.count(Sql.fmt("selection", "input_flows", "clause", clause), params.toArray());
            clause += hostScopeClause(ctx, point, used);
            long retained = db.count(Sql.fmt("selection", "input_frames", "clause", clause), params.toArray());
            long retainedFlows = db.count(Sql.fmt("selection", "input_flows", "clause", clause), params.toArray());
            db.exec(Sql.get("selection", "analysis_scope_insert"), point.id(), inputFrames, retained,
                    inputFrames - retained, inputFlows - retainedFlows);
            List<Object> insert = new ArrayList<>();
            insert.add(point.id());
            insert.addAll(params);
            db.exec(Sql.fmt("selection", "obs_insert", "clause", clause), insert.toArray());
            pointPostFilters(ctx, point);
        }
        for (String column : List.of("add_corrected", "add_direction", "add_canon_reverse", "add_packet_key", "add_flow",
                "add_eligible")) {
            db.exec(Sql.get("selection", column));
        }
        if (db.count(Sql.get("selection", "overlapping_selections")) > 0) {
            throw new IllegalArgumentException("Capture-point selections overlap: use distinct file/interface/source filters");
        }
        normalizeFragments(db);
    }

    /** vendors.point_selector. */
    static Object[] pointSelector(Point point) {
        // Statement texts are stored without their leading space; the reference strings start with " AND".
        if (point.vendor().equals("checkpoint")) {
            String clause = " " + Sql.get("vendors", "checkpoint_stage");
            List<Object> values = new ArrayList<>();
            values.add(point.vendorStage());
            if (point.vendorInterface() != null && !point.vendorInterface().isEmpty()) {
                clause += " " + Sql.get("vendors", "checkpoint_interface");
                values.add(point.vendorInterface());
            }
            return new Object[] {clause, values};
        }
        if (point.vendor().equals("f5")) {
            return new Object[] {" " + Sql.get("vendors", "f5_role"), new ArrayList<>(List.of(point.vendorStage()))};
        }
        return new Object[] {"", new ArrayList<>()};
    }

    private static String hostScopeClause(EngineContext ctx, Point point, Set<String> used) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        boolean other = topology.points.stream().anyMatch(p -> !p.hostCapture() && used.contains(p.id()));
        if (!(point.hostCapture() && other)) {
            return "";
        }
        db.exec(Sql.get("selection", "path_tuples"));
        List<Object[]> mapped = new ArrayList<>();
        for (NatMapping m : topology.natMappings) {
            for (String k : List.of(m.tupleA(), m.tupleB(), Tuples.reverseTuple(m.tupleA()), Tuples.reverseTuple(m.tupleB()))) {
                mapped.add(new Object[] {k});
            }
        }
        db.execMany(Sql.get("selection", "path_tuples_insert"), mapped);
        List<Tuples.Network> nets = Tuples.networks(topology.clientCidrs);
        List<Object[]> chosen = new ArrayList<>();
        for (Object[] r : db.tuples(Sql.get("selection", "capture_addresses"), point.captureId(), point.captureId())) {
            String a = (String) r[0];
            if (a != null && !a.isEmpty() && Tuples.inAny(Tuples.address(a), nets)) {
                chosen.add(new Object[] {a});
            }
        }
        db.exec(Sql.get("selection", "selected_clients"));
        db.execMany(Sql.get("selection", "selected_clients_insert"), chosen);
        return " " + Sql.get("selection", "host_scope_clause");
    }

    private static void pointPostFilters(EngineContext ctx, Point point) {
        Db db = ctx.db;
        CaptureLayers.applyLayers(db, point);
        if (point.sourceCidr() != null && !point.sourceCidr().isEmpty()) {
            Tuples.Network network = Tuples.network(point.sourceCidr());
            List<Object[]> accepted = new ArrayList<>();
            for (Object[] r : db.tuples(Sql.get("selection", "point_source_addresses"), point.id())) {
                String src = (String) r[0];
                if (src != null && !src.isEmpty() && network.contains(Tuples.address(src))) {
                    accepted.add(new Object[] {src});
                }
            }
            db.exec(Sql.get("selection", "point_sources"));
            db.execMany(Sql.get("selection", "point_sources_insert"), accepted);
            db.exec(Sql.get("selection", "point_sources_delete"), point.id());
        }
        if (point.vendor().equals("paloalto")) {
            Map<String, Object> vendor = new LinkedHashMap<>();
            vendor.put("adapter", "paloalto");
            vendor.put("stage", point.vendorStage());
            vendor.put("device", point.device());
            vendor.put("stage_source", "user_file_tag");
            db.exec(Sql.get("selection", "paloalto_vendor"), PyJsonWriter.dumps(vendor, false), point.id());
        }
    }

    // ------------------------------------------------------------------ fragments.py

    static void normalizeFragments(Db db) {
        db.exec(Sql.get("fragments", "wire_observations"));
        db.exec(Sql.get("fragments", "fragment_members"));
        db.exec(Sql.get("fragments", "fragment_episodes"));
        db.exec(Sql.get("fragments", "fragment_spans"));
        db.exec(Sql.fmt("fragments", "fragment_groups", "FRAGMENT_STATUS", Sql.get("fragments", "FRAGMENT_STATUS")));
        if (db.one(Sql.get("fragments", "any_group")) == null) {
            return;
        }
        db.exec(Sql.get("fragments", "ambiguous_groups"));
        db.exec(Sql.get("fragments", "normalized_fragments"));
        db.exec(Sql.get("fragments", "delete_fragment_points"));
        db.exec(Sql.get("fragments", "insert_normalized"));
        db.exec(Sql.get("fragments", "drop_normalized"));
    }

    // ------------------------------------------------------------------ analysis/matching.py

    static void findNatSuggestions(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Point> points = ctx.points;
        TreeSet<String> natPoints = new TreeSet<>();
        for (int i = 0; i + 1 < topology.forward.size(); i++) {
            Point pa = points.get(topology.forward.get(i));
            Point pb = points.get(topology.forward.get(i + 1));
            if (pa.device().equals(pb.device()) && (pa.translation().equals("nat") || pb.translation().equals("nat"))) {
                natPoints.add(pa.id());
                natPoints.add(pb.id());
            }
        }
        ctx.natPoints = new ArrayList<>(natPoints);
        db.exec(Sql.get("analysis_matching", "nat_candidates"), ctx.natPoints);
        for (int i = 0; i + 1 < topology.forward.size(); i++) {
            String a = topology.forward.get(i);
            String b = topology.forward.get(i + 1);
            Point pa = points.get(a);
            Point pb = points.get(b);
            if (!pa.device().equals(pb.device()) || !(pa.translation().equals("nat") || pb.translation().equals("nat"))) {
                continue;
            }
            List<Row> found = db.rows(Sql.get("analysis_matching", "nat_pairs"), a, b);
            for (Row item : found) {
                long n = 0;
                for (Row x : found) {
                    if (Objects.equals(x.get("tuple_a"), item.get("tuple_a")) || Objects.equals(x.get("tuple_b"), item.get("tuple_b"))) {
                        n++;
                    }
                }
                if (n != 1) {
                    continue;
                }
                Map<String, Object> s = new LinkedHashMap<>(item);
                s.put("point_a", a);
                s.put("point_b", b);
                s.put("status", "suggested");
                s.put("evidence", Evidence.evidence(db, "(o.point=? AND o.frame=?) OR (o.point=? AND o.frame=?)",
                        Arrays.asList(a, item.get("frame_a"), b, item.get("frame_b"))));
                ctx.suggestions.add(s);
            }
        }
    }

    static void canonicalizeTuples(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Point> points = ctx.points;
        Map<String, String> parent = new HashMap<>();
        Map<List<String>, String> seen = new HashMap<>();
        for (NatMapping m : topology.natMappings) {
            if (!points.containsKey(m.pointA()) || !points.containsKey(m.pointB())) {
                throw new IllegalArgumentException("NAT mapping references an unknown capture point");
            }
            if (!points.get(m.pointA()).device().equals(points.get(m.pointB()).device())) {
                throw new IllegalArgumentException("NAT mappings must join two sides of the same device");
            }
            String[][] pairs = {{m.tupleA(), m.tupleB()}, {Tuples.reverseTuple(m.tupleA()), Tuples.reverseTuple(m.tupleB())}};
            for (String[] pair : pairs) {
                String a = pair[0];
                String b = pair[1];
                if (Tuples.parse(a).size() != 5 || Tuples.parse(b).size() != 5) {
                    throw new IllegalArgumentException("NAT tuples must have five fields");
                }
                String[][] sides = {{m.pointA(), a, b}, {m.pointB(), b, a}};
                for (String[] side : sides) {
                    List<String> conflictKey = List.of(m.pointA(), m.pointB(), side[0], side[1]);
                    if (seen.containsKey(conflictKey) && !seen.get(conflictKey).equals(side[2])) {
                        throw new IllegalArgumentException("NAT mappings conflict; mapping must be one-to-one");
                    }
                    seen.put(conflictKey, side[2]);
                }
                String ra = root(parent, a);
                String rb = root(parent, b);
                parent.put(rb, ra);
            }
        }
        List<Tuples.Network> nets = Tuples.networks(topology.clientCidrs);
        db.exec(Sql.get("analysis_matching", "tuple_lookup"));
        List<Object[]> metadata = new ArrayList<>();
        for (Object[] r : db.tuples(Sql.get("analysis_matching", "distinct_tuples"))) {
            String key = (String) r[0];
            String canon = parent.containsKey(key) ? root(parent, key) : key;
            metadata.add(new Object[] {key, canon, Tuples.reverseTuple(canon), direction(canon, nets)});
        }
        db.execMany(Sql.get("analysis_matching", "tuple_lookup_insert"), metadata);
        db.exec(Sql.get("analysis_matching", "canonical_obs"));
        if (topology.points.stream().anyMatch(p -> p.vendor().equals("f5"))) {
            db.exec(Sql.get("analysis_matching", "f5_direction"));
        }
    }

    private static String root(Map<String, String> parent, String key) {
        parent.putIfAbsent(key, key);
        while (!parent.get(key).equals(key)) {
            key = parent.get(key);
        }
        return key;
    }

    static String direction(String key, List<Tuples.Network> nets) {
        List<Object> t = Tuples.parse(key);
        try {
            Tuples.Address a = Tuples.address(t.get(1));
            Tuples.Address b = Tuples.address(t.get(3));
            boolean ac = Tuples.inAny(a, nets);
            boolean bc = Tuples.inAny(b, nets);
            return ac && !bc ? "forward" : bc && !ac ? "reverse" : "unknown";
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }

    static void restrictIndexScope(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        List<String> evidencePoints = new ArrayList<>();
        for (Point p : topology.points) {
            if (p.translation().equals("full_proxy") || List.of("f5", "paloalto", "checkpoint").contains(p.vendor())) {
                evidencePoints.add(p.id());
            }
        }
        List<Tuples.Network> nets = Tuples.networks(topology.clientCidrs);
        List<Object[]> selected = new ArrayList<>();
        for (Object[] r : db.tuples(Sql.get("selection", "distinct_canon"))) {
            String canon = (String) r[0];
            try {
                List<Object> key = Tuples.parse(canon);
                boolean any = false;
                for (Object ip : new Object[] {key.get(1), key.get(3)}) {
                    Tuples.Address address = Tuples.address(ip);
                    for (Tuples.Network net : nets) {
                        if (net.contains(address)) {
                            any = true;
                            break;
                        }
                    }
                    if (any) {
                        break;
                    }
                }
                if (any) {
                    selected.add(new Object[] {canon});
                }
            } catch (IllegalArgumentException | IndexOutOfBoundsException | NullPointerException e) {
                // ValueError / TypeError / IndexError: not a client tuple.
            }
        }
        db.exec(Sql.get("selection", "client_tuples"));
        db.execMany(Sql.get("selection", "client_tuples_insert"), selected);
        db.exec(Sql.get("selection", "scoped_flows"));
        db.exec(Sql.get("selection", "index_scope"), evidencePoints, evidencePoints);
        db.exec(Sql.get("selection", "index_scope_update"));
        db.exec(Sql.get("selection", "restrict_obs"), evidencePoints);
        db.exec(Sql.get("selection", "analysis_scope_update"));
    }

    // ------------------------------------------------------------------ translation.py

    static void normalizeSequences(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Point> points = ctx.points;
        String mod = "4294967296";
        db.exec(Sql.get("translation", "sequence_models"));
        db.exec(Sql.get("translation", "translation_links"));
        List<String[]> boundaries = new ArrayList<>();
        Set<String> paired = new LinkedHashSet<>();
        for (int i = 0; i + 1 < topology.forward.size(); i++) {
            Point pa = points.get(topology.forward.get(i));
            Point pb = points.get(topology.forward.get(i + 1));
            if (pa.device().equals(pb.device())
                    && (pa.translation().equals("seq_randomization") || pb.translation().equals("seq_randomization"))) {
                boundaries.add(new String[] {pa.id(), pb.id()});
                paired.add(pa.id());
                paired.add(pb.id());
            }
        }
        for (Point p : topology.points) {
            if (p.translation().equals("seq_randomization") && !paired.contains(p.id())) {
                throw new IllegalArgumentException("Sequence randomization needs adjacent ingress/egress points of the same device");
            }
        }
        for (String[] boundary : boundaries) {
            String a = boundary[0];
            String b = boundary[1];
            db.exec(Sql.get("translation", "sequence_anchors"));
            List<Object[]> modelRows = new ArrayList<>();
            Set<String> targets = new LinkedHashSet<>(topology.forward);
            targets.addAll(topology.reverse);
            for (String target : targets) {
                if (target.equals(a)) {
                    continue;
                }
                db.exec(Sql.fmt("translation", "sequence_pairs", "MOD", mod), a, target);
                List<Row> found = db.rows(Sql.get("translation", "sequence_found"));
                Map<List<Object>, Integer> countsA = new HashMap<>();
                Map<List<Object>, Integer> countsB = new HashMap<>();
                for (Row row : found) {
                    if (row.lng("n") >= 3) {
                        countsA.merge(Arrays.asList(row.get("flow"), row.get("sa")), 1, Integer::sum);
                        countsB.merge(Arrays.asList(row.get("flow"), row.get("sb")), 1, Integer::sum);
                    }
                }
                for (Row row : found) {
                    String reason = null;
                    Integer ca = countsA.get(Arrays.asList(row.get("flow"), row.get("sa")));
                    Integer cb = countsB.get(Arrays.asList(row.get("flow"), row.get("sb")));
                    if (row.lng("n") < 3 || Math.min(row.lng("nf"), row.lng("nr")) < 2) {
                        reason = "Insufficient independent sequence-translation anchors";
                    } else if (!Objects.equals(ca, 1) || !Objects.equals(cb, 1)) {
                        reason = "Ambiguous TCP session correspondence at sequence translation";
                    } else if (!Objects.equals(row.get("f"), row.get("f_max")) || !Objects.equals(row.get("r"), row.get("r_max"))) {
                        reason = "Inconsistent sequence/ACK offset within this flow";
                    }
                    modelRows.add(new Object[] {a, b, target, row.get("flow"), row.get("sa"), row.get("sb"), row.get("f"),
                        row.get("r"), row.get("raw_f"), row.get("raw_r"), row.get("n"), reason != null ? "unknown" : "learned",
                        reason, row.get("fa"), row.get("fb")});
                    if (row.lng("n") >= 3 && Objects.equals(ca, 1) && Objects.equals(cb, 1)) {
                        db.exec(Sql.get("translation", "translation_links_insert"), a, row.get("sa"), target, row.get("sb"),
                                row.get("flow"));
                    }
                }
            }
            Set<Object> modelTargets = new LinkedHashSet<>();
            for (Object[] m : modelRows) {
                modelTargets.add(m[2]);
            }
            for (Object target : modelTargets) {
                List<Object[]> candidates = new ArrayList<>();
                for (Object[] m : modelRows) {
                    if (m[2].equals(target) && "learned".equals(m[11])) {
                        candidates.add(new Object[] {m[3], m[4], m[5], m[6], m[7]});
                    }
                }
                if (candidates.isEmpty()) {
                    continue;
                }
                db.exec(Sql.get("translation", "offset_candidates"));
                db.execMany(Sql.get("translation", "offset_candidates_insert"), candidates);
                List<Row> bad = db.rows(Sql.fmt("translation", "inconsistent", "MOD", mod), a, target);
                Set<List<Object>> badKeys = new LinkedHashSet<>();
                for (Row x : bad) {
                    badKeys.add(Arrays.asList(x.get("flow"), x.get("sa"), x.get("sb")));
                }
                for (Object[] m : modelRows) {
                    if (m[2].equals(target) && badKeys.contains(Arrays.asList(m[3], m[4], m[5]))) {
                        m[11] = "unknown";
                        m[12] = "Inconsistent sequence/ACK offset within this flow (independent SEQ/ACK cross-check)";
                    }
                }
            }
            db.execMany(Sql.get("translation", "models_insert"), modelRows);
            for (String point : List.of(a, b)) {
                for (Object[] r : db.tuples(Sql.get("translation", "point_sessions"), point)) {
                    Object flow = r[0];
                    Object stream = r[1];
                    Object frame = r[2];
                    String column = point.equals(a) ? "ingress_stream" : "stream";
                    if (db.count(Sql.fmt("translation", "model_exists", "column", column), a, b, flow, stream) == 0) {
                        db.exec(Sql.get("translation", "models_insert"), a, b, b, flow, point.equals(a) ? stream : -1L,
                                point.equals(b) ? stream : -1L, null, null, null, null, 0L, "unknown",
                                "Insufficient independent sequence-translation anchors", point.equals(a) ? frame : null,
                                point.equals(b) ? frame : null);
                    }
                }
            }
            db.exec(Sql.get("translation", "bad_sequence_sessions"), a, b);
            db.exec(Sql.get("translation", "propagate_bad"), a);
            Set<List<Object>> maps = new LinkedHashSet<>();
            List<String> downstream = topology.forward.subList(topology.forward.indexOf(b), topology.forward.size());
            for (Row m : db.rows(Sql.get("translation", "ingress_models"), a)) {
                maps.add(Arrays.asList(m.get("point"), m.get("old_flow"), m.get("stream"), m.get("forward_offset"),
                        m.get("reverse_offset"), downstream.contains(m.str("point")), m.get("reason")));
                if (m.lng("ingress_stream") >= 0) {
                    maps.add(Arrays.asList(a, m.get("old_flow"), m.get("ingress_stream"), 0L, 0L, false,
                            Objects.equals(m.get("point"), b) ? m.get("reason") : null));
                }
            }
            db.exec(Sql.get("translation", "sequence_map"));
            List<Object[]> mapRows = new ArrayList<>();
            for (List<Object> m : maps) {
                mapRows.add(m.toArray());
            }
            db.execMany(Sql.get("translation", "sequence_map_insert"), mapRows);
            db.exec(Sql.get("translation", "sequence_map_grouped"));
            db.exec(Sql.fmt("translation", "apply_offsets", "MOD", mod));
            db.exec(Sql.get("translation", "translated_signatures"));
            db.exec(Sql.get("translation", "translated_keys"));
        }
    }

    // ------------------------------------------------------------------ matching.py

    static void partitionProxyLegs(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Point> points = ctx.points;
        Set<String> devices = new LinkedHashSet<>();
        for (Point p : topology.points) {
            if (p.translation().equals("full_proxy")) {
                devices.add(p.device());
            }
        }
        if (devices.isEmpty()) {
            return;
        }
        TreeSet<String> active = new TreeSet<>(topology.forward);
        active.addAll(topology.reverse);
        Map<String, String> parent = new HashMap<>();
        for (String p : active) {
            parent.put(p, p);
        }
        for (List<String> path : List.of(topology.forward, topology.reverse.isEmpty() ? Topology.reversed(topology.forward)
                : topology.reverse)) {
            for (int i = 0; i + 1 < path.size(); i++) {
                String a = path.get(i);
                String b = path.get(i + 1);
                if (points.get(a).device().equals(points.get(b).device()) && devices.contains(points.get(a).device())) {
                    continue;
                }
                String ra = legRoot(parent, a);
                String rb = legRoot(parent, b);
                if (!ra.equals(rb)) {
                    parent.put(ra.compareTo(rb) > 0 ? ra : rb, ra.compareTo(rb) > 0 ? rb : ra);
                }
            }
        }
        List<String> sortedActive = new ArrayList<>(active);
        List<String> legs = new ArrayList<>();
        for (String p : sortedActive) {
            legs.add(legRoot(parent, p));
        }
        db.exec(Sql.get("matching", "proxy_leg_names"), sortedActive, legs);
        db.exec(Sql.get("matching", "proxy_leg_obs"));
    }

    private static String legRoot(Map<String, String> parent, String p) {
        while (!parent.get(p).equals(p)) {
            p = parent.get(p);
        }
        return p;
    }

    static void prefixKeys(EngineContext ctx) {
        ctx.db.exec(Sql.get("analysis_matching", "prefix_lengths"));
        ctx.db.exec(Sql.get("analysis_matching", "prefix_keys"));
        ctx.db.exec(Sql.get("analysis_matching", "nat_point_tuples"), ctx.natPoints);
    }

    static void sipKeys(EngineContext ctx) {
        ctx.db.exec(Sql.get("analysis_matching", "sip_keys"));
    }

    static void prepareOccurrences(EngineContext ctx) {
        Db db = ctx.db;
        db.exec(Sql.get("matching", "add_base_key"));
        db.exec(Sql.get("matching", "add_occurrence"));
        db.exec(Sql.get("matching", "add_excluded_reason"));
        db.exec(Sql.get("matching", "span_duplicates"), ctx.topology.duplicateUs / 1e6 + 0.0000005);
        db.exec(Sql.get("matching", "eligible_obs"));
        db.exec(Sql.get("matching", "calibration"));
    }

    static void byteCalibration(EngineContext ctx) {
        if (ctx.db.count(Sql.get("analysis_matching", "large_tcp")) > 0) {
            ctx.db.exec(Sql.get("byte_ranges", "byte_calibration"));
            ctx.db.exec(Sql.get("byte_ranges", "calibration_insert"));
        }
    }
}
