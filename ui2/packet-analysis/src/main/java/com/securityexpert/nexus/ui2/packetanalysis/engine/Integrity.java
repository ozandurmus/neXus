package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyMath;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reference {@code integrity} package: bounded path checks ({@code analyze_integrity}: RST/ICMP origins,
 * endpoint origins, payload changes, downstream-only packets, MTU/MSS, header field changes, duplication,
 * asymmetric routing) and {@code group_downstream_findings}. Positive field changes and absence-based
 * hypotheses stay distinct; {@code link_modified_payloads} is in {@link Matching} (it runs before matching).
 */
final class Integrity {
    static final int LIMIT = 200;
    static final String ORIGIN_TIP = "Origin is the first observed point along the configured direction, not the lowest raw timestamp. "
            + "Device injection requires a paired ingress/egress, usable clock and coverage, no upstream occurrence, "
            + "and endpoint reference packets. Endpoint-consistent TTL at the same capture point leaves capture miss unresolved. TTL/IP ID are supporting patterns, never authentication. "
            + "Unreported capture loss or an unmodelled alternate path can mimic injection; policy/IPS cause is unknown without device evidence.";

    private final Db db;
    private final Topology topology;
    private final List<Map<String, Object>> segments;
    private final Map<String, Point> points;
    private final Map<String, Row> coverage = new LinkedHashMap<>();
    private final Map<String, ClockModel> models;
    private final Double start;
    private final Double end;
    private final Row hints;
    private final Map<String, Row> repeats = new LinkedHashMap<>();
    private final Map<String, Map<String, List<Map<String, Object>>>> candidateCache = new LinkedHashMap<>();
    private final Map<List<String>, Map<String, Object>> changeCache = new LinkedHashMap<>();
    final List<Map<String, Object>> findings = new ArrayList<>();
    final List<Object> notes = new ArrayList<>();
    private final Map<String, Map<String, Object>> inventories = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    Integrity(EngineContext ctx) {
        this.db = ctx.db;
        this.topology = ctx.topology;
        this.segments = ctx.segments;
        this.points = topology.pointMap();
        for (Row c : ctx.coverage) {
            coverage.put(c.str("point"), c);
        }
        this.models = ctx.models;
        this.start = ctx.start;
        this.end = ctx.end;
        db.exec(Sql.get("integrity", "window"), start, end);
        db.exec(Sql.get("integrity", "view"));
        this.hints = db.rows(Sql.get("integrity", "hints")).get(0);
        for (Row r : db.rows(Sql.get("integrity", "repeats"))) {
            repeats.put(r.str("point"), r);
        }
        List<List<Object>> columns = new ArrayList<>();
        for (String k : List.of("id", "point_a", "point_b", "direction")) {
            List<Object> col = new ArrayList<>();
            for (Map<String, Object> s : segments) {
                col.add(s.get(k));
            }
            columns.add(col);
        }
        db.exec(Sql.get("integrity", "edges"), columns.get(0), columns.get(1), columns.get(2), columns.get(3));
        for (Object[] r : db.tuples(Sql.get("integrity", "inventories"))) {
            inventories.put((String) r[0], (Map<String, Object>) Evidence.loads((String) r[1]));
        }
    }

    /** {@code analyze_integrity}: returns (findings, notes) in ctx order. */
    static void analyze(EngineContext ctx) {
        Integrity checks = new Integrity(ctx);
        checks.origins();
        checks.endpointOrigins();
        checks.payloadChanges();
        checks.downstreamOnly();
        checks.mtu();
        checks.pathChecks();
        ctx.path.integrityFindings = checks.findings;
        ctx.path.integrityNotes = checks.notes;
        Parity.record(ctx, "integrity", Parity.pair(checks.findings, checks.notes));
    }

    // ------------------------------------------------------------------ common.py

    @SuppressWarnings("unchecked")
    static Map<String, Object> metadata(Map<String, Object> packet) {
        Object text = packet.get("path_fields");
        return (Map<String, Object>) Evidence.loads(Evidence.truthy(text) ? (String) text : "{}");
    }

    static boolean isV6(Map<String, Object> packet) {
        return ((String) packet.get("src")).contains(":");
    }

    /** {@code endpoint_pattern(samples, packet)}. */
    static Map<String, Object> endpointPattern(List<? extends Map<String, Object>> samples, Map<String, Object> packet) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (samples.size() < 3) {
            out.put("status", "unknown");
            out.put("reason", "Fewer than three endpoint reference packets");
            return out;
        }
        TreeSet<Long> ttlSet = new TreeSet<>();
        for (Map<String, Object> p : samples) {
            ttlSet.add((Long) p.get("ttl"));
        }
        List<Long> ttls = new ArrayList<>(ttlSet);
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> p : samples) {
            ids.add(p.get("ipid"));
        }
        boolean v6 = isV6(packet);
        String pattern = v6 ? "unknown" : new LinkedHashSet<>(ids).size() == 1 ? "constant" : "varying";
        out.put("status", "observed");
        out.put("ttl_values", ttls);
        out.put("candidate_ttl", packet.get("ttl"));
        out.put("ttl_matches", ttls.contains(packet.get("ttl")));
        out.put("ip_id_pattern", pattern);
        out.put("candidate_ip_id", v6 ? null : packet.get("ipid"));
        if (v6) {
            out.put("candidate_id_steps", null);
            out.put("endpoint_id_steps", null);
        } else {
            long candidate = (Long) packet.get("ipid");
            List<Object> steps = new ArrayList<>();
            for (Map<String, Object> p : samples.subList(Math.max(0, samples.size() - 3), samples.size())) {
                steps.add(PyMath.mod(candidate - (Long) p.get("ipid"), 65536L));
            }
            out.put("candidate_id_steps", steps);
            List<Object> endpointSteps = new ArrayList<>();
            for (int i = 0; i + 1 < ids.size(); i++) {
                endpointSteps.add(PyMath.mod((Long) ids.get(i + 1) - (Long) ids.get(i), 65536L));
            }
            out.put("endpoint_id_steps", endpointSteps);
        }
        out.put("ip_id_note", "Constant, randomized and per-destination IDs cannot authenticate origin");
        return out;
    }

    /** {@code duplicate_pairs(db, point, duplicate_us, mode)}: adjacent repeats only. */
    static List<Row> duplicatePairs(Db db, String point, double duplicateUs, String mode) {
        String partition;
        String eligible;
        String condition;
        if (mode.equals("loop")) {
            partition = "canon,signature,payload_hash";
            eligible = "eligible AND proto='TCP' AND length>0";
            condition = "previous_ttl>ttl AND ts-previous_ts BETWEEN 0 AND 1";
        } else if (mode.equals("identical")) {
            partition = "frame_hash";
            eligible = "eligible AND caplen=wirelen AND frame_hash IS NOT NULL";
            condition = "ts-previous_ts>? AND ts-previous_ts<0.001";
        } else {
            throw new IllegalArgumentException("Unknown duplication check");
        }
        String sql = Sql.fmt("integrity", "duplicate_pairs", "eligible", eligible, "partition", partition, "condition", condition);
        return mode.equals("identical") ? db.rows(sql, point, duplicateUs / 1e6) : db.rows(sql, point);
    }

    private String location(Map<String, Object> segment) {
        Object device = segment.get("device");
        if (Evidence.truthy(device)) {
            return (String) device;
        }
        return "link between " + points.get(segment.get("point_a")).label() + " and " + points.get(segment.get("point_b")).label();
    }

    private boolean hasEndpoint(List<String> path, String direction) {
        return !path.isEmpty() && points.get(path.get(0)).kind().equals(direction.equals("forward") ? "Client" : "Server");
    }

    private String reason(Map<String, Object> segment, Object ts) {
        if (ts == null) {
            return "Clock correction unavailable";
        }
        double t = ((Number) ts).doubleValue();
        if (Evidence.truthy(segment.get("reason"))) {
            return (String) segment.get("reason");
        }
        for (Object id : List.of(segment.get("point_a"), segment.get("point_b"))) {
            Point point = points.get(id);
            ClockModel model = models.get(point.captureId());
            if (model.uncertainty == null) {
                return "Clock uncertainty unverified";
            }
            if (point.vendor().equals("checkpoint") && !point.inspectionComplete()) {
                return "Check Point inspection coverage is not attested complete";
            }
            Row cov = coverage.get(id);
            double margin = topology.matchWindowMs / 1000 + ClockModel.value(model.uncertainty);
            if (cov == null || cov.get("start") == null
                    || !(cov.dbl("start") + margin <= t && t <= cov.dbl("end") - margin)) {
                return "Coverage does not bracket the complete matching window";
            }
            Map<String, Object> inventory = inventories.get(point.captureId());
            for (String k : List.of("ifdrop", "osdrop", "truncated_tail", "damaged_tail", "timestamp_excluded_counts")) {
                if (Evidence.truthy(inventory.get(k))) {
                    return "Capture quality prevents absence-based attribution";
                }
            }
        }
        return null;
    }

    private List<Map<String, Object>> refs(List<? extends Map<String, Object>> packets) {
        List<List<Object>> selected = new ArrayList<>();
        for (Map<String, Object> p : packets) {
            List<Object> k = Arrays.asList(p.get("point"), p.get("frame"));
            if (!selected.contains(k)) {
                selected.add(k);
            }
        }
        selected = new ArrayList<>(selected.subList(0, Math.min(16, selected.size())));
        if (selected.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> parts = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (List<Object> pair : selected) {
            parts.add("(o.point=? AND o.frame=?)");
            values.addAll(pair);
        }
        List<Map<String, Object>> refs = Evidence.evidence(db, String.join(" OR ", parts), values, 16, false);
        List<List<Object>> order = selected;
        List<Map<String, Object>> sorted = new ArrayList<>(refs);
        sorted.sort((x, y) -> Integer.compare(rank(order, x), rank(order, y)));
        return sorted;
    }

    private static int rank(List<List<Object>> order, Map<String, Object> r) {
        int i = order.indexOf(Arrays.asList(r.get("point"), r.get("frame")));
        return i < 0 ? order.size() : i;
    }

    static Double correctedOrTs(Map<String, Object> packet) {
        Object c = packet.get("corrected");
        return Evidence.truthy(c) ? ((Number) c).doubleValue() : packet.get("ts") == null ? null : ((Number) packet.get("ts")).doubleValue();
    }

    private void add(Map<String, Object> segment, String kind, Map<String, Object> packet, String summary, String tooltip,
            Map<String, Object> metrics, boolean supported, List<? extends Map<String, Object>> packets, String severity,
            String cause) {
        double ts = correctedOrTs(packet);
        if (start != null && ts < start || end != null && ts > end) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", 1L);
        if (metrics != null) {
            m.putAll(metrics);
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", "integrity:" + segment.get("id") + ":" + kind + ":" + packet.get("frame") + ":" + findings.size());
        f.put("type", kind);
        f.put("hop", segment.get("id"));
        f.put("device", segment.get("device"));
        f.put("direction", segment.get("direction"));
        f.put("severity", supported ? severity : "unknown");
        f.put("confidence", supported ? "supported" : "unknown");
        f.put("time_range", new ArrayList<>(List.of(ts, ts)));
        f.put("time_labels", Headlines.timeLabels(ts, topology.reportTimezone));
        f.put("metrics", m);
        f.put("summary", summary);
        f.put("headline", summary);
        f.put("tooltip", tooltip);
        f.put("cause", cause);
        f.put("evidence", refs(packets != null ? packets : List.of(packet)));
        f.put("evidence_note", "Observed frame filters; absence is conditional on capture coverage");
        f.put("clock_caveat", "Times are clock-corrected estimates; see segment clock uncertainty");
        findings.add(f);
    }

    private void add(Map<String, Object> segment, String kind, Map<String, Object> packet, String summary, String tooltip,
            Map<String, Object> metrics, boolean supported, List<? extends Map<String, Object>> packets, String severity) {
        add(segment, kind, packet, summary, tooltip, metrics, supported, packets, severity, "unknown");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> candidates(Map<String, Object> segment, String condition) {
        if (!candidateCache.containsKey(condition)) {
            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (Row row : db.rows(Sql.fmt("integrity", "candidates", "condition", condition), (long) (LIMIT + 1))) {
                grouped.computeIfAbsent(row.str("id"), k -> new ArrayList<>()).add((Map<String, Object>) row.get("packet"));
            }
            candidateCache.put(condition, grouped);
        }
        List<Map<String, Object>> data = candidateCache.get(condition).getOrDefault((String) segment.get("id"), List.of());
        if (data.size() > LIMIT) {
            notes.add(segment.get("id") + ": candidate display bounded to " + LIMIT + "; more candidates exist");
        }
        return data.subList(0, Math.min(LIMIT, data.size()));
    }

    private List<Map<String, Object>> changedPair(Map<String, Object> segment, String condition) {
        List<String> key = List.of((String) segment.get("id"), condition);
        if (!changeCache.containsKey(key)) {
            Row pair = db.rows(Sql.fmt("integrity", "changed_pair", "condition", condition), segment.get("point_a"),
                    segment.get("direction"), segment.get("point_b"), segment.get("direction")).get(0);
            Map<String, Object> item = null;
            if (Evidence.truthy(pair.get("count"))) {
                item = new LinkedHashMap<>();
                item.put("id", segment.get("id"));
                item.put("count", pair.get("count"));
                item.put("before_packet", db.rows(Sql.get("integrity", "packet_at"), segment.get("point_a"), pair.get("frame_a")).get(0));
                item.put("after_packet", db.rows(Sql.get("integrity", "packet_at"), segment.get("point_b"), pair.get("frame_b")).get(0));
            }
            changeCache.put(key, item);
        }
        Map<String, Object> item = changeCache.get(key);
        return item != null ? List.of(item) : List.of();
    }

    private List<String> path(String direction) {
        return topology.path(direction);
    }

    // ------------------------------------------------------------------ origins.py

    @SuppressWarnings("unchecked")
    private List<List<Row>> originReferenceSamples(List<String> path, Map<String, Object> packet, boolean endpointAvailable) {
        if (!endpointAvailable) {
            return List.of(new ArrayList<>(), new ArrayList<>());
        }
        double around = correctedOrTs(packet);
        List<Row> samples = db.rows(Sql.get("integrity", "origin_samples"), path.get(0), packet.get("canon"), around - 30, around + 30);
        Object proto = packet.get("proto");
        if ("ICMP".equals(proto) || "ICMPv6".equals(proto)) {
            Object q = metadata(packet).get("quoted");
            Map<String, Object> quoted = Evidence.truthy(q) ? (Map<String, Object>) q : Map.of();
            samples = db.rows(Sql.get("integrity", "icmp_samples"), path.get(0), packet.get("src"), packet.get("dst"),
                    quoted.get("dport"), quoted.get("sport"), around - 30, around + 30);
        }
        List<Object> endpointKeys = new ArrayList<>();
        for (Row p : samples) {
            endpointKeys.add(p.get("packet_key"));
        }
        List<Row> local = endpointKeys.isEmpty() ? new ArrayList<>()
                : db.rows(Sql.get("integrity", "local_samples"), packet.get("point"), endpointKeys);
        return List.of(samples, local);
    }

    @SuppressWarnings("unchecked")
    void origins() {
        if (!Evidence.truthy(hints.get("origins"))) {
            return;
        }
        for (Map<String, Object> segment : segments) {
            if ("device_stage".equals(segment.get("location"))) {
                continue;
            }
            for (Map<String, Object> packet : candidates(segment, Sql.get("integrity", "ORIGIN_CONDITION"))) {
                List<String> path = path((String) segment.get("direction"));
                List<String> upstream = new ArrayList<>(path.subList(0, indexOf(path, segment.get("point_b"))));
                if (db.count(Sql.get("integrity", "upstream_seen"), upstream, packet.get("packet_key")) > 0) {
                    continue;
                }
                boolean endpointAvailable = hasEndpoint(path, (String) segment.get("direction"));
                List<List<Row>> s = originReferenceSamples(path, packet, endpointAvailable);
                List<Row> samples = s.get(0);
                List<Row> localSamples = s.get(1);
                Map<String, Object> pattern = endpointPattern(samples, packet);
                TreeSet<Long> comparison = new TreeSet<>();
                for (Row p : localSamples) {
                    comparison.add(p.lng("ttl"));
                }
                List<Long> comparisonTtls = new ArrayList<>(comparison);
                pattern.put("ttl_values_at_first_point", comparisonTtls);
                pattern.put("ttl_consistent_with_endpoint", localSamples.size() >= 3 ? comparisonTtls.contains(packet.get("ttl")) : null);

                String why = reason(segment, packet.get("corrected"));
                if (!"device".equals(segment.get("location"))) {
                    why = why != null ? why : "Capture points do not bracket one device; injection source unknown";
                }
                if (!Boolean.TRUE.equals(packet.get("eligible")) || Evidence.truthy(packet.get("translation_reason"))) {
                    Object t = packet.get("translation_reason");
                    Object x = packet.get("excluded_reason");
                    why = Evidence.truthy(t) ? (String) t : Evidence.truthy(x) ? (String) x : "Unmatchable origin identity";
                }
                Object consistent = pattern.get("ttl_consistent_with_endpoint");
                if (!endpointAvailable) {
                    why = "no endpoint-side capture";
                } else if (Boolean.TRUE.equals(consistent)) {
                    why = orElse(why, "Endpoint-origin TTL pattern is consistent; capture miss cannot be excluded");
                } else if (consistent == null) {
                    why = orElse(why, "Insufficient matched endpoint references at the first-observation point");
                }
                if ("unknown".equals(pattern.get("status"))) {
                    why = orElse(why, (String) pattern.get("reason"));
                }
                Object vendorText = packet.get("vendor");
                Map<String, Object> vendor = (Map<String, Object>) Evidence.loads(Evidence.truthy(vendorText) ? (String) vendorText : "{}");
                String label = "TCP".equals(packet.get("proto")) ? "RST" : "ICMP unreachable";
                Object reset = vendor.get("f5ethtrailer.rstcausetxt");
                String summary = !Evidence.truthy(why) ? label + " injected by " + Evidence.pyStr(segment.get("device"))
                        : label + " first observed at " + points.get(packet.get("point")).label() + " (" + location(segment)
                                + "); origin unknown: " + why;
                if (Evidence.truthy(reset)) {
                    summary += "; F5 reset explanation: " + Evidence.pyStr(reset);
                }
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("first_point", packet.get("point"));
                metrics.put("endpoint_pattern", pattern);
                metrics.put("quality_reason", why);
                metrics.put("vendor", vendor);
                List<Map<String, Object>> evidencePackets = new ArrayList<>();
                evidencePackets.add(packet);
                evidencePackets.addAll(samples.subList(0, Math.min(7, samples.size())));
                evidencePackets.addAll(localSamples.subList(0, Math.min(7, localSamples.size())));
                add(segment, "TCP".equals(packet.get("proto")) ? "reset_origin" : "icmp_origin", packet, summary, ORIGIN_TIP,
                        metrics, !Evidence.truthy(why), evidencePackets, "high",
                        Evidence.truthy(reset) ? "vendor_reset_evidence" : "unknown");
            }
        }
    }

    private static String orElse(String why, String fallback) {
        return Evidence.truthy(why) ? why : fallback;
    }

    private static int indexOf(List<String> path, Object item) {
        int i = path.indexOf(item);
        if (i < 0) {
            throw new IllegalArgumentException(item + " is not in list");
        }
        return i;
    }

    void endpointOrigins() {
        if (!Evidence.truthy(hints.get("origins"))) {
            return;
        }
        for (String direction : List.of("forward", "reverse")) {
            List<String> path = path(direction);
            if (path.size() < 2) {
                continue;
            }
            Map<String, Object> segment = null;
            for (Map<String, Object> s : segments) {
                if (path.get(0).equals(s.get("point_a")) && direction.equals(s.get("direction"))) {
                    segment = s;
                    break;
                }
            }
            if (segment == null) {
                continue;
            }
            List<Row> packets = db.rows(Sql.get("integrity", "endpoint_packets"), path.get(0), direction, (long) LIMIT);
            boolean endpointAvailable = hasEndpoint(path, direction);
            Point first = points.get(path.get(0));
            for (Row packet : packets) {
                List<Row> refs = db.rows(Sql.get("integrity", "endpoint_refs"), path.get(0), packet.get("canon"));
                String label = "TCP".equals(packet.get("proto")) ? "RST" : "ICMP error";
                Map<String, Object> seg = new LinkedHashMap<>(segment);
                seg.put("device", endpointAvailable ? first.device() : null);
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("count", packet.get("origin_count"));
                metrics.put("first_point", path.get(0));
                metrics.put("endpoint_pattern", endpointPattern(endpointAvailable ? refs : List.of(), packet));
                metrics.put("quality_reason", endpointAvailable ? null : "no endpoint-side capture");
                List<Map<String, Object>> evidencePackets = new ArrayList<>();
                evidencePackets.add(packet);
                evidencePackets.addAll(refs);
                add(seg, "TCP".equals(packet.get("proto")) ? "reset_origin" : "icmp_origin", packet,
                        label + " first observed at " + first.label() + "; "
                                + (endpointAvailable ? "source authenticity unknown" : "origin unknown: no endpoint-side capture"),
                        ORIGIN_TIP, metrics, endpointAvailable, evidencePackets, "quality");
            }
        }
    }

    // ------------------------------------------------------------------ payloads.py

    @SuppressWarnings("unchecked")
    void payloadChanges() {
        for (Map<String, Object> segment : segments) {
            Point a = points.get(segment.get("point_a"));
            Point b = points.get(segment.get("point_b"));
            if (a.translation().equals("full_proxy") || b.translation().equals("full_proxy")
                    || (a.device().equals(b.device()) && (!a.payloadTransform().equals("none") || !b.payloadTransform().equals("none")))) {
                continue;
            }
            List<Row> candidates = db.rows(Sql.get("integrity", "payload_candidates"), a.id(), b.id(), segment.get("direction"),
                    (long) (LIMIT + 1));
            if (candidates.size() > LIMIT) {
                notes.add(segment.get("id") + ": payload comparison sample limited to " + LIMIT + " unique pairs");
            }
            for (Row pair : candidates.subList(0, Math.min(LIMIT, candidates.size()))) {
                Map<String, Object> before = (Map<String, Object>) pair.get("before_packet");
                Map<String, Object> after = (Map<String, Object>) pair.get("after_packet");
                Map<String, Object> x = metadata(before);
                Map<String, Object> y = metadata(after);
                if (!Evidence.truthy(x.get("payload_complete")) || !Evidence.truthy(y.get("payload_complete"))
                        || Objects.equals(x.get("payload_sha256"), y.get("payload_sha256"))) {
                    continue;
                }
                String why = reason(segment, before.get("corrected"));
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("before_sha256", x.get("payload_sha256"));
                metrics.put("after_sha256", y.get("payload_sha256"));
                metrics.put("quality_reason", why);
                add(segment, "payload_modified", before,
                        why == null ? "Payload changed across " + location(segment)
                                : "Payload comparison unknown at " + location(segment) + ": " + why,
                        "Unique canonical tuple/SEQ/ACK/flags/length pair within the match window; complete payload hashes differ. Ambiguous retries and offload boundaries are excluded. A change does not establish malicious intent.",
                        metrics, why == null, List.of(before, after), "high");
            }
        }
    }

    @SuppressWarnings("unchecked")
    void downstreamOnly() {
        Set<List<Object>> modified = new LinkedHashSet<>();
        for (Map<String, Object> f : findings) {
            if ("payload_modified".equals(f.get("type"))) {
                for (Map<String, Object> e : (List<Map<String, Object>>) f.get("evidence")) {
                    modified.add(Arrays.asList(e.get("point"), e.get("frame")));
                }
            }
        }
        for (Map<String, Object> segment : segments) {
            List<String> path = path((String) segment.get("direction"));
            if (!path.contains(segment.get("point_b")) || "device_stage".equals(segment.get("location"))) {
                continue;
            }
            if (Evidence.truthy(segment.get("reason"))) {
                continue;
            }
            List<String> upstream = new ArrayList<>(path.subList(0, path.indexOf(segment.get("point_b"))));
            for (Map<String, Object> packet : candidates(segment, "b.eligible AND b.proto='TCP' AND b.length>0")) {
                if (modified.contains(Arrays.asList(packet.get("point"), packet.get("frame")))) {
                    continue;
                }
                if (db.count(Sql.get("integrity", "upstream_seen"), upstream, packet.get("packet_key")) > 0) {
                    continue;
                }
                String why = reason(segment, packet.get("corrected"));
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("quality_reason", why);
                add(segment, "downstream_packet", packet,
                        "Downstream-only packet at " + points.get(packet.get("point")).label() + "; origin unknown"
                                + (why != null ? ": " + why : "; injection and capture miss cannot be separated by absence alone"),
                        "Packet observed downstream without a matching upstream occurrence in the configured path. Unreported capture loss, translation or alternate routing can explain it; this is not confirmed malicious injection.",
                        metrics, false, null, "low");
            }
        }
    }

    // ------------------------------------------------------------------ path_checks.py

    @SuppressWarnings("unchecked")
    void mtu() {
        for (Map<String, Object> segment : segments) {
            if ("device_stage".equals(segment.get("location"))) {
                continue;
            }
            List<Map<String, Object>> clamped = Evidence.truthy(hints.get("mss"))
                    ? changedPair(segment, Sql.get("integrity", "MSS_CONDITION")) : List.of();
            for (Map<String, Object> pair : clamped) {
                Map<String, Object> before = (Map<String, Object>) pair.get("before_packet");
                Map<String, Object> after = (Map<String, Object>) pair.get("after_packet");
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("count", pair.get("count"));
                metrics.put("before_mss", before.get("mss"));
                metrics.put("after_mss", after.get("mss"));
                add(segment, "mss_clamping", before,
                        "MSS clamping at " + location(segment) + ": " + Evidence.pyStr(before.get("mss")) + " → "
                                + Evidence.pyStr(after.get("mss")) + " B",
                        "MSS advertisement reduced between matched SYNs. This is observed clamping, not proof of a path MTU fault.",
                        metrics, true, List.of(before, after), "low");
            }
            if (!Evidence.truthy(hints.get("large"))) {
                continue;
            }
            List<Row> losses = db.rows(Sql.get("integrity", "mtu_losses"), segment.get("point_a"), segment.get("point_b"),
                    segment.get("direction"), (long) LIMIT);
            for (Row loss : losses) {
                Map<String, Object> packet = (Map<String, Object>) loss.get("packet");
                List<Row> small = db.rows(Sql.get("integrity", "mtu_small"), segment.get("point_a"), packet.get("flow"),
                        segment.get("direction"), ((Number) loss.get("min_length")).doubleValue() / 2, segment.get("point_b"));
                if (small.size() < 3) {
                    continue;
                }
                String why = reason(segment, packet.get("corrected"));
                Map<String, Object> meta = metadata(packet);
                if (!isV6(packet) && !Boolean.TRUE.equals(meta.get("df"))) {
                    why = orElse(why, "IPv4 DF not established");
                }
                List<Row> feedback = db.rows(Sql.get("integrity", "mtu_feedback"), packet.get("flow"));
                String name = location(segment);
                List<Object> large = (List<Object>) loss.get("large_frames");
                List<Row> largePackets = db.rows(Sql.get("integrity", "mtu_large"), segment.get("point_a"),
                        new ArrayList<>(large.subList(0, Math.min(3, large.size()))));
                List<Object> advertised = new ArrayList<>();
                for (Row p : feedback) {
                    advertised.add(metadata(p).get("icmp_mtu"));
                }
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("count", loss.get("lost_ranges"));
                metrics.put("large_min_bytes", loss.get("min_length"));
                metrics.put("small_passed", (long) small.size());
                metrics.put("df", meta.get("df"));
                metrics.put("icmp_feedback", feedback.isEmpty() ? "not observed; generation/delivery unknown" : "present");
                metrics.put("advertised_mtu", advertised);
                metrics.put("quality_reason", why);
                List<Map<String, Object>> evidencePackets = new ArrayList<>(largePackets);
                evidencePackets.addAll(small);
                evidencePackets.addAll(feedback);
                add(segment, "mtu_black_hole", packet,
                        "MTU/PMTUD black-hole pattern at " + name + "; cause unknown" + (Evidence.truthy(why) ? " (" + why + ")" : ""),
                        "At least three distinct near-MSS TCP ranges disappear (missing bytes, not the enclosing GRO frame size) while three smaller payload segments of the same flow pass. DF/IPv6 and quoted-flow ICMP feedback are shown. Size-selective policy or capture loss can mimic MTU trouble; missing ICMP is not proof it was never sent.",
                        metrics, !Evidence.truthy(why), evidencePackets, "high");
            }
        }
    }

    @SuppressWarnings("unchecked")
    void pathChecks() {
        String[][] conditions = {
            {"ttl_path", Sql.get("integrity", "TTL_CONDITION"), "TTL/hop-limit path deviation",
                "A matched packet lost more than one TTL step or increased TTL between drawn adjacent points. Extra routing, a tunnel, TTL rewrite or a loop is possible; this alone cannot distinguish them."},
            {"dscp_remark", Sql.get("integrity", "DSCP_CONDITION"), "DSCP remarking",
                "DSCP changed between matched packets. This is observed remarking, not necessarily a fault."},
            {"option_stripping", Sql.get("integrity", "OPTION_CONDITION"), "TCP option stripping",
                "A decoded SACK-permitted, window-scale or timestamp option present upstream is absent downstream in a complete matched packet. Expected SYN-only option absence on later packets is never compared."},
        };
        for (Map<String, Object> segment : segments) {
            if ("device_stage".equals(segment.get("location"))) {
                continue;
            }
            Point a = points.get(segment.get("point_a"));
            Point b = points.get(segment.get("point_b"));
            if (a.translation().equals("full_proxy") || b.translation().equals("full_proxy")) {
                continue;
            }
            for (String[] c : conditions) {
                String kind = c[0];
                if (kind.equals("ttl_path") && (a.stageCapture() || b.stageCapture())) {
                    continue;
                }
                if (kind.equals("dscp_remark") && !Evidence.truthy(hints.get("dscp"))
                        || kind.equals("option_stripping") && !Evidence.truthy(hints.get("options"))) {
                    continue;
                }
                for (Map<String, Object> pair : changedPair(segment, c[1])) {
                    Map<String, Object> before = (Map<String, Object>) pair.get("before_packet");
                    Map<String, Object> after = (Map<String, Object>) pair.get("after_packet");
                    Map<String, Object> metrics = new LinkedHashMap<>();
                    metrics.put("count", pair.get("count"));
                    metrics.put("before", fields(before));
                    metrics.put("after", fields(after));
                    add(segment, kind, before, c[2] + " at " + location(segment), c[3], metrics, true, List.of(before, after), "low");
                }
            }
        }
        Set<String> handled = new LinkedHashSet<>();
        for (Map<String, Object> segment : segments) {
            String point = (String) segment.get("point_a");
            if (!handled.add(point)) {
                continue;
            }
            Row r = repeats.get(point);
            if (r == null || !Evidence.truthy(r.get("identical")) && !Evidence.truthy(r.get("repeated"))) {
                continue;
            }
            String device = Evidence.pyStr(points.get(point).device());
            for (Row packet : db.rows(Sql.get("integrity", "span_duplicate"), point)) {
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("count", packet.get("duplicate_count"));
                metrics.put("duplicate_us", topology.duplicateUsValue());
                add(segment, "capture_duplicate", packet, "SPAN/double-capture pattern at " + device,
                        "Byte-identical complete frames at the same capture point within the configured microsecond threshold are capture duplicates. Wire-level duplication inside that resolution cannot be distinguished.",
                        metrics, true, null, "quality");
            }
            for (Row pair : duplicatePairs(db, point, topology.duplicateUs, "loop")) {
                Map<String, Object> first = (Map<String, Object>) pair.get("first_packet");
                Map<String, Object> repeat = (Map<String, Object>) pair.get("repeat_packet");
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("ttl_before", first.get("ttl"));
                metrics.put("ttl_after", repeat.get("ttl"));
                add(segment, "duplication", first,
                        "Repeated packet with falling TTL at " + device + "; loop or network duplication suspected",
                        "Same tuple, sequence identity and payload prefix reappeared with lower TTL. It is not a byte-identical SPAN duplicate; TTL rewriting and retransmission remain alternatives, so root cause is unknown.",
                        metrics, false, List.of(first, repeat), "low");
            }
            for (Row pair : duplicatePairs(db, point, topology.duplicateUs, "identical")) {
                Map<String, Object> first = (Map<String, Object>) pair.get("first_packet");
                Map<String, Object> repeat = (Map<String, Object>) pair.get("repeat_packet");
                add(segment, "duplication", first, "Possible packet duplication at " + device + "; cause unknown",
                        "Identical complete bytes repeat outside the SPAN tolerance but within 1 ms. Timing alone cannot distinguish network duplication, retransmission, or a slower capture mirror.",
                        null, false, List.of(first, repeat), "low");
            }
        }
        asymmetricPaths();
    }

    private static Map<String, Object> fields(Map<String, Object> packet) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ttl", packet.get("ttl"));
        out.put("dscp", packet.get("dscp"));
        out.put("options", metadata(packet));
        return out;
    }

    void asymmetricPaths() {
        List<String> forward = topology.forward;
        List<String> reverse = topology.reverse;
        if (reverse.isEmpty()) {
            return;
        }
        record Target(Map<String, Object> segment, List<String> missing, List<String> anchors) {
        }
        List<Target> targets = new ArrayList<>();
        int i = 0;
        while (i < forward.size()) {
            String device = points.get(forward.get(i)).device();
            int lo = i;
            List<String> missing = new ArrayList<>();
            while (i < forward.size() && Objects.equals(points.get(forward.get(i)).device(), device)) {
                missing.add(forward.get(i++));
            }
            int hi = i - 1;
            if (lo == 0 || hi == forward.size() - 1 || missing.stream().anyMatch(reverse::contains)) {
                continue;
            }
            for (Map<String, Object> s : segments) {
                if ("forward".equals(s.get("direction")) && missing.get(0).equals(s.get("point_a"))) {
                    Map<String, Object> seg = new LinkedHashMap<>(s);
                    seg.put("device", device);
                    targets.add(new Target(seg, missing, List.of(forward.get(lo - 1), forward.get(hi + 1))));
                    break;
                }
            }
        }
        for (Map<String, Object> segment : segments) {
            String a = (String) segment.get("point_a");
            String b = (String) segment.get("point_b");
            if ("forward".equals(segment.get("direction")) && "link".equals(segment.get("location"))
                    && !(points.get(a).stageCapture() || points.get(b).stageCapture()) && reverse.contains(a) && reverse.contains(b)) {
                if (reverse.indexOf(a) > reverse.indexOf(b) + 1) {
                    targets.add(new Target(segment, List.of(), List.of(a, b)));
                }
            }
        }
        for (Target t : targets) {
            String a = t.anchors().get(0);
            String b = t.anchors().get(1);
            if (!reverse.contains(a) || !reverse.contains(b) || reverse.indexOf(b) >= reverse.indexOf(a)) {
                continue;
            }
            List<String> alternative = new ArrayList<>(reverse.subList(reverse.indexOf(b) + 1, reverse.indexOf(a)));
            if (alternative.isEmpty()) {
                continue;
            }
            String point = !t.missing().isEmpty() ? t.missing().get(0) : (String) t.segment().get("point_a");
            List<Row> pairs = db.rows(Sql.get("integrity", "asymmetric_pairs"), point, alternative, new ArrayList<>(t.missing()),
                    new ArrayList<>(t.anchors()));
            for (Row pair : pairs) {
                @SuppressWarnings("unchecked")
                Map<String, Object> forwardPacket = (Map<String, Object>) pair.get("forward_packet");
                @SuppressWarnings("unchecked")
                Map<String, Object> returnPacket = (Map<String, Object>) pair.get("return_packet");
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("bypassed_points", new ArrayList<>(t.missing()));
                metrics.put("return_point", returnPacket.get("point"));
                metrics.put("bounding_points", new ArrayList<>(t.anchors()));
                add(t.segment(), "asymmetric_routing", forwardPacket,
                        "Asymmetric return bypasses " + location(t.segment()) + " in the configured path",
                        "Forward evidence plus same-flow return evidence on the declared alternate path and both bounding points. A bypassed device is reported once; a detoured inter-device edge stays a link, not a device fault.",
                        metrics, true, List.of(forwardPacket, returnPacket), "quality");
            }
        }
    }

    // ------------------------------------------------------------------ grouping.py

    /** {@code group_downstream_findings(findings, topology)}: in place. */
    @SuppressWarnings("unchecked")
    /** The integrity findings join the report findings, then {@code integrity.group_downstream_findings}. */
    static void groupDownstreamStage(EngineContext ctx) {
        ctx.findings.addAll(ctx.path.integrityFindings);
        groupDownstreamFindings(ctx.findings, ctx.topology);
        Parity.record(ctx, "group_downstream_findings", ctx.findings);
    }

    static void groupDownstreamFindings(List<Map<String, Object>> findings, Topology topology) {
        Map<String, Point> points = topology.pointMap();
        record Miss(int index, Map<String, Object> finding) {
        }
        Map<String, List<Miss>> capturesByDir = new LinkedHashMap<>();
        for (Map<String, Object> f : findings) {
            if ("capture_miss".equals(f.get("type"))) {
                Integer idx = segIndex(topology, (String) f.get("hop"), (String) f.get("direction"));
                if (idx != null) {
                    capturesByDir.computeIfAbsent((String) f.get("direction"), k -> new ArrayList<>()).add(new Miss(idx, f));
                }
            }
        }
        Map<Object, Object[]> groupedByMiss = new LinkedHashMap<>();
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> f : findings) {
            if (!"downstream_packet".equals(f.get("type"))) {
                kept.add(f);
                continue;
            }
            Integer idx = segIndex(topology, (String) f.get("hop"), (String) f.get("direction"));
            Miss best = null;
            for (Miss m : capturesByDir.getOrDefault((String) f.get("direction"), List.of())) {
                if (idx != null && m.index() <= idx && t0(f) + 0.005 >= t0(m.finding())) {
                    if (best == null || m.index() > best.index()
                            || m.index() == best.index() && t0(m.finding()) > t0(best.finding())) {
                        best = m;
                    }
                }
            }
            if (best == null) {
                kept.add(f);
                continue;
            }
            Map<String, Object> target = best.finding();
            Object[] entry = groupedByMiss.computeIfAbsent(target.get("id"), k -> new Object[] {target, new ArrayList<Map<String, Object>>()});
            ((List<Map<String, Object>>) entry[1]).add(f);
        }
        for (Object[] entry : groupedByMiss.values()) {
            Map<String, Object> target = (Map<String, Object>) entry[0];
            List<Map<String, Object>> downstreams = (List<Map<String, Object>>) entry[1];
            long count = 0;
            List<String> labels = new ArrayList<>();
            for (Map<String, Object> df : downstreams) {
                Map<String, Object> m = (Map<String, Object>) df.get("metrics");
                count += m.containsKey("count") ? (Long) m.get("count") : 1L;
                String[] parts = ((String) df.get("hop")).split(":", -1);
                String label = parts.length == 3 && points.containsKey(parts[2]) ? points.get(parts[2]).label() : (String) df.get("hop");
                if (!labels.contains(label)) {
                    labels.add(label);
                }
            }
            String line = count + " downstream-only packet observation(s) at " + String.join(", ", labels)
                    + " follow this capture miss (origin unknown; consistent with upstream capture miss).";
            target.put("downstream_summary", line);
            target.put("downstream_count", count);
            ((Map<String, Object>) target.get("metrics")).put("downstream_packet_count", count);
            target.put("summary", Evidence.pyStr(target.get("summary")) + " " + line);
            if (target.containsKey("headline")) {
                target.put("headline", Evidence.pyStr(target.get("headline")) + " " + line);
            }
            List<Map<String, Object>> evidence = (List<Map<String, Object>>) target.get("evidence");
            Set<List<Object>> seen = new LinkedHashSet<>();
            for (Map<String, Object> r : evidence) {
                seen.add(Arrays.asList(r.get("point"), r.get("frame")));
            }
            for (Map<String, Object> df : downstreams) {
                for (Map<String, Object> r : (List<Map<String, Object>>) df.get("evidence")) {
                    List<Object> key = Arrays.asList(r.get("point"), r.get("frame"));
                    if (!seen.contains(key) && evidence.size() < 16) {
                        seen.add(key);
                        evidence.add(r);
                    }
                }
            }
        }
        List<Map<String, Object>> merged = mergeQualityLimited(kept);
        findings.clear();
        findings.addAll(merged);
    }

    @SuppressWarnings("unchecked")
    private static double t0(Map<String, Object> f) {
        return ((Number) ((List<Object>) f.get("time_range")).get(0)).doubleValue();
    }

    private static Integer segIndex(Topology topology, String hop, String direction) {
        String[] parts = hop.split(":", -1);
        if (parts.length != 3) {
            return null;
        }
        List<String> path = direction.equals("forward") ? topology.forward
                : direction.equals("reverse") ? topology.path("reverse") : List.of();
        int i = path.indexOf(parts[1]);
        return i < 0 ? null : i;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mergeQualityLimited(List<Map<String, Object>> findings) {
        Map<List<Object>, Map<String, Object>> merged = new LinkedHashMap<>();
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> f : findings) {
            Object m = f.get("metrics");
            Object why = m instanceof Map<?, ?> mm ? mm.get("quality_reason") : null;
            if (!"downstream_packet".equals(f.get("type")) || !Evidence.truthy(why)) {
                kept.add(f);
                continue;
            }
            List<Object> key = Arrays.asList(f.get("direction"), f.get("hop"), why);
            if (!merged.containsKey(key)) {
                Map<String, Object> carrier = new LinkedHashMap<>(f);
                carrier.put("evidence", new ArrayList<>((List<Object>) f.get("evidence")));
                Map<String, Object> metrics = new LinkedHashMap<>((Map<String, Object>) f.get("metrics"));
                metrics.put("count", 0L);
                carrier.put("metrics", metrics);
                carrier.put("id", f.get("id") + ":merged");
                carrier.put("severity", "quality");
                merged.put(key, carrier);
                kept.add(carrier);
            }
            Map<String, Object> carrier = merged.get(key);
            Map<String, Object> cm = (Map<String, Object>) carrier.get("metrics");
            Map<String, Object> fm = (Map<String, Object>) f.get("metrics");
            cm.put("count", (Long) cm.get("count") + (fm.containsKey("count") ? (Long) fm.get("count") : 1L));
            List<Object> ct = (List<Object>) carrier.get("time_range");
            List<Object> ft = (List<Object>) f.get("time_range");
            carrier.put("time_range", new ArrayList<>(List.of(
                    Evidence.pyMin(((Number) ct.get(0)).doubleValue(), ((Number) ft.get(0)).doubleValue()),
                    Evidence.pyMax(((Number) ct.get(1)).doubleValue(), ((Number) ft.get(1)).doubleValue()))));
            List<Object> evidence = (List<Object>) carrier.get("evidence");
            for (Object ref : (List<Object>) f.get("evidence")) {
                if (!evidence.contains(ref) && evidence.size() < 16) {
                    evidence.add(ref);
                }
            }
        }
        for (Map<String, Object> carrier : merged.values()) {
            Map<String, Object> cm = (Map<String, Object>) carrier.get("metrics");
            String text = cm.get("count") + " downstream-only packet observation(s) explained by capture limits ("
                    + cm.get("quality_reason") + "); not evidence of injection.";
            carrier.put("summary", text);
            carrier.put("headline", text);
        }
        return kept;
    }
}
