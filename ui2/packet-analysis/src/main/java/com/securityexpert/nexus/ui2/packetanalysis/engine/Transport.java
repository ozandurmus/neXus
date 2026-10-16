package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Port of the reference {@code transport.py}, {@code retransmissions.py}, and {@code waterfall.py}
 * ({@code service_times}, {@code request_size}, {@code completion}).
 */
public final class Transport {
    static final long MOD = 4294967296L;
    static final int WATERFALL_LIMIT = 20000;
    public static final String TOOLTIP =
            "Per-flow/direction hypothesis from negotiated receive window, demand/flight, ACK timing, "
                    + "matched path timing, classified loss and visible HTTP service time. Counters alone cannot prove a cause.";

    private static final Pattern HTTP_REQUEST = Pattern.compile("^[A-Z]+ [^\\r\\n]+ HTTP/1\\.[01]\\r\\n");
    private static final Pattern HTTP_RESPONSE = Pattern.compile("^HTTP/1\\.[01] ([2-5][0-9]{2})[ \\r]");

    static final Map<String, String> RETRY_REASONS;
    static {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("unknown", "Recovery mechanism unknown: missing trustworthy timer/ACK/SACK evidence");
        r.put("fast", "tshark fast retransmission at this independently decoded point");
        r.put("sack", "tshark fast retransmission after duplicate ACKs with a SACK block beyond the retransmitted hole");
        r.put("spurious", "tshark spurious flag corroborated by an earlier cumulative ACK covering these bytes");
        r.put("rto", "RTO-compatible retry: tshark retry interval exceeds a conservative 1 s / prior RTT+4 sigma timer bound, without observed duplicate-ACK/SACK trigger; actual socket timer is not observable");
        RETRY_REASONS = Collections.unmodifiableMap(r);
    }

    private Transport() {
    }

    // ------------------------------------------------------------------ transport.classify

    public static Map<String, Object> classify(Map<String, Object> m) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classification", "unknown");
        result.put("reason", "No independently corroborated limitation in this window");
        result.put("tooltip", TOOLTIP);

        String[][] guards = {
            {"indexed", "Reattach to index transport analysis fields"},
            {"trusted", "TCP state was not decoded independently at this capture point"},
            {"handshake", "Handshake not captured completely; negotiated receive-window scale unknown"},
            {"sender_captured", "No declared sender-side capture for this TCP leg"},
            {"receiver_captured", "No declared receiver-side capture to separate ACK delay from path time"},
        };
        for (String[] g : guards) {
            if (!Evidence.truthy(m.get(g[0]))) {
                result.put("reason", g[1]);
                return result;
            }
        }
        if (lng(m, "samples") < 6L) {
            result.put("reason", "Too few data observations for a limitation profile");
            return result;
        }
        double stall = dbl(m, "stall_ms");
        boolean receiver = Evidence.truthy(m.getOrDefault("receiver_window_evidence", false))
                && ((lng(m, "zero_windows") >= 2L
                        && dbl(m, "zero_span_ms") >= stall
                        && (lng(m, "blocked_samples") >= 2L || lng(m, "probes") >= 1L)
                        && (lng(m, "window_shrinks") >= 1L || lng(m, "probes") >= 1L))
                        || (lng(m, "window_full") >= 3L && lng(m, "window_shrinks") >= 2L && lng(m, "blocked_samples") >= 3L));
        boolean openWindow = lng(m, "open_samples") >= 3L && !receiver;
        boolean promptAck = lng(m, "ack_samples") >= 3L && m.get("ack_delay_p95_ms") != null && dbl(m, "ack_delay_p95_ms") < stall / 4.0;
        boolean network = openWindow && promptAck
                && ((lng(m, "loss_count") >= 3L && dbl(m, "loss_stall_ms") >= stall)
                        || (Evidence.truthy(m.get("rtt_degraded")) && Evidence.truthy(m.get("path_degraded"))));
        boolean healthyPath = Evidence.truthy(m.getOrDefault("path_complete", false))
                && lng(m, "path_samples") >= 3L
                && m.get("path_p95_ms") != null
                && dbl(m, "path_p95_ms") < stall / 4.0
                && lng(m, "loss_count") == 0L;
        boolean application = openWindow && promptAck && healthyPath
                && ((lng(m, "service_samples") >= 1L && m.get("service_ms") != null && dbl(m, "service_ms") >= stall)
                        || lng(m, "idle_gaps") >= 3L);
        int active = (receiver ? 1 : 0) + (network ? 1 : 0) + (application ? 1 : 0);
        if (active > 1 || (receiver && lng(m, "loss_count") >= 3L)) {
            result.put("reason", "Competing receiver and path limitations; a single dominant cause is unknown");
            return result;
        }
        if (receiver) {
            result.put("classification", "receiver-limited");
            result.put("reason", "Sustained closed/shrinking advertised window with active demand (flight/probes or repeated window-full observations)");
            return result;
        }
        if (network) {
            result.put("classification", "network-limited (loss/RTT)");
            result.put("reason", "Receiver has window headroom and prompt local ACKs; repeated supported loss/stalls or corroborated RTT and matched-path delay increase");
            result.put("hop", m.get("loss_hop"));
            return result;
        }
        if (application) {
            result.put("classification", "sender/application-limited");
            result.put("reason", "Repeated visible server service waits or sender-side idle gaps despite window headroom, prompt ACKs and a healthy monitored path; pacing/physical bandwidth cause is not measured");
            return result;
        }
        if (!Evidence.truthy(m.getOrDefault("path_complete", false))) {
            result.put("reason", "Incomplete clock-corrected/matchable path coverage; sender/application cause unknown");
        }
        return result;
    }

    private static long lng(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double dbl(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    // ------------------------------------------------------------------ transport.prepare + retransmissions.prepare

    @SuppressWarnings("unchecked")
    public static void prepare(Db db, Topology topology, Map<String, Object> report) {
        List<Object> generation = Arrays.asList(report.get("generated_at"), 5L);
        Object existing = settingGet(db, "transport_generation");
        if (Objects.equals(existing, generation)) {
            return;
        }
        db.exec(Sql.get("transport", "transport_points"));
        List<Object[]> pts = new ArrayList<>();
        for (Point p : topology.points) {
            pts.add(new Object[] {p.id(), p.kind(), p.label()});
        }
        db.execMany(Sql.get("transport", "transport_points_insert"), pts);
        db.exec(Sql.get("transport", "transport_tcp"));
        db.exec(Sql.get("transport", "transport_syn"));
        db.exec(Sql.get("transport", "transport_handshake"));
        db.exec(Sql.get("transport", "transport_acks"));
        db.exec(Sql.get("transport", "transport_ack_first"));
        db.exec(Sql.get("transport", "transport_data"));
        db.exec(Sql.get("transport", "transport_samples"));
        db.exec(Sql.get("transport", "transport_window_trace"));
        db.exec(Sql.get("transport", "transport_point_facts"), topology.stallMs);
        db.exec(Sql.get("transport", "transport_edges"));
        List<Object[]> edges = new ArrayList<>();
        List<Map<String, Object>> segments = (List<Map<String, Object>>) report.getOrDefault("segments", List.of());
        List<String> reversePath = topology.reverse.isEmpty() ? Topology.reversed(topology.forward) : topology.reverse;
        for (Object[] dp : new Object[][] {{"forward", topology.forward}, {"reverse", reversePath}}) {
            String direction = (String) dp[0];
            List<String> path = (List<String>) dp[1];
            for (int i = 0; i + 1 < path.size(); i++) {
                String a = path.get(i);
                String b = path.get(i + 1);
                String hop = direction + ":" + a + ":" + b;
                boolean active = false;
                for (Map<String, Object> s : segments) {
                    if (hop.equals(s.get("id")) && s.get("loss_percent") != null) {
                        active = true;
                        break;
                    }
                }
                if (active) {
                    edges.add(new Object[] {direction, a, b, hop});
                }
            }
        }
        if (!edges.isEmpty()) {
            db.execMany(Sql.get("transport", "transport_edges_insert"), edges);
        }
        db.exec(Sql.get("transport", "transport_transit"));
        db.exec(Sql.get("transport", "transport_path_facts"));
        db.exec(Sql.get("transport", "transport_rtt_facts"));
        prepareRetries(db);
        settingSet(db, "transport_generation", generation);
    }

    public static void prepareRetries(Db db) {
        db.exec(Sql.get("transport", "transport_retry_signals"));
        db.exec(Sql.fmt("transport", "transport_retries", "RETRY_KIND", Sql.get("transport", "RETRY_KIND")));
        db.exec(Sql.get("transport", "transport_order_neighbors"));
        db.exec(Sql.get("transport", "transport_reordering"));
    }

    // ------------------------------------------------------------------ retransmissions.report

    public static Map<String, Object> retryReport(Db db, Topology topology, String flow) {
        List<Row> points = db.rows(Sql.get("transport", "retry_points"), flow, flow);
        Map<List<Object>, Long> ackCounts = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("transport", "duplicate_ack_counts"))) {
            ackCounts.put(Arrays.asList(r.get("flow"), r.get("point"), r.get("direction")), r.lng("n"));
        }
        for (Row p : points) {
            p.put("duplicate_acks", ackCounts.getOrDefault(Arrays.asList(p.get("flow"), p.get("point"), p.get("direction")), 0L));
            if (!p.bool("usable")) {
                for (String key : List.of("duplicate_acks", "rto", "fast", "spurious", "sack", "unknown")) {
                    p.put(key, null);
                }
            }
            p.put("tooltip", "Mutually exclusive recovery counts at one capture point. RTO is timer-compatible, not a measured kernel timer. Retransmission here can be a sender symptom of downstream loss.");
            p.put("reason", p.bool("usable") ? null : "Missing independently decoded TCP state/index; counts unknown");
        }
        List<Row> edges = db.rows(Sql.get("transport", "retry_edges"));
        List<Map<String, Object>> segments = new ArrayList<>();
        for (Row p : points) {
            for (Row edge : edges) {
                if (Objects.equals(p.get("point"), edge.get("a")) && Objects.equals(p.get("direction"), edge.get("direction"))) {
                    Map<String, Object> seg = new LinkedHashMap<>(p);
                    seg.put("hop", edge.get("hop"));
                    segments.add(seg);
                }
            }
        }
        List<Row> events = db.rows(Sql.get("transport", "retry_events"), flow, flow);
        Set<List<Object>> keys = new LinkedHashSet<>();
        for (Row e : events) {
            for (String field : List.of("frame", "rto_frame", "ack_trigger_frame", "delivered_frame")) {
                if (e.get(field) != null) {
                    keys.add(Arrays.asList(e.get("point"), e.get(field)));
                }
            }
        }
        Map<List<Object>, Map<String, Object>> lookup = Proxies.frameLookup(db, keys);
        for (Row event : events) {
            event.put("reason", RETRY_REASONS.get(event.str("retry_kind")));
            Set<List<Object>> evKeys = new LinkedHashSet<>();
            for (String field : List.of("frame", "rto_frame", "ack_trigger_frame", "delivered_frame")) {
                if (event.get(field) != null) {
                    evKeys.add(Arrays.asList(event.get("point"), event.get(field)));
                }
            }
            List<List<Object>> sortedKeys = new ArrayList<>(evKeys);
            sortedKeys.sort((x, y) -> {
                int c = ((String) x.get(0)).compareTo((String) y.get(0));
                return c != 0 ? c : Long.compare(((Number) x.get(1)).longValue(), ((Number) y.get(1)).longValue());
            });
            List<Map<String, Object>> evRefs = new ArrayList<>();
            for (List<Object> k : sortedKeys) {
                if (lookup.containsKey(k)) {
                    evRefs.add(lookup.get(k));
                }
            }
            event.put("evidence", evRefs);
        }
        List<Row> reorder = db.rows(Sql.get("transport", "retry_reordering"), flow, flow);
        Map<String, Point> pmap = topology.pointMap();
        for (Row r : reorder) {
            Point a = pmap.get(r.str("point"));
            Point b = pmap.get(r.str("downstream"));
            String device = a.device().equals(b.device()) ? a.device() : null;
            r.put("device", device);
            r.put("location", device != null ? device : "link between " + a.label() + " and " + b.label());
            r.put("confidence", "supported");
            r.put("tooltip", r.str("reason") + "; first observed order reversal, not proof of device policy or an unseen queue");
            r.put("evidence", Evidence.evidence(db,
                    "o.flow=? AND ((o.point=? AND o.frame IN (?,?)) OR (o.point=? AND o.frame IN (?,?)))",
                    Arrays.asList(r.get("flow"), r.get("point"), r.get("frame"), r.get("neighbor_frame"),
                            r.get("downstream"), r.get("downstream_frame"), r.get("downstream_neighbor_frame")),
                    4));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", points);
        out.put("segments", segments);
        out.put("events", events);
        out.put("reordering", reorder);
        out.put("note", "Counts describe observations; repeated observation at several points is not several sender retries. Unsupported timer mechanisms remain unknown.");
        return out;
    }

    // ------------------------------------------------------------------ transport.profiles + export_profiles

    @SuppressWarnings("unchecked")
    public static Map<String, Object> profiles(Db db, String flow, boolean includeService) {
        Map<String, Object> report = (Map<String, Object>) settingGet(db, "report");
        Map<String, Object> rawTopology = (Map<String, Object>) settingGet(db, "topology");
        if (report == null || rawTopology == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("items", new ArrayList<>());
            empty.put("reason", "Run analysis first");
            empty.put("tooltip", TOOLTIP);
            return empty;
        }
        Topology topology;
        try {
            topology = Topology.parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(PyJsonWriter.dumps(rawTopology, false)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return profiles(db, topology, report, flow, includeService);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> profiles(Db db, Topology topology, Map<String, Object> report, String flow, boolean includeService) {
        prepare(db, topology, report);
        String cacheGeneration = PyJsonWriter.dumps(Arrays.asList(report.get("generated_at"), 7L), false);
        try {
            db.exec(Sql.get("transport", "profile_cache"));
        } catch (RuntimeException ignored) {
            // read-only connection when table was not pre-created
        }
        if (flow != null && includeService) {
            try {
                Object[] cached = db.one(Sql.get("transport", "profile_cache_get"), flow, cacheGeneration);
                if (cached != null && cached[0] != null) {
                    return (Map<String, Object>) Evidence.loads((String) cached[0]);
                }
            } catch (RuntimeException ignored) {
                // read-only connection without profile_cache table
            }
        }
        List<Row> data = db.rows(Sql.get("transport", "point_data"), flow, flow);
        Map<List<String>, List<Row>> groups = new LinkedHashMap<>();
        for (Row item : data) {
            groups.computeIfAbsent(List.of(item.str("flow"), item.str("direction")), k -> new ArrayList<>()).add(item);
        }
        Map<String, Point> pointmap = topology.pointMap();
        Map<List<String>, Row> lossmap = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("transport", "losses"))) {
            lossmap.put(List.of(r.str("flow"), r.str("direction")), r);
        }
        Map<List<String>, Set<String>> observedEdges = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("transport", "observed_edges"))) {
            observedEdges.computeIfAbsent(List.of(r.str("flow"), r.str("direction")), k -> new LinkedHashSet<>()).add(r.str("hop"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<List<String>, List<Row>> entry : groups.entrySet()) {
            String fid = entry.getKey().get(0);
            String direction = entry.getKey().get(1);
            List<Row> pts = entry.getValue();
            String senderKind = "forward".equals(direction) ? "Client" : "Server";
            String receiverKind = "forward".equals(direction) ? "Server" : "Client";
            Row sender = null;
            Row receiver = null;
            for (Row r : pts) {
                if (sender == null && senderKind.equals(r.get("kind"))) {
                    sender = r;
                }
                if (receiver == null && receiverKind.equals(r.get("kind"))) {
                    receiver = r;
                }
            }
            if (sender == null) {
                String wantSide = "forward".equals(direction) ? "egress" : "ingress";
                for (Row r : pts) {
                    Point pm = pointmap.get(r.str("point"));
                    if (pm != null && "full_proxy".equals(pm.translation()) && wantSide.equals(pm.side())) {
                        sender = r;
                        break;
                    }
                }
            }
            if (receiver == null) {
                String wantSide = "forward".equals(direction) ? "ingress" : "egress";
                for (Row r : pts) {
                    Point pm = pointmap.get(r.str("point"));
                    if (pm != null && "full_proxy".equals(pm.translation()) && wantSide.equals(pm.side())) {
                        receiver = r;
                        break;
                    }
                }
            }
            Map<String, Object> metrics = new LinkedHashMap<>(sender != null ? sender : pts.get(0));
            metrics.put("sender_captured", sender != null);
            metrics.put("receiver_captured", receiver != null);
            List<String> path = "forward".equals(direction) ? topology.forward
                    : (topology.reverse.isEmpty() ? Topology.reversed(topology.forward) : topology.reverse);
            Set<String> expectedEdges = new LinkedHashSet<>();
            if (sender != null && receiver != null && path.contains(sender.str("point")) && path.contains(receiver.str("point"))) {
                int start = path.indexOf(sender.str("point"));
                int stop = path.indexOf(receiver.str("point"));
                for (int i = start; i < stop; i++) {
                    expectedEdges.add(direction + ":" + path.get(i) + ":" + path.get(i + 1));
                }
            }
            Set<String> obsSet = observedEdges.getOrDefault(List.of(fid, direction), Set.of());
            metrics.put("path_complete", !expectedEdges.isEmpty() && obsSet.containsAll(expectedEdges));
            metrics.put("receiver_window_evidence",
                    receiver != null && (receiver.lng("zero_windows") >= 2L || receiver.lng("window_full") >= 3L));
            Object bw = metrics.remove("blocked_windows");
            long blockedWin = bw instanceof Number n ? n.longValue() : 0L;
            metrics.put("blocked_samples", lng(metrics, "blocked_samples") + blockedWin);
            metrics.put("ack_samples", receiver != null ? receiver.get("ack_samples") : 0L);
            metrics.put("ack_delay_p95_ms", receiver != null ? receiver.get("ack_delay_p95_ms") : null);
            metrics.put("loss_count", 0L);
            metrics.put("loss_stall_ms", 0L);
            metrics.put("loss_hop", null);
            metrics.put("service_ms", null);
            metrics.put("service_samples", 0L);
            metrics.put("stall_ms", topology.stallMsJson);
            Row loss = lossmap.get(List.of(fid, direction));
            if (loss != null) {
                metrics.putAll(loss);
            }
            if (!Evidence.truthy(metrics.get("loss_stall_ms"))) {
                metrics.put("loss_stall_ms", 0L);
            }
            if (!Evidence.truthy(metrics.get("path_samples"))) {
                metrics.put("path_samples", 0L);
            }
            metrics.put("path_degraded", Evidence.truthy(metrics.get("path_degraded")));
            metrics.put("rtt_degraded", Evidence.truthy(metrics.get("rtt_degraded")));
            List<Map<String, Object>> serviceRefs = new ArrayList<>();
            if (includeService && "reverse".equals(direction) && flow != null
                    && Evidence.truthy(metrics.get("indexed"))
                    && Evidence.truthy(metrics.get("trusted"))
                    && Evidence.truthy(metrics.get("handshake"))
                    && Evidence.truthy(metrics.get("sender_captured"))
                    && Evidence.truthy(metrics.get("receiver_captured"))
                    && lng(metrics, "samples") >= 6L) {
                Map<String, Object> service = serviceTimes(db, topology, report, fid);
                List<Map<String, Object>> valid = (List<Map<String, Object>>) service.get("items");
                metrics.put("service_samples", (long) valid.size());
                if (!valid.isEmpty()) {
                    Map<String, Object> slowest = valid.get(0);
                    for (Map<String, Object> v : valid) {
                        if (dbl(v, "ms") > dbl(slowest, "ms")) {
                            slowest = v;
                        }
                    }
                    metrics.put("service_ms", slowest.get("ms"));
                    serviceRefs = (List<Map<String, Object>>) slowest.get("evidence");
                }
                metrics.put("service_reason", service.get("reason"));
            }
            Map<String, Object> decision = classify(metrics);
            List<Map<String, Object>> refs = new ArrayList<>(Evidence.evidence(db,
                    "o.flow=? AND o.proto='TCP' AND (o.point=? OR o.point=?)",
                    Arrays.asList(fid, metrics.get("point"), receiver != null ? receiver.get("point") : null),
                    16));
            List<Row> signal = "unknown".equals(decision.get("classification"))
                    ? List.of()
                    : db.rows(Sql.get("transport", "signal_samples"), fid, direction);
            if (!"unknown".equals(decision.get("classification"))) {
                TreeSet<String> sigPoints = new TreeSet<>();
                for (Row s : signal) {
                    sigPoints.add(s.str("point"));
                }
                for (String p : sigPoints) {
                    TreeSet<Long> frames = new TreeSet<>();
                    for (Row s : signal) {
                        if (p.equals(s.get("point"))) {
                            for (String k : List.of("frame", "ack_frame", "window_frame")) {
                                if (s.get(k) != null) {
                                    frames.add(s.lng(k));
                                }
                            }
                        }
                    }
                    refs.addAll(Evidence.evidence(db,
                            "o.flow=? AND o.point=? AND o.frame IN(SELECT unnest(?::BIGINT[]))",
                            Arrays.asList(fid, p, new ArrayList<>(frames)),
                            24));
                }
            }
            refs.addAll(serviceRefs);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("flow", fid);
            item.put("direction", direction);
            item.put("point", metrics.get("point"));
            item.putAll(decision);
            item.put("metrics", metrics);
            item.put("evidence", refs);
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("retransmissions", retryReport(db, topology, flow));
        result.put("point_metrics", data);
        result.put("reason", null);
        result.put("tooltip", TOOLTIP);
        result.put("note", "Same-capture ACK/service timing; hop timing retains clock uncertainty. Sender pacing does not measure physical capacity. Missing/ambiguous evidence is unknown.");
        if (flow != null && includeService) {
            try {
                db.exec(Sql.get("transport", "profile_cache_prune"), cacheGeneration);
                db.exec(Sql.get("transport", "profile_cache_put"), flow, cacheGeneration, PyJsonWriter.dumps(result, false));
            } catch (RuntimeException ignored) {
                // read-only connection
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> exportProfiles(Db db, Topology topology, Map<String, Object> report, int limit) {
        List<Object> generation = report != null ? Arrays.asList(report.get("generated_at"), 7L, (long) limit) : null;
        Map<String, Object> cached = (Map<String, Object>) settingGet(db, "transport_export_cache");
        if (cached != null && Objects.equals(cached.get("generation"), generation)) {
            return (Map<String, Object>) cached.get("value");
        }
        Map<String, Object> result = profiles(db, topology, report, null, false);
        List<String> httpFlows = new ArrayList<>();
        for (Object[] r : db.tuples(Sql.get("transport", "http_flows"), limit)) {
            httpFlows.add((String) r[0]);
        }
        for (String fid : httpFlows) {
            Map<String, Object> enriched = profiles(db, topology, report, fid, true);
            Map<List<Object>, Map<String, Object>> index = new LinkedHashMap<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) enriched.get("items")) {
                index.put(Arrays.asList(r.get("flow"), r.get("direction")), r);
            }
            List<Map<String, Object>> updated = new ArrayList<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) result.get("items")) {
                updated.add(index.getOrDefault(Arrays.asList(r.get("flow"), r.get("direction")), r));
            }
            result.put("items", updated);
        }
        List<Map<String, Object>> allItems = (List<Map<String, Object>>) result.get("items");
        result.put("total", (long) allItems.size());
        result.put("items", new ArrayList<>(allItems.subList(0, Math.min(limit, allItems.size()))));
        List<Object> pm = (List<Object>) result.getOrDefault("point_metrics", List.of());
        result.put("point_metrics", new ArrayList<>(pm.subList(0, Math.min(limit * 4, pm.size()))));
        result.put("note", result.get("note") + " Export shows at most 200 directional profiles; inspect another flow locally for its full evidence.");
        Map<String, Object> cacheEntry = new LinkedHashMap<>();
        cacheEntry.put("generation", generation);
        cacheEntry.put("value", result);
        settingSet(db, "transport_export_cache", cacheEntry);
        return result;
    }

    // ------------------------------------------------------------------ waterfall.service_times

    @SuppressWarnings("unchecked")
    public static Map<String, Object> serviceTimes(Db db, Topology topology, Map<String, Object> report, String flow) {
        if (report == null || topology == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", new ArrayList<>());
            out.put("reason", "Run analysis first");
            return out;
        }
        String server = topology.forward.get(topology.forward.size() - 1);
        Map<String, Object> proxies = (Map<String, Object>) report.getOrDefault("proxies", Map.of());
        Map<String, Object> hostPath = (Map<String, Object>) report.getOrDefault("host_path", Map.of());
        Map<String, Object> localService = (Map<String, Object>) hostPath.getOrDefault("local_service", Map.of());
        if (Evidence.truthy(proxies.get("transactions")) || Evidence.truthy(localService.get("samples"))) {
            List<Row> pairs = db.rows(Sql.get("transport", "proxy_service_pairs"), flow, server);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Row p : pairs) {
                List<Map<String, Object>> refs = Evidence.evidence(db,
                        "o.point=? AND o.frame IN (?,?)",
                        Arrays.asList(server, p.get("last_frame"), p.get("response_frame")),
                        2);
                Map<Long, Double> stamps = new LinkedHashMap<>();
                for (Row r : db.rows(Sql.get("transport", "obs_stamps"), server, p.get("last_frame"), p.get("response_frame"))) {
                    stamps.put(r.lng("frame"), r.dbl("ts"));
                }
                Long lf = p.lng("last_frame");
                Long rf = p.lng("response_frame");
                if (stamps.containsKey(lf) && stamps.containsKey(rf) && stamps.get(rf) >= stamps.get(lf)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("ms", (stamps.get(rf) - stamps.get(lf)) * 1000.0);
                    item.put("evidence", refs);
                    result.add(item);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", result);
            out.put("reason", null);
            return out;
        }
        List<Row> packets = db.rows(Sql.get("transport", "server_packets"), server, flow, WATERFALL_LIMIT + 1);
        if (packets.size() > WATERFALL_LIMIT) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", new ArrayList<>());
            out.put("reason", "Service boundary observation budget exceeded");
            return out;
        }
        List<Row> reqCandidates = new ArrayList<>();
        for (Row p : packets) {
            if ("forward".equals(p.get("direction")) && p.lng("length") > 0L && p.bool("eligible")
                    && HTTP_REQUEST.matcher(latin1FromHex(p.str("prefix"))).find()) {
                reqCandidates.add(p);
            }
        }
        Map<Long, Row> bySeq = new LinkedHashMap<>();
        for (int i = reqCandidates.size() - 1; i >= 0; i--) {
            Row p = reqCandidates.get(i);
            bySeq.put(p.lng("seq"), p);
        }
        List<Row> requests = new ArrayList<>(bySeq.values());
        requests.sort(Comparator.comparingDouble(p -> p.dbl("ts")));
        List<Map<String, Object>> result = new ArrayList<>();
        int cap = Math.min(100, requests.size());
        for (int i = 0; i < cap; i++) {
            Row req = requests.get(i);
            Row response = null;
            for (Row p : packets) {
                if ("reverse".equals(p.get("direction")) && p.lng("length") > 0L && p.bool("eligible")
                        && p.dbl("ts") >= req.dbl("ts")
                        && HTTP_RESPONSE.matcher(latin1FromHex(p.str("prefix"))).find()) {
                    response = p;
                    break;
                }
            }
            if (response == null) {
                continue;
            }
            if (i + 1 < requests.size() && requests.get(i + 1).dbl("ts") < response.dbl("ts")) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("items", new ArrayList<>());
                out.put("reason", "Pipelined request/response ownership unknown; service association suppressed");
                return out;
            }
            List<Row> local = new ArrayList<>();
            for (Row p : packets) {
                if (req.dbl("ts") <= p.dbl("ts") && p.dbl("ts") <= response.dbl("ts")) {
                    local.add(p);
                }
            }
            Object[] sr = requestSize(req, local);
            Integer size = (Integer) sr[0];
            String reason = (String) sr[1];
            Row last = size != null && reason == null ? completion(local, req, size, response.dbl("ts")) : null;
            if (last == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ms", (response.dbl("ts") - last.dbl("ts")) * 1000.0);
            item.put("evidence", Evidence.evidence(db,
                    "o.point=? AND o.frame IN (?,?)",
                    Arrays.asList(server, last.get("frame"), response.get("frame")),
                    2));
            result.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", result);
        out.put("reason", null);
        out.put("note", "At most 100 visible HTTP requests; incomplete/chunked/pipelined boundaries stay unknown.");
        return out;
    }

    static Object[] requestSize(Row packet, List<Row> packets) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        List<Row> ordered = new ArrayList<>(packets);
        long baseSeq = packet.lng("seq");
        ordered.sort(Comparator.comparingLong(p -> Math.floorMod(p.lng("seq") - baseSeq, MOD)));
        for (Row p : ordered) {
            long offset = Math.floorMod(p.lng("seq") - baseSeq, MOD);
            byte[] current = data.toByteArray();
            if (offset > current.length || offset >= 8192L) {
                break;
            }
            byte[] payload = bytesFromHex(p.str("prefix"));
            int off = (int) offset;
            int overlap = Math.min(payload.length, current.length - off);
            for (int i = 0; i < overlap; i++) {
                if (current[off + i] != payload[i]) {
                    return new Object[] {null, "Conflicting captured HTTP header bytes"};
                }
            }
            int limit = Math.min(payload.length, 8192 - current.length + overlap);
            if (limit > overlap) {
                data.write(payload, overlap, limit - overlap);
            }
            if (indexOfCrLfCrLf(data.toByteArray()) >= 0) {
                break;
            }
        }
        byte[] all = data.toByteArray();
        int sep = indexOfCrLfCrLf(all);
        if (sep < 0) {
            return new Object[] {null, "HTTP headers incomplete in captured prefixes (8 KiB header limit)"};
        }
        String headerStr = new String(all, 0, sep, StandardCharsets.ISO_8859_1);
        String[] lines = headerStr.split("\\r\\n", -1);
        List<String[]> headers = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 0) {
                return new Object[] {null, "Malformed HTTP request headers"};
            }
            String k = lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String v = lines[i].substring(colon + 1).strip();
            if ("transfer-encoding".equals(k)) {
                return new Object[] {null, "HTTP transfer-encoding request boundary is unknown; chunk decoding is not supported"};
            }
            headers.add(new String[] {k, v});
        }
        Set<String> lengths = new LinkedHashSet<>();
        for (String[] kv : headers) {
            if ("content-length".equals(kv[0])) {
                lengths.add(kv[1]);
            }
        }
        int body = 0;
        if (!lengths.isEmpty()) {
            String first = lengths.iterator().next();
            if (lengths.size() != 1 || first.isEmpty() || !first.chars().allMatch(Character::isDigit)) {
                return new Object[] {null, "Ambiguous HTTP Content-Length"};
            }
            long parsed = Long.parseLong(first);
            if (parsed > 8L * 1024L * 1024L) {
                return new Object[] {null, "HTTP request exceeds the 8 MiB request tracking limit"};
            }
            body = (int) parsed;
        }
        return new Object[] {sep + 4 + body, null};
    }

    static Row completion(List<Row> packets, Row seed, int size, double before) {
        List<Row> ordered = new ArrayList<>(packets);
        ordered.sort((x, y) -> {
            int c = Double.compare(x.dbl("ts"), y.dbl("ts"));
            return c != 0 ? c : Long.compare(x.lng("frame"), y.lng("frame"));
        });
        List<long[]> covered = new ArrayList<>();
        long seedSeq = seed.lng("seq");
        for (Row p : ordered) {
            if (p.dbl("ts") < seed.dbl("ts") || p.dbl("ts") > before || !p.bool("eligible")) {
                continue;
            }
            long lo = Math.floorMod(p.lng("seq") - seedSeq, MOD);
            long hi = Math.min((long) size, lo + p.lng("length"));
            if (lo >= size || hi <= lo) {
                continue;
            }
            covered.add(new long[] {lo, hi});
            covered.sort((a, b) -> {
                int c = Long.compare(a[0], b[0]);
                return c != 0 ? c : Long.compare(a[1], b[1]);
            });
            long end = 0L;
            for (long[] ab : covered) {
                if (ab[0] > end) {
                    break;
                }
                end = Math.max(end, ab[1]);
            }
            if (end >= size) {
                return p;
            }
        }
        return null;
    }

    private static byte[] bytesFromHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        return HexFormat.of().parseHex(hex);
    }

    private static String latin1FromHex(String hex) {
        return new String(bytesFromHex(hex), StandardCharsets.ISO_8859_1);
    }

    private static int indexOfCrLfCrLf(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static Object settingGet(Db db, String key) {
        try {
            Object[] r = db.one(Sql.get("transport", "settings_get"), key);
            return r == null || r[0] == null ? null : Evidence.loads((String) r[0]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void settingSet(Db db, String key, Object value) {
        try {
            db.exec(Sql.get("transport", "settings_set"), key, PyJsonWriter.dumps(value, false));
        } catch (RuntimeException e) {
            // settings table may not exist in in-memory unit fixtures
        }
    }
}
