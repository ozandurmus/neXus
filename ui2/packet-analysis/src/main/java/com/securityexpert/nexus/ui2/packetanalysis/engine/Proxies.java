package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Full-proxy request graph: reference {@code proxy_analysis} (boundaries, uncertainty, prepare_requests,
 * correlate), {@code proxy_matching.pair_requests}, {@code proxy_waterfall.add_dwell}/{@code interval},
 * {@code proxy_resets} (classify, reset_checks) and {@code application_evidence} (frame_lookup, frame_refs,
 * finding). Requests pair across independent TCP legs; TCP packets are never matched across a proxy.
 */
final class Proxies {
    static final String NOTE = "Requests pair across independent TCP legs. SNI pairs visible TLS setup only; encrypted application request boundaries remain unknown.";

    private Proxies() {
    }

    // ------------------------------------------------------------------ proxy_analysis

    static List<Point[]> boundaries(Topology topology) {
        Map<String, Point> points = topology.pointMap();
        List<Point[]> found = new ArrayList<>();
        for (int i = 0; i + 1 < topology.forward.size(); i++) {
            Point pa = points.get(topology.forward.get(i));
            Point pb = points.get(topology.forward.get(i + 1));
            if (pa.device().equals(pb.device()) && (pa.translation().equals("full_proxy") || pb.translation().equals("full_proxy"))
                    && !pa.vendor().equals("f5") && !pb.vendor().equals("f5")) {
                found.add(new Point[] {pa, pb});
            }
        }
        return found;
    }

    /** {@code uncertainty(models, a, b)}: null, or the summed clock uncertainty (0.0 within one capture). */
    static PyNum uncertainty(Map<String, ClockModel> models, Point a, Point b) {
        ClockModel ma = models.get(a.captureId());
        ClockModel mb = models.get(b.captureId());
        if (ma == null || mb == null || ma.uncertainty == null || mb.uncertainty == null || ma.confidence.equals("unknown")
                || mb.confidence.equals("unknown") || !Objects.equals(ma.domain, mb.domain)) {
            return null;
        }
        return a.captureId().equals(b.captureId()) ? new PyFloat(0.0) : PyArith.add(ma.uncertainty, mb.uncertainty);
    }

    static Object json(PyNum n) {
        return n == null ? null : ClockModel.json(n);
    }

    static Object times1000(PyNum n) {
        return n == null ? null : ClockModel.json(PyArith.mul(n, new PyInt(1000)));
    }

    static void prepareRequests(Db db) {
        if (db.count(Sql.get("proxy", "has_requests")) == 0) {
            db.exec(Sql.get("proxy", "empty_requests"));
            return;
        }
        db.exec(Sql.get("proxy", "requests"));
        db.exec(Sql.get("proxy", "http_pdus"));
        db.exec(Sql.get("proxy", "clear_http_responses"));
        db.exec(Sql.get("proxy", "http_responses"));
    }

    /** Stage: {@code correlate} then {@code add_dwell}. */
    static void correlateStage(EngineContext ctx) {
        Map<String, Object> proxies = correlate(ctx.db, ctx.topology, ctx.models, ctx.applicationStatus);
        Parity.record(ctx, "correlate", proxies);
        addDwell(ctx.db, ctx.topology, ctx.models, proxies);
        Parity.record(ctx, "add_dwell", proxies);
        ctx.snapshot("proxy", List.of("proxy_requests", "proxy_pairs", "app_protocol", "tcp_point_analysis", "app_protocol_cache"));
        ctx.path.proxies = proxies;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> correlate(Db db, Topology topology, Map<String, ClockModel> models,
            List<Map<String, Object>> statuses) {
        prepareRequests(db);
        List<Row> requests = db.rows(Sql.get("proxy", "ordered_requests"));
        Map<Object, Row> lookup = new LinkedHashMap<>();
        for (Row r : requests) {
            lookup.put(r.get("id"), r);
        }
        Set<List<Object>> keys = new LinkedHashSet<>();
        for (Row r : requests) {
            for (String k : List.of("first_frame", "last_frame", "response_frame")) {
                if (Evidence.truthy(r.get(k))) {
                    keys.add(Arrays.asList(r.get("point"), r.get(k)));
                }
            }
        }
        Map<List<Object>, Map<String, Object>> referenceLookup = frameLookup(db, keys);
        Map<Object, Object> failures = new LinkedHashMap<>();
        for (Map<String, Object> s : statuses) {
            if (Evidence.truthy(s.get("reason"))) {
                failures.put(s.get("point"), s.get("reason"));
            }
        }
        List<Map<String, Object>> found = new ArrayList<>();
        for (Point[] edge : boundaries(topology)) {
            Point a = edge[0];
            Point b = edge[1];
            List<Map<String, Object>> clients = new ArrayList<>();
            List<Map<String, Object>> servers = new ArrayList<>();
            for (Row r : requests) {
                if (a.id().equals(r.get("point"))) {
                    clients.add(r);
                }
                if (b.id().equals(r.get("point"))) {
                    servers.add(r);
                }
            }
            List<Map<String, Object>> pairs = pairRequests(clients, servers, topology.matchWindowMs / 1000, uncertainty(models, a, b),
                    true, db);
            if (pairs.isEmpty()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("status", "unknown");
                p.put("client_id", null);
                p.put("server_id", null);
                Object reason = firstTruthy(failures.get(a.id()), failures.get(b.id()), "No decoded application requests at this proxy");
                p.put("reason", reason);
                pairs.add(p);
            }
            for (Map<String, Object> pair : pairs) {
                Row c = lookup.get(pair.get("client_id"));
                Row s = lookup.get(pair.get("server_id"));
                Map<String, Object> either = c != null && !c.isEmpty() ? c : s != null && !s.isEmpty() ? s : Map.of();
                pair.put("device", a.device());
                pair.put("point_a", a.id());
                pair.put("point_b", b.id());
                pair.put("kind", either.get("kind"));
                pair.put("request", firstTruthy(either.get("line"), either.get("sni")));
                pair.put("client_flow", c != null ? c.get("flow") : null);
                pair.put("server_flow", s != null ? s.get("flow") : null);
                pair.put("time", either.get("start"));
                List<Map<String, Object>> refPackets = new ArrayList<>();
                for (Row r : Arrays.asList(c, s)) {
                    if (r == null || r.isEmpty()) {
                        continue;
                    }
                    for (String k : List.of("first_frame", "last_frame", "response_frame")) {
                        if (Evidence.truthy(r.get(k))) {
                            Map<String, Object> ref = new LinkedHashMap<>();
                            ref.put("point", r.get("point"));
                            ref.put("frame", r.get(k));
                            refPackets.add(ref);
                        }
                    }
                }
                pair.put("evidence", frameRefs(db, refPackets, referenceLookup));
                if (Evidence.truthy(failures.get(a.id())) || Evidence.truthy(failures.get(b.id()))) {
                    pair.put("status", "unknown");
                    pair.put("reason", firstTruthy(failures.get(a.id()), failures.get(b.id())));
                }
                found.add(pair);
            }
        }
        db.exec(Sql.get("proxy", "pairs_table"));
        List<Object[]> batch = new ArrayList<>();
        for (Map<String, Object> p : found) {
            batch.add(new Object[] {p.get("device"), p.get("client_id"), p.get("server_id"), p.get("status"),
                PyJsonWriter.dumps(p, false)});
        }
        db.execMany(Sql.get("proxy", "pairs_insert"), batch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transactions", new ArrayList<>(found.subList(0, Math.min(200, found.size()))));
        out.put("transaction_count", (long) found.size());
        out.put("request_count", (long) requests.size());
        out.put("note", NOTE);
        return out;
    }

    static Object firstTruthy(Object... values) {
        Object last = null;
        for (Object v : values) {
            if (Evidence.truthy(v)) {
                return v;
            }
            last = v;
        }
        return last;
    }

    // ------------------------------------------------------------------ proxy_matching

    private record Edge(Object c, Object s, boolean[] rank, double distance) {
        int compareRank(Edge o) {
            for (int i = 0; i < 3; i++) {
                int x = Boolean.compare(rank[i], o.rank[i]);
                if (x != 0) {
                    return x;
                }
            }
            return 0;
        }
    }

    /** {@code pair_requests(clients, servers, window_s, uncertainty_s, declared, db)}. */
    static List<Map<String, Object>> pairRequests(List<Map<String, Object>> clients, List<Map<String, Object>> servers, double windowS,
            PyNum uncertaintyS, boolean declared, Db db) {
        Map<Object, Map<String, Object>> pendingC = new LinkedHashMap<>();
        for (Map<String, Object> r : clients) {
            pendingC.put(r.get("id"), r);
        }
        Map<Object, Map<String, Object>> pendingS = new LinkedHashMap<>();
        for (Map<String, Object> r : servers) {
            pendingS.put(r.get("id"), r);
        }
        List<Map<String, Object>> found = new ArrayList<>();
        String blocked = declared ? null : "Boundary is not declared full_proxy";
        if (uncertaintyS == null && blocked == null) {
            blocked = "Clock uncertainty unavailable; bounded pairing window unverified";
        }
        List<Edge> candidates = new ArrayList<>();
        if (blocked == null) {
            String schema = Sql.get("proxy", "request_schema");
            Object u = json(uncertaintyS);
            List<Row> edges = db.rows(Sql.get("proxy", "pair_edges"), PyJsonWriter.dumps(clients, false), schema,
                    PyJsonWriter.dumps(servers, false), schema, u, windowS, u);
            if (edges.size() > 50000) {
                blocked = "More than 50,000 request candidates; narrow the pairing window";
            } else {
                for (Row e : edges) {
                    candidates.add(new Edge(e.get("c"), e.get("s"),
                            new boolean[] {(Boolean) e.get("identity"), (Boolean) e.get("xff"), (Boolean) e.get("sni")},
                            e.dbl("distance")));
                }
            }
            double u2 = ClockModel.value(uncertaintyS);
            double tolerance = Math.max(2 * u2, 1e-6);
            int rounds = 0;
            while (!pendingC.isEmpty() && !pendingS.isEmpty() && blocked == null) {
                List<Edge> active = new ArrayList<>();
                for (Edge e : candidates) {
                    if (pendingC.containsKey(e.c()) && pendingS.containsKey(e.s())) {
                        active.add(e);
                    }
                }
                rounds++;
                if (rounds > 100) {
                    blocked = "Request graph exceeds the 100-round resolution budget";
                    break;
                }
                Map<Object, List<Edge>> byC = new LinkedHashMap<>();
                Map<Object, List<Edge>> byS = new LinkedHashMap<>();
                for (Edge e : active) {
                    byC.computeIfAbsent(e.c(), k -> new ArrayList<>()).add(e);
                    byS.computeIfAbsent(e.s(), k -> new ArrayList<>()).add(e);
                }
                List<Edge> winners = new ArrayList<>();
                for (Object cid : pendingC.keySet()) {
                    Edge choice = best(byC.getOrDefault(cid, List.of()), tolerance);
                    if (choice != null && best(byS.getOrDefault(choice.s(), List.of()), tolerance) == choice) {
                        winners.add(choice);
                    }
                }
                if (winners.isEmpty()) {
                    break;
                }
                for (Edge e : winners) {
                    Map<String, Object> c = pendingC.remove(e.c());
                    Map<String, Object> s = pendingS.remove(e.s());
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("status", "matched");
                    p.put("client_id", c.get("id"));
                    p.put("server_id", s.get("id"));
                    p.put("reason", null);
                    p.put("evidence_rank", e.rank()[0] ? "HTTP request line + Host" : e.rank()[1] ? "X-Forwarded-For" : "TLS SNI");
                    p.put("xff_confirmed", e.rank()[1]);
                    p.put("timing_delta_s", ((Number) s.get("start")).doubleValue() - ((Number) c.get("start")).doubleValue());
                    found.add(p);
                }
            }
        }
        for (String side : List.of("client", "server")) {
            for (Map<String, Object> r : (side.equals("client") ? pendingC : pendingS).values()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("status", "unknown");
                p.put("client_id", side.equals("client") ? r.get("id") : null);
                p.put("server_id", side.equals("server") ? r.get("id") : null);
                p.put("reason", firstTruthy(blocked, r.get("reason"),
                        "No unique compatible one-to-one request candidate inside the pairing window"));
                found.add(p);
            }
        }
        return found;
    }

    private static Edge best(List<Edge> edges, double tolerance) {
        if (edges.isEmpty()) {
            return null;
        }
        Edge top = edges.get(0);
        for (Edge e : edges) {
            if (e.compareRank(top) > 0) {
                top = e;
            }
        }
        List<Edge> ranked = new ArrayList<>();
        for (Edge e : edges) {
            if (e.compareRank(top) == 0) {
                ranked.add(e);
            }
        }
        double distance = ranked.get(0).distance();
        for (Edge e : ranked) {
            if (e.distance() < distance) {
                distance = e.distance();
            }
        }
        List<Edge> close = new ArrayList<>();
        for (Edge e : ranked) {
            if (e.distance() - distance <= tolerance) {
                close.add(e);
            }
        }
        return close.size() == 1 ? close.get(0) : null;
    }

    // ------------------------------------------------------------------ proxy_waterfall

    static Map<String, Object> interval(Object a, Object b, PyNum uncertaintyS) {
        String reason = null;
        if (a == null || b == null) {
            reason = "Application byte boundary unavailable";
        } else if (uncertaintyS == null) {
            reason = "Clock alignment unreliable or uncertainty unverified";
        } else if (((Number) b).doubleValue() < ((Number) a).doubleValue()) {
            reason = "Streaming/order or clock inconsistency: request forwarded before ingress completion";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("duration_ms", reason != null ? null : (((Number) b).doubleValue() - ((Number) a).doubleValue()) * 1000);
        out.put("uncertainty_ms", times1000(uncertaintyS));
        out.put("reason", reason);
        return out;
    }

    @SuppressWarnings("unchecked")
    static void addDwell(Db db, Topology topology, Map<String, ClockModel> models, Map<String, Object> proxies) {
        if (!Evidence.truthy(proxies.get("transactions"))) {
            return;
        }
        Map<String, Point> points = topology.pointMap();
        Map<Object, Row> lookup = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("proxy", "all_requests"))) {
            lookup.put(r.get("id"), r);
        }
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (Object[] r : db.tuples(Sql.get("proxy", "pair_metadata"))) {
            pairs.add((Map<String, Object>) Evidence.loads((String) r[0]));
        }
        for (Map<String, Object> p : pairs) {
            Row c = lookup.get(p.get("client_id"));
            Row s = lookup.get(p.get("server_id"));
            PyNum u = uncertainty(models, points.get(p.get("point_a")), points.get(p.get("point_b")));
            p.put("request_dwell", interval(c != null ? c.get("complete") : null, s != null ? s.get("start") : null, u));
            p.put("response_dwell", interval(s != null ? s.get("response") : null, c != null ? c.get("response") : null, u));
            if (!"matched".equals(p.get("status"))) {
                for (String key : List.of("request_dwell", "response_dwell")) {
                    Map<String, Object> d = (Map<String, Object>) p.get(key);
                    d.put("duration_ms", null);
                    d.put("reason", p.get("reason"));
                }
            }
            if ("tls_setup".equals(p.get("kind"))) {
                for (String key : List.of("request_dwell", "response_dwell")) {
                    Map<String, Object> d = (Map<String, Object>) p.get(key);
                    d.put("duration_ms", null);
                    d.put("reason", "TLS setup pairing; encrypted application request boundaries unknown");
                }
            }
            db.exec(Sql.get("proxy", "update_pair"), PyJsonWriter.dumps(p, false), p.get("device"), p.get("client_id"), p.get("server_id"));
        }
        proxies.put("transactions", new ArrayList<>(pairs.subList(0, Math.min(200, pairs.size()))));
    }

    // ------------------------------------------------------------------ application_evidence

    static Map<List<Object>, Map<String, Object>> frameLookup(Db db, Set<List<Object>> keys) {
        Map<List<Object>, Map<String, Object>> out = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        List<Object> names = new ArrayList<>();
        for (List<Object> k : keys) {
            names.add(k.get(0) + ":" + k.get(1));
        }
        List<Object> params = new ArrayList<>();
        params.add(names);
        for (Map<String, Object> r : Evidence.evidence(db, Sql.get("proxy", "frame_lookup"), params, keys.size(), false)) {
            out.put(Arrays.asList(r.get("point"), r.get("frame")), r);
        }
        return out;
    }

    private static final Comparator<List<Object>> KEY_ORDER = (x, y) -> {
        int c = ((String) x.get(0)).compareTo((String) y.get(0));
        return c != 0 ? c : Long.compare(((Number) x.get(1)).longValue(), ((Number) y.get(1)).longValue());
    };

    static List<Map<String, Object>> frameRefs(Db db, List<? extends Map<String, Object>> packets,
            Map<List<Object>, Map<String, Object>> lookup) {
        Set<List<Object>> set = new LinkedHashSet<>();
        for (Map<String, Object> p : packets) {
            if (p != null && !p.isEmpty() && Evidence.truthy(p.get("frame"))) {
                set.add(Arrays.asList(p.get("point"), p.get("frame")));
            }
        }
        List<List<Object>> keys = new ArrayList<>(set);
        keys.sort(KEY_ORDER);
        if (lookup != null) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (List<Object> k : keys) {
                if (lookup.containsKey(k)) {
                    out.add(lookup.get(k));
                }
            }
            return out;
        }
        if (keys.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> parts = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (List<Object> k : keys) {
            parts.add("(o.point=? AND o.frame=?)");
            values.addAll(k);
        }
        return Evidence.evidence(db, String.join(" OR ", parts), values, keys.size(), false);
    }

    static Map<String, Object> finding(Db db, Map<String, Object> segment, String kind, String summary,
            List<? extends Map<String, Object>> packets, Map<String, Object> metrics, boolean supported, String severity,
            Map<List<Object>, Map<String, Object>> lookup) {
        List<Double> stamps = new ArrayList<>();
        for (Map<String, Object> p : packets) {
            if (p != null && !p.isEmpty() && p.get("corrected") != null) {
                stamps.add(((Number) p.get("corrected")).doubleValue());
            }
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", "proxy:" + kind + ":" + segment.get("id") + ":" + packets.get(0).get("frame"));
        f.put("type", kind);
        f.put("hop", segment.get("id"));
        f.put("device", segment.get("device"));
        f.put("direction", segment.get("direction"));
        if (stamps.isEmpty()) {
            f.put("time_range", Arrays.asList(null, null));
        } else {
            Double lo = stamps.get(0);
            Double hi = stamps.get(0);
            for (Double t : stamps) {
                lo = Evidence.pyMin(lo, t);
                hi = Evidence.pyMax(hi, t);
            }
            f.put("time_range", new ArrayList<>(List.of(lo, hi)));
        }
        f.put("severity", supported ? severity : "unknown");
        f.put("confidence", supported ? "supported" : "unknown");
        f.put("summary", summary);
        f.put("tooltip", "Evidence describes observed request/certificate/reset events. Missing capture coverage and ambiguous candidates cannot establish origin.");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", 1L);
        m.putAll(metrics);
        f.put("metrics", m);
        f.put("evidence", frameRefs(db, packets, lookup));
        f.put("cause", "unknown");
        f.put("clock_caveat", "Clock-corrected estimates; see uncertainty in metrics.");
        return f;
    }

    // ------------------------------------------------------------------ proxy_resets

    static String[] classify(Map<String, Object> output, List<? extends Map<String, Object>> triggers, PyNum uncertaintyS, boolean covered,
            boolean externalTrigger, boolean ambiguous) {
        if (uncertaintyS == null) {
            return new String[] {"unknown", "Clock uncertainty unavailable or opposite legs belong to unaligned clock domains"};
        }
        if (!covered) {
            return new String[] {"unknown", "Capture window or leg quality insufficient to rule out an opposite-leg trigger"};
        }
        if (externalTrigger) {
            return new String[] {"unknown", "Reset trigger observed on other points but missing at proxy ingress"};
        }
        if (ambiguous) {
            return new String[] {"unknown", "Multiple requests share this leg; reset association ambiguous"};
        }
        double limit = ((Number) output.get("corrected")).doubleValue() + ClockModel.value(uncertaintyS);
        int causal = 0;
        for (Map<String, Object> p : triggers) {
            if (((Number) p.get("corrected")).doubleValue() <= limit) {
                causal++;
            }
        }
        if (causal > 1) {
            return new String[] {"unknown", "Multiple plausible opposite-leg reset triggers"};
        }
        return causal == 1 ? new String[] {"propagated", null} : new String[] {"originated", null};
    }

    static void resetStage(EngineContext ctx) {
        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Object> legReport = resetChecks(ctx, findings);
        Parity.record(ctx, "reset_checks", Parity.pair(findings, legReport));
        ctx.path.proxies.putAll(legReport);
        ctx.path.proxyFindings = findings;
        ctx.findings.addAll(findings);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resetChecks(EngineContext ctx, List<Map<String, Object>> findings) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        List<Point[]> edges = boundaries(topology);
        Map<String, Object> report = new LinkedHashMap<>();
        if (edges.isEmpty()) {
            report.put("legs", new ArrayList<>());
            report.put("leg_count", 0L);
            report.put("reset_notes", new ArrayList<>());
            return report;
        }
        db.exec(Sql.get("proxy", "opaque_edges"));
        List<Object[]> batch = new ArrayList<>();
        for (Point[] e : edges) {
            batch.add(new Object[] {e[0].id(), e[1].id()});
        }
        db.execMany(Sql.get("proxy", "opaque_edges_insert"), batch);
        Map<Object, Row> requests = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("proxy", "all_requests"))) {
            requests.put(r.get("id"), r);
        }
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (Object[] r : db.tuples(Sql.get("proxy", "matched_metadata"))) {
            pairs.add((Map<String, Object>) Evidence.loads((String) r[0]));
        }
        List<Row> resets = db.rows(Sql.get("proxy", "resets"));
        Map<Object, Row> cov = new LinkedHashMap<>();
        for (Row c : ctx.coverage) {
            cov.put(c.get("point"), c);
        }
        List<Object> notes = new ArrayList<>();
        double window = topology.matchWindowMs / 1000;
        for (Point[] edge : edges) {
            Point a = edge[0];
            Point b = edge[1];
            PyNum u = uncertainty(ctx.models, a, b);
            double uv = u == null ? 0 : ClockModel.value(u);
            Map<String, Object> segment = null;
            for (Map<String, Object> s : ctx.segments) {
                if ("forward".equals(s.get("direction")) && a.id().equals(s.get("point_a")) && b.id().equals(s.get("point_b"))) {
                    segment = s;
                    break;
                }
            }
            if (segment == null) {
                continue;
            }
            for (Row out : resets) {
                if (!a.id().equals(out.get("point")) && !b.id().equals(out.get("point"))) {
                    continue;
                }
                List<Object[]> possible = new ArrayList<>();
                for (Map<String, Object> p : pairs) {
                    if (!Objects.equals(p.get("device"), a.device())) {
                        continue;
                    }
                    Row c = requests.get(p.get("client_id"));
                    Row s = requests.get(p.get("server_id"));
                    String side = a.id().equals(out.get("point")) && Objects.equals(out.get("flow"), c.get("flow"))
                            && !Objects.equals(out.get("canon"), c.get("canon")) ? "client"
                            : b.id().equals(out.get("point")) && Objects.equals(out.get("flow"), s.get("flow"))
                                    && Objects.equals(out.get("canon"), s.get("canon")) ? "server" : null;
                    if (side != null && out.get("corrected") != null && c.get("start") != null && s.get("complete") != null) {
                        double completion = Math.max(c.dbl("complete"), s.dbl("complete"));
                        double t = out.dbl("corrected");
                        if (completion <= t && t <= completion + window) {
                            possible.add(new Object[] {c, s, side});
                        }
                    }
                }
                if (possible.isEmpty()) {
                    continue;
                }
                Row c = (Row) possible.get(0)[0];
                Row s = (Row) possible.get(0)[1];
                String side = (String) possible.get(0)[2];
                Row opposite = side.equals("client") ? s : c;
                double t = out.dbl("corrected");
                List<Row> candidates = new ArrayList<>();
                for (Row p : resets) {
                    boolean incoming = side.equals("client") ? !Objects.equals(p.get("canon"), s.get("canon"))
                            : Objects.equals(p.get("canon"), c.get("canon"));
                    if (Objects.equals(p.get("flow"), opposite.get("flow")) && incoming && p.get("corrected") != null
                            && t - window <= p.dbl("corrected") && p.dbl("corrected") <= t + uv) {
                        candidates.add(p);
                    }
                }
                List<Row> triggers = new ArrayList<>();
                for (Row p : candidates) {
                    if (Objects.equals(p.get("point"), opposite.get("point"))) {
                        triggers.add(p);
                    }
                }
                boolean external = !candidates.isEmpty() && triggers.isEmpty();
                boolean covered = true;
                for (String p : List.of(a.id(), b.id())) {
                    Row x = cov.get(p);
                    if (!(x != null && !x.isEmpty() && x.get("start") != null && x.get("end") != null && x.dbl("start") <= t - window
                            && x.dbl("end") >= t + uv)) {
                        covered = false;
                        break;
                    }
                }
                long degraded = db.count(Sql.get("proxy", "degraded"), a.id(), b.id(), c.get("flow"), s.get("flow"), t - window, t + window);
                String[] decision = classify(out, triggers, u, covered && degraded == 0, external, possible.size() != 1);
                List<Map<String, Object>> packets = new ArrayList<>();
                packets.add(out);
                packets.addAll(triggers);
                if (decision[0].equals("unknown")) {
                    Map<String, Object> note = new LinkedHashMap<>();
                    note.put("device", a.device());
                    note.put("status", decision[0]);
                    note.put("reason", decision[1]);
                    note.put("evidence", frameRefs(db, packets, null));
                    notes.add(note);
                    continue;
                }
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("client_flow", c.get("flow"));
                metrics.put("server_flow", s.get("flow"));
                metrics.put("uncertainty_ms", times1000(u));
                metrics.put("trigger_count", (long) triggers.size());
                findings.add(finding(db, segment, "proxy_reset_" + decision[0], "Reset " + decision[0] + " by " + a.device(), packets,
                        metrics, true, decision[0].equals("propagated") ? "quality" : "high", null));
            }
        }
        List<Row> legs = db.rows(Sql.get("proxy", "legs"));
        Map<List<Object>, Map<String, Object>> lossByLeg = new LinkedHashMap<>();
        for (Object[] r : db.tuples(Sql.get("proxy", "loss_by_leg"))) {
            lossByLeg.computeIfAbsent(Arrays.asList(r[0], r[1]), k -> new LinkedHashMap<>()).put((String) r[2], r[3]);
        }
        for (Row leg : legs) {
            Map<String, Object> loss = lossByLeg.get(Arrays.asList(leg.get("flow"), leg.get("point")));
            leg.put("loss_by_class", loss != null ? loss : new LinkedHashMap<>());
        }
        report.put("legs", new ArrayList<>(legs.subList(0, Math.min(200, legs.size()))));
        report.put("leg_count", (long) legs.size());
        report.put("reset_notes", notes);
        return report;
    }

    // ------------------------------------------------------------------ restrict_onset_domains

    @SuppressWarnings("unchecked")
    static void restrictOnsetDomains(Map<String, Object> onsets, Map<String, Point> points, Map<String, ClockModel> models) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) onsets.get("items");
        for (Map<String, Object> item : items) {
            Point point = item.containsKey("point_a") ? points.get((String) item.get("point_a")) : null;
            if (point == null) {
                point = points.get(((String) item.get("segment")).split(":")[1]);
            }
            item.put("clock_domain", point != null && models.containsKey(point.captureId())
                    ? models.get(point.captureId()).domain : null);
        }
        Map<String, Map<String, Object>> directions = (Map<String, Map<String, Object>>) onsets.get("directions");
        for (Map.Entry<String, Map<String, Object>> e : directions.entrySet()) {
            String direction = e.getKey();
            Map<String, Object> value = e.getValue();
            Set<Object> domains = new LinkedHashSet<>();
            for (Map<String, Object> i : items) {
                if (Objects.equals(i.get("direction"), direction) && "network_segment".equals(i.get("scope"))) {
                    domains.add(i.get("clock_domain"));
                }
            }
            if (domains.size() > 1) {
                value.put("propagation_order", new ArrayList<>());
                value.put("prime_suspects", new ArrayList<>());
                value.put("first_time", null);
                value.put("caveat", "Unknown cross-proxy propagation order: independent clock domains have no alignment evidence.");
            }
        }
        Set<Object> allDomains = new LinkedHashSet<>();
        for (Map<String, Object> i : items) {
            if ("network_segment".equals(i.get("scope"))) {
                allDomains.add(i.get("clock_domain"));
            }
        }
        if (allDomains.size() > 1) {
            onsets.put("summary", "Onsets detected per segment; first hop and propagation order across proxy legs are unknown because their clock domains are unaligned.");
        }
        onsets.put("items", orderedOnsets(items, directions));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> orderedOnsets(List<Map<String, Object>> items,
            Map<String, Map<String, Object>> directions) {
        Set<Object> primes = new LinkedHashSet<>();
        for (Map<String, Object> d : directions.values()) {
            List<Object> ps = (List<Object>) d.getOrDefault("prime_suspects", List.of());
            if (ps != null) {
                primes.addAll(ps);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(items);
        out.sort((x, y) -> {
            int rx = "capture_signal".equals(x.get("scope")) ? 2 : primes.contains(x.get("segment")) ? 0 : 1;
            int ry = "capture_signal".equals(y.get("scope")) ? 2 : primes.contains(y.get("segment")) ? 0 : 1;
            if (rx != ry) {
                return Integer.compare(rx, ry);
            }
            double tx = x.get("time") instanceof Number n ? n.doubleValue() : 0.0;
            double ty = y.get("time") instanceof Number n ? n.doubleValue() : 0.0;
            int c = Double.compare(tx, ty);
            if (c != 0) {
                return c;
            }
            c = String.valueOf(x.get("segment")).compareTo(String.valueOf(y.get("segment")));
            return c != 0 ? c : String.valueOf(x.get("metric")).compareTo(String.valueOf(y.get("metric")));
        });
        return out;
    }

    // ------------------------------------------------------------------ proxy_waterfall.waterfall / handshakes

    static Map<String, ClockModel> clockModels(Map<String, Object> clocks) {
        Map<String, ClockModel> out = new LinkedHashMap<>();
        if (clocks == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : clocks.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            ClockModel cm = new ClockModel();
            cm.offset = pyNum(m.get("offset"));
            if (m.containsKey("drift")) {
                cm.drift = pyNum(m.get("drift"));
            }
            if (m.containsKey("epoch")) {
                cm.epoch = pyNum(m.get("epoch"));
            }
            cm.uncertainty = pyNum(m.get("uncertainty"));
            if (m.containsKey("confidence")) {
                cm.confidence = (String) m.get("confidence");
            }
            if (m.containsKey("reason")) {
                cm.reason = (String) m.get("reason");
            }
            if (m.get("samples") instanceof Number n) {
                cm.samples = n.longValue();
            }
            if (m.get("evidence") instanceof List<?> ev) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> evList = (List<Map<String, Object>>) ev;
                cm.evidence = evList;
            }
            cm.domain = (String) m.get("domain");
            cm.sourceDomain = (String) m.get("source_domain");
            if (m.containsKey("timestamp_jitter") && m.get("timestamp_jitter") != null) {
                cm.timestampJitter = pyNum(m.get("timestamp_jitter"));
            }
            out.put(e.getKey(), cm);
        }
        return out;
    }

    private static PyNum pyNum(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof PyNum p) {
            return p;
        }
        if (v instanceof Integer || v instanceof Long) {
            return new PyInt(((Number) v).longValue());
        }
        return new PyFloat(((Number) v).doubleValue());
    }

    private static boolean alias(Map<String, Object> a, Map<String, Object> b) {
        return Objects.equals(a.get("flow"), b.get("flow"))
                && Objects.equals(a.get("start_seq"), b.get("start_seq"))
                && Objects.equals(a.get("line"), b.get("line"))
                && Objects.equals(a.get("host"), b.get("host"));
    }

    private static Row root(Row r, Map<Object, Row> byid, List<Map<String, Object>> pairs) {
        Set<Object> seen = new LinkedHashSet<>();
        while (!seen.contains(r.get("id"))) {
            seen.add(r.get("id"));
            List<Row> candidates = new ArrayList<>();
            for (Map<String, Object> p : pairs) {
                Row c = byid.get(p.get("client_id"));
                Row s = byid.get(p.get("server_id"));
                if (c != null && s != null && alias(r, s)) {
                    candidates.add(c);
                }
            }
            if (candidates.size() != 1) {
                break;
            }
            r = candidates.get(0);
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> waterfall(Db db, Map<String, Object> report, Topology topology, String flow,
            Double start, Double end, int requestIndex) {
        List<Row> requests = db.rows(Sql.get("proxy", "waterfall_requests"));
        if (requests.size() > 20000) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", new ArrayList<>());
            out.put("requests", new ArrayList<>());
            out.put("reason", "Proxy waterfall exceeds 20,000 request observations; narrow capture");
            return out;
        }
        Map<Object, Row> byid = new LinkedHashMap<>();
        for (Row r : requests) {
            byid.put(r.get("id"), r);
        }
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (Object[] r : db.tuples(Sql.get("proxy", "matched_metadata"))) {
            pairs.add((Map<String, Object>) Evidence.loads((String) r[0]));
        }
        Map<Object, Row> seedMap = new LinkedHashMap<>();
        for (Row r : requests) {
            Double rs = r.get("start") != null ? r.dbl("start") : null;
            if (Objects.equals(r.get("flow"), flow)
                    && (start == null || rs != null && rs >= start)
                    && (end == null || rs != null && rs < end)) {
                Row rt = root(r, byid, pairs);
                seedMap.put(rt.get("id"), rt);
            }
        }
        List<Row> seeds = new ArrayList<>(seedMap.values());
        seeds.sort(Comparator.comparingDouble(r -> r.get("start") != null ? r.dbl("start") : 0.0));
        if (seeds.size() > 100) {
            seeds = new ArrayList<>(seeds.subList(0, 100));
        }
        List<Map<String, Object>> choices = new ArrayList<>();
        for (int i = 0; i < seeds.size(); i++) {
            Row r = seeds.get(i);
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("index", (long) i);
            ch.put("label", r.get("line"));
            ch.put("time", r.get("start"));
            choices.add(ch);
        }
        Map<String, ClockModel> models = clockModels((Map<String, Object>) report.get("clocks"));
        if (requestIndex >= seeds.size()) {
            Map<String, Map<String, Object>> fallbackStage = new LinkedHashMap<>();
            fallbackStage.put("selected", Map.of("flow", flow));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", handshakes(db, topology, models, fallbackStage));
            out.put("requests", choices);
            out.put("reason", "No uniquely correlated cleartext HTTP/1.x request for this leg/window");
            return out;
        }
        Row seed = seeds.get(requestIndex);
        Map<String, Point> points = topology.pointMap();
        Map<String, Row> stage = new LinkedHashMap<>();
        for (Row r : requests) {
            if (alias(seed, r)) {
                stage.put(r.str("point"), r);
            }
        }
        for (int iter = 0; iter < topology.points.size(); iter++) {
            boolean changed = false;
            for (Map<String, Object> p : pairs) {
                Row c = byid.get(p.get("client_id"));
                Row s = byid.get(p.get("server_id"));
                if (c != null && s != null && stage.containsKey(c.str("point"))
                        && Objects.equals(stage.get(c.str("point")).get("id"), c.get("id"))
                        && !stage.containsKey(s.str("point"))) {
                    for (Row r : requests) {
                        if (alias(s, r)) {
                            stage.put(r.str("point"), r);
                        }
                    }
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        List<Map<String, Object>> bars = new ArrayList<>();
        Double origin = seed.get("start") != null ? seed.dbl("start") : null;
        List<String> reversePath = topology.reverse.isEmpty() ? Topology.reversed(topology.forward) : topology.reverse;
        for (Object[] dirPath : new Object[][] {{"forward", topology.forward}, {"reverse", reversePath}}) {
            String direction = (String) dirPath[0];
            List<String> path = (List<String>) dirPath[1];
            for (int i = 0; i + 1 < path.size(); i++) {
                String a = path.get(i);
                String b = path.get(i + 1);
                Row ra = stage.get(a);
                Row rb = stage.get(b);
                boolean device = points.get(a).device().equals(points.get(b).device());
                boolean proxy = device && ("full_proxy".equals(points.get(a).translation())
                        || "full_proxy".equals(points.get(b).translation()));
                Object ta = ra != null ? (proxy && "forward".equals(direction) ? ra.get("complete")
                        : "forward".equals(direction) ? ra.get("start") : ra.get("response")) : null;
                Object tb = rb != null ? ("forward".equals(direction) ? rb.get("start") : rb.get("response")) : null;
                String reason = ra != null && rb != null && ra.bool("ready") && rb.bool("ready")
                        ? null : "Request correlation or captured byte boundary unknown";
                if (ra != null && rb != null && !proxy && !alias(ra, rb)) {
                    reason = "No shared request evidence on this link";
                }
                if (ra != null && rb != null && proxy) {
                    Row c = "forward".equals(direction) ? ra : rb;
                    Row s = "forward".equals(direction) ? rb : ra;
                    boolean paired = false;
                    for (Map<String, Object> p : pairs) {
                        if (Objects.equals(p.get("client_id"), c.get("id")) && Objects.equals(p.get("server_id"), s.get("id"))) {
                            paired = true;
                            break;
                        }
                    }
                    if (!paired) {
                        reason = "No unique full-proxy request pair";
                    }
                }
                bars.add(waterfallBar(db, models, points, origin, a, b,
                        points.get(a).label() + " \u2192 " + points.get(b).label(),
                        proxy ? "proxy_dwell" : device ? "device_dwell" : "link",
                        direction, ta, tb, ra, rb, reason));
            }
            if ("forward".equals(direction)) {
                String last = path.get(path.size() - 1);
                Row r = stage.get(last);
                bars.add(waterfallBar(db, models, points, origin, last, last,
                        "Server processing", "server_processing", "reverse",
                        r != null ? r.get("complete") : null,
                        r != null ? r.get("response") : null,
                        r, r, null));
            }
        }
        List<Map<String, Object>> items = new ArrayList<>(handshakes(db, topology, models, stage));
        Map<String, Object> httpItem = new LinkedHashMap<>();
        httpItem.put("kind", "http");
        httpItem.put("title", seed.get("line"));
        httpItem.put("bars", bars);
        httpItem.put("note", "HTTP request graph crosses proxy connections; TCP handshake remains local to each leg.");
        items.add(httpItem);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("requests", choices);
        out.put("reason", null);
        return out;
    }

    private static Map<String, Object> waterfallBar(Db db, Map<String, ClockModel> models, Map<String, Point> points,
            Double origin, String a, String b, String label, String kind, String direction, Object ta, Object tb,
            Row ra, Row rb, String reason) {
        Map<String, Object> value = interval(ta, tb, uncertainty(models, points.get(a), points.get(b)));
        if (reason != null) {
            value.put("duration_ms", null);
            value.put("reason", reason);
        }
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("label", label);
        bar.put("kind", kind);
        bar.put("direction", direction);
        bar.put("point_a", a);
        bar.put("point_b", b);
        bar.putAll(value);
        bar.put("start_ms", ta != null && origin != null && value.get("reason") == null
                ? (((Number) ta).doubleValue() - origin) * 1000.0 : null);
        List<Map<String, Object>> pkts = new ArrayList<>();
        for (Row r : new Row[] {ra, rb}) {
            if (r != null) {
                for (String k : List.of("first_frame", "last_frame", "response_frame")) {
                    if (Evidence.truthy(r.get(k))) {
                        pkts.add(Map.of("point", r.get("point"), "frame", r.get(k)));
                    }
                }
            }
        }
        bar.put("evidence", frameRefs(db, pkts, null));
        bar.put("note", "Request-level correlation; clocks carry uncertainty. These are separate TCP connections.");
        return bar;
    }

    static List<Map<String, Object>> handshakes(Db db, Topology topology, Map<String, ClockModel> models,
            Map<String, ? extends Map<String, Object>> stage) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Point> points = topology.pointMap();
        TreeSet<String> flows = new TreeSet<>();
        for (Map<String, Object> r : stage.values()) {
            if (r != null && r.get("flow") != null) {
                flows.add((String) r.get("flow"));
            }
        }
        for (String flow : flows) {
            List<Row> packets = db.rows(Sql.get("proxy", "handshake_packets"), flow);
            List<String> route = new ArrayList<>();
            for (String p : topology.forward) {
                boolean has = false;
                for (Row o : packets) {
                    if (p.equals(o.get("point"))) {
                        has = true;
                        break;
                    }
                }
                if (has) {
                    route.add(p);
                }
            }
            if (route.size() < 2) {
                continue;
            }
            String first = route.get(0);
            String last = route.get(route.size() - 1);
            Row syn = null;
            for (Row p : packets) {
                if (first.equals(p.get("point")) && (p.lng("flags") & 18L) == 2L) {
                    syn = p;
                    break;
                }
            }
            Row sa = null;
            if (syn != null) {
                long expAck = (syn.lng("seq") + 1L) % 4294967296L;
                for (Row p : packets) {
                    if (last.equals(p.get("point")) && (p.lng("flags") & 18L) == 18L && p.lng("ack") == expAck) {
                        sa = p;
                        break;
                    }
                }
            }
            Row ack = null;
            if (sa != null) {
                long expAck = (sa.lng("seq") + 1L) % 4294967296L;
                for (Row p : packets) {
                    if (first.equals(p.get("point")) && p.lng("flags") == 16L && p.lng("ack") == expAck
                            && p.dbl("corrected") >= sa.dbl("corrected")) {
                        ack = p;
                        break;
                    }
                }
            }
            List<Map<String, Object>> bars = new ArrayList<>();
            List<String> revRoute = Topology.reversed(route);
            Object[][] steps = {{syn, route, "SYN"}, {sa, revRoute, "SYN/ACK"}, {ack, route, "ACK"}};
            for (Object[] step : steps) {
                Row seed = (Row) step[0];
                @SuppressWarnings("unchecked")
                List<String> path = (List<String>) step[1];
                String label = (String) step[2];
                if ("SYN/ACK".equals(label)) {
                    Row peerSyn = null;
                    if (syn != null) {
                        for (Row p : packets) {
                            if (last.equals(p.get("point")) && Objects.equals(p.get("packet_key"), syn.get("packet_key"))) {
                                peerSyn = p;
                                break;
                            }
                        }
                    }
                    bars.add(localHandling(db, models, points, syn, peerSyn, sa, last, "TCP peer SYN handling"));
                } else if ("ACK".equals(label)) {
                    Row reqSa = null;
                    if (sa != null) {
                        for (Row p : packets) {
                            if (first.equals(p.get("point")) && Objects.equals(p.get("packet_key"), sa.get("packet_key"))) {
                                reqSa = p;
                                break;
                            }
                        }
                    }
                    bars.add(localHandling(db, models, points, syn, reqSa, ack, first, "Requester handshake ACK handling"));
                }
                Map<String, Row> matched = new LinkedHashMap<>();
                if (seed != null) {
                    for (Row p : packets) {
                        if (Objects.equals(p.get("packet_key"), seed.get("packet_key"))) {
                            matched.put(p.str("point"), p);
                        }
                    }
                }
                for (int i = 0; i + 1 < path.size(); i++) {
                    String a = path.get(i);
                    String b = path.get(i + 1);
                    Row x = matched.get(a);
                    Row y = matched.get(b);
                    Map<String, Object> value = interval(
                            x != null ? x.get("corrected") : null,
                            y != null ? y.get("corrected") : null,
                            uncertainty(models, points.get(a), points.get(b)));
                    List<String> clauses = new ArrayList<>();
                    List<Object> params = new ArrayList<>();
                    for (Row p : new Row[] {x, y}) {
                        if (p != null) {
                            clauses.add("(o.point=? AND o.frame=?)");
                            params.add(p.get("point"));
                            params.add(p.get("frame"));
                        }
                    }
                    Map<String, Object> bar = new LinkedHashMap<>();
                    bar.put("label", label + ": " + points.get(a).label() + " \u2192 " + points.get(b).label());
                    bar.put("kind", "link");
                    bar.put("direction", !"SYN/ACK".equals(label) ? "forward" : "reverse");
                    bar.put("point_a", a);
                    bar.put("point_b", b);
                    bar.putAll(value);
                    bar.put("start_ms", x != null && syn != null && value.get("reason") == null
                            ? (x.dbl("corrected") - syn.dbl("corrected")) * 1000.0 : null);
                    bar.put("evidence", clauses.isEmpty() ? new ArrayList<>()
                            : Evidence.evidence(db, String.join(" OR ", clauses), params, 5, false));
                    bar.put("note", "Handshake belongs to this TCP leg only; no cross-proxy packet identity.");
                    bars.add(bar);
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "handshake:" + flow);
            item.put("title", "TCP leg handshake: " + points.get(first).label() + " \u2192 " + points.get(last).label());
            item.put("bars", bars);
            result.add(item);
        }
        return result;
    }

    private static Map<String, Object> localHandling(Db db, Map<String, ClockModel> models, Map<String, Point> points,
            Row syn, Row x, Row y, String point, String label) {
        Map<String, Object> value = interval(
                x != null ? x.get("corrected") : null,
                y != null ? y.get("corrected") : null,
                uncertainty(models, points.get(point), points.get(point)));
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (Row p : new Row[] {x, y}) {
            if (p != null) {
                clauses.add("(o.point=? AND o.frame=?)");
                params.add(p.get("point"));
                params.add(p.get("frame"));
            }
        }
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("label", label);
        bar.put("kind", "handshake_processing");
        bar.put("direction", "reverse");
        bar.put("point_a", point);
        bar.put("point_b", point);
        bar.putAll(value);
        bar.put("start_ms", x != null && syn != null && value.get("reason") == null
                ? (x.dbl("corrected") - syn.dbl("corrected")) * 1000.0 : null);
        bar.put("evidence", clauses.isEmpty() ? new ArrayList<>()
                : Evidence.evidence(db, String.join(" OR ", clauses), params, 5, false));
        bar.put("note", "Local handling on this TCP leg; no cross-proxy handshake match.");
        return bar;
    }
}

