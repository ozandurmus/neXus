package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Occurrence-aware matching after clock alignment: reference {@code matching.match_occurrences},
 * {@code link_tcp_sessions}, {@code propagate_translation_unknown}, {@code integrity.link_modified_payloads}
 * and {@code byte_ranges.prepare_byte_ranges}.
 */
final class Matching {
    private static final Pattern OBS = Pattern.compile("\\bobs\\b");

    private Matching() {
    }

    static void matchOccurrences(EngineContext ctx, String table) {
        if (!table.equals("obs") && !table.equals("byte_obs")) {
            throw new IllegalArgumentException("Unknown observation table");
        }
        Db db = ctx.db;
        java.util.function.Function<String, String> t = name -> OBS.matcher(Sql.get("matching", name)).replaceAll(table);
        db.exec(t.apply("occurrences"));
        db.exec(t.apply("occurrence_obs"));
        db.exec(t.apply("repeated_keys"));
        if (db.count(t.apply("repeated_count")) == 0) {
            return;
        }
        db.exec(t.apply("time_shifts"), ctx.topology.forward.get(0));
        db.exec(t.apply("repeat_obs"));
        db.exec(t.apply("anchor_points"));
        db.exec(t.apply("anchors"));
        db.exec(t.apply("balanced_pairs"), ctx.topology.matchWindowMs / 1000);
        db.exec(t.apply("unmatched_repeats"));
        db.exec(t.apply("before_candidates"));
        db.exec(t.apply("after_candidates"));
        db.exec(t.apply("nearest"), ctx.topology.matchWindowMs / 1000);
        db.exec(t.apply("nearest_balanced"));
        db.exec(t.apply("assigned"));
        db.exec(t.apply("assigned_obs"));
    }

    /** integrity/payloads.link_modified_payloads. */
    static void linkModifiedPayloads(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Point> points = ctx.points;
        List<String[]> edges = new ArrayList<>();
        for (String direction : List.of("forward", "reverse")) {
            List<String> path = topology.path(direction);
            for (int i = 0; i + 1 < path.size(); i++) {
                String a = path.get(i);
                String b = path.get(i + 1);
                if (!points.get(a).translation().equals("full_proxy") && !points.get(b).translation().equals("full_proxy")) {
                    edges.add(new String[] {a, b, direction});
                }
            }
        }
        db.exec(Sql.get("integrity_payloads", "modified_payload_pairs"));
        db.exec(Sql.get("integrity_payloads", "modified_headers"));
        for (String[] e : edges) {
            db.exec(Sql.get("integrity_payloads", "modified_pairs_insert"), e[0], e[2], e[1], e[2],
                    topology.matchWindowMs / 1000, e[0], e[1], e[2]);
        }
        db.exec(Sql.get("integrity_payloads", "drop_modified_headers"));
        Set<List<Object>> changed = new LinkedHashSet<>();
        for (Object[] r : db.tuples(Sql.get("integrity_payloads", "changed_edges"))) {
            changed.add(Arrays.asList(r));
        }
        for (String[] e : edges) {
            if (!changed.contains(Arrays.asList((Object) e[0], e[1]))) {
                continue;
            }
            if (ctx.models.get(points.get(e[0]).captureId()).uncertainty == null
                    || ctx.models.get(points.get(e[1]).captureId()).uncertainty == null) {
                continue;
            }
            db.exec(Sql.get("integrity_payloads", "relink"), e[0], e[1]);
        }
    }

    /** A local TCP session identity (point, stream, flow), ordered like the reference tuple. */
    record Session(String point, long stream, String flow) implements Comparable<Session> {
        @Override
        public int compareTo(Session o) {
            int c = point.compareTo(o.point);
            if (c != 0) {
                return c;
            }
            c = Long.compare(stream, o.stream);
            return c != 0 ? c : flow.compareTo(o.flow);
        }

        /** {@code repr((point, stream, flow))}. */
        String repr() {
            return "(" + pyRepr(point) + ", " + stream + ", " + pyRepr(flow) + ")";
        }
    }

    static String pyRepr(String s) {
        boolean single = s.contains("'") && !s.contains("\"");
        char q = single ? '"' : '\'';
        StringBuilder out = new StringBuilder().append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == q) {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20 || c == 0x7f) {
                out.append(String.format("\\x%02x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append(q).toString();
    }

    static void linkTcpSessions(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Point> points = ctx.points;
        List<Session> sessions = new ArrayList<>();
        for (Object[] r : db.tuples(Sql.get("matching", "tcp_sessions"))) {
            sessions.add(new Session((String) r[0], (Long) r[1], (String) r[2]));
        }
        Map<Session, Session> parent = new HashMap<>();
        for (Session s : sessions) {
            parent.put(s, s);
        }
        Set<List<String>> pairs = new LinkedHashSet<>();
        for (List<String> path : List.of(topology.forward,
                topology.reverse.isEmpty() ? Topology.reversed(topology.forward) : topology.reverse)) {
            for (int i = 0; i + 1 < path.size(); i++) {
                pairs.add(sortedPair(path.get(i), path.get(i + 1)));
            }
        }
        Set<String> paDevices = new LinkedHashSet<>();
        for (Point p : topology.points) {
            if (p.vendor().equals("paloalto")) {
                paDevices.add(p.device());
            }
        }
        for (String device : paDevices) {
            List<String> sameNode = new ArrayList<>();
            for (Point p : topology.points) {
                if (p.device().equals(device) && p.vendor().equals("paloalto")) {
                    sameNode.add(p.id());
                }
            }
            for (int i = 0; i < sameNode.size(); i++) {
                for (int j = i + 1; j < sameNode.size(); j++) {
                    pairs.add(sortedPair(sameNode.get(i), sameNode.get(j)));
                }
            }
        }
        Set<String> proxyDevices = new LinkedHashSet<>();
        for (Point p : topology.points) {
            if (p.translation().equals("full_proxy")) {
                proxyDevices.add(p.device());
            }
        }
        for (List<String> pair : pairs) {
            String a = pair.get(0);
            String b = pair.get(1);
            if (points.get(a).device().equals(points.get(b).device()) && proxyDevices.contains(points.get(a).device())) {
                continue;
            }
            for (Object[] e : db.tuples(Sql.get("matching", "session_edges"), a, b)) {
                union(parent, new Session((String) e[0], (Long) e[1], (String) e[2]),
                        new Session((String) e[3], (Long) e[4], (String) e[5]));
            }
        }
        if (db.count(Sql.get("matching", "has_translation_links")) > 0) {
            for (Object[] r : db.tuples(Sql.get("matching", "translation_links"))) {
                if (r[1] == null || r[3] == null) {
                    continue;
                }
                Session x = new Session((String) r[0], (Long) r[1], (String) r[4]);
                Session y = new Session((String) r[2], (Long) r[3], (String) r[4]);
                if (parent.containsKey(x) && parent.containsKey(y)) {
                    union(parent, x, y);
                }
            }
        }
        if (db.count(Sql.get("matching", "has_byte_calibration")) > 0) {
            for (Object[] e : db.tuples(Sql.get("matching", "byte_calibration_links"))) {
                union(parent, new Session((String) e[0], (Long) e[1], (String) e[2]),
                        new Session((String) e[3], (Long) e[4], (String) e[5]));
            }
        }
        db.exec(Sql.get("matching", "session_map"));
        if (!sessions.isEmpty()) {
            List<Object[]> rows = new ArrayList<>();
            for (Session s : sessions) {
                rows.add(new Object[] {s.point(), s.stream(), s.flow(), sha256(root(parent, s).repr()).substring(0, 32)});
            }
            db.execMany(Sql.get("matching", "session_map_insert"), rows);
            db.exec(Sql.get("matching", "session_obs"));
        }
    }

    private static List<String> sortedPair(String a, String b) {
        return a.compareTo(b) <= 0 ? List.of(a, b) : List.of(b, a);
    }

    private static Session root(Map<Session, Session> parent, Session x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    private static void union(Map<Session, Session> parent, Session a, Session b) {
        Session left = root(parent, a);
        Session right = root(parent, b);
        if (!left.equals(right)) {
            boolean leftBigger = left.compareTo(right) > 0;
            parent.put(leftBigger ? left : right, leftBigger ? right : left);
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void propagateTranslationUnknown(EngineContext ctx) {
        if (ctx.db.count(Sql.get("matching", "unknown_models")) > 0) {
            ctx.db.exec(Sql.get("matching", "translation_unknown_obs"));
        }
    }

    // ------------------------------------------------------------------ byte_ranges.py

    static void makeMatchesView(Db db, boolean active) {
        db.exec(Sql.fmt("byte_ranges", "matches_view", "exclusion", active ? Sql.get("byte_ranges", "matches_exclusion") : "",
                "extra", active ? " " + Sql.get("byte_ranges", "matches_extra") : ""));
    }

    static String rangeCondition(String alias) {
        return Sql.fmt("byte_ranges", "range_condition", "a_alias", alias);
    }

    static void prepareByteRanges(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        String mod = "4294967296";
        long candidate = db.count(Sql.get("byte_ranges", "candidate"));
        db.exec(Sql.get("byte_ranges", "byte_flows"));
        if (candidate == 0) {
            makeMatchesView(db, false);
            ctx.byteActive = false;
            return;
        }
        db.exec(Sql.fmt("byte_ranges", "range_intervals", "MOD", mod));
        db.exec(Sql.get("byte_ranges", "byte_flows_insert"));
        if (db.count(Sql.get("byte_ranges", "byte_flow_count")) == 0) {
            makeMatchesView(db, false);
            ctx.byteActive = false;
            return;
        }
        db.exec(Sql.get("byte_ranges", "range_atoms"));
        db.exec(Sql.get("byte_ranges", "byte_work_units"));
        db.exec(Sql.get("byte_ranges", "drop_all_byte_obs"));
        for (Object[] r : db.tuples(Sql.get("byte_ranges", "byte_flow_list"))) {
            String flow = (String) r[0];
            matchByteFlow(ctx, flow, mod);
            db.exec(Sql.get("byte_ranges", "work_unit_insert"), flow);
            if (db.count(Sql.get("byte_ranges", "work_unit_count")) == 1) {
                db.exec(Sql.get("byte_ranges", "all_byte_obs_create"));
            } else {
                db.exec(Sql.get("byte_ranges", "all_byte_obs_insert"));
            }
        }
        db.exec(Sql.get("byte_ranges", "drop_byte_obs"));
        db.exec(Sql.get("byte_ranges", "rename_byte_obs"));
        List<Object> bad = new ArrayList<>();
        for (Object[] r : db.tuples(Sql.get("byte_ranges", "payload_conflicts"))) {
            bad.add(r[0]);
        }
        if (!bad.isEmpty()) {
            db.exec(Sql.get("byte_ranges", "conflict_obs"), bad);
            db.exec(Sql.get("byte_ranges", "conflict_byte_obs"), bad);
        }
        List<Object[]> pairs = new ArrayList<>();
        for (String direction : List.of("forward", "reverse")) {
            List<String> path = direction.equals("forward") ? topology.forward
                    : topology.reverse.isEmpty() ? Topology.reversed(topology.forward) : topology.reverse;
            for (int i = 0; i + 1 < path.size(); i++) {
                pairs.add(new Object[] {path.get(i), path.get(i + 1), direction});
            }
        }
        db.exec(Sql.get("byte_ranges", "byte_pairs"));
        db.execMany(Sql.get("byte_ranges", "byte_pairs_insert"), pairs);
        for (String name : List.of("byte_links", "byte_missing_atoms", "byte_missing", "byte_recovered_atoms",
                "byte_recovered_attempts", "byte_recoveries")) {
            db.exec(Sql.get("byte_ranges", name));
        }
        makeMatchesView(db, true);
        ctx.byteActive = true;
    }

    private static void matchByteFlow(EngineContext ctx, String flow, String mod) {
        Db db = ctx.db;
        Object[] sizes = db.one(Sql.get("byte_ranges", "largest_frame"), flow);
        if (((Number) sizes[1]).longValue() > 65535) {
            throw new IllegalArgumentException("A single frame exceeds 65535 byte boundaries; narrow the overlapping TCP ranges");
        }
        db.exec(Sql.fmt("byte_ranges", "flow_byte_obs", "MOD", mod), flow);
        db.exec(Sql.get("byte_ranges", "flow_clock_atoms"), flow);
        matchOccurrences(ctx, "byte_obs");
    }

    /** delivery.prepare_delivery: rejected retry substitutions leave observation_matches. */
    static void prepareDelivery(EngineContext ctx) {
        Db db = ctx.db;
        db.exec(Sql.get("delivery", "delivery_edges"));
        List<Object[]> edges = new ArrayList<>();
        for (String d : List.of("forward", "reverse")) {
            List<String> path = ctx.topology.path(d);
            for (int i = 0; i + 1 < path.size(); i++) {
                edges.add(new Object[] {path.get(i), path.get(i + 1), d});
            }
        }
        db.execMany(Sql.get("delivery", "delivery_edges_insert"), edges);
        db.exec(Sql.get("delivery", "delivery_repeats"));
        db.exec(Sql.get("delivery", "delivery_candidates"));
        db.exec(Sql.get("delivery", "delivery_id_policy"));
        db.exec(Sql.get("delivery", "delivery_rejections"), 1.0);
        db.exec(Sql.get("delivery", "delivery_rejection_peers"));
        String definition = (String) db.scalar(Sql.get("delivery", "matches_definition"));
        db.exec(definition.replaceFirst("CREATE VIEW", "CREATE OR REPLACE VIEW")
                .replaceFirst("observation_matches", "unfiltered_observation_matches"));
        db.exec(Sql.get("delivery", "filtered_matches"));
    }

    static boolean equalsAny(Object a, Object... values) {
        for (Object v : values) {
            if (Objects.equals(a, v)) {
                return true;
            }
        }
        return false;
    }
}
