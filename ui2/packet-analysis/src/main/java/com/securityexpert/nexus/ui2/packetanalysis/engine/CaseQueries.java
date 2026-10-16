package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.export.EngineExport;
import com.securityexpert.nexus.ui2.packetanalysis.export.Export;
import com.securityexpert.nexus.ui2.packetanalysis.export.FieldDiff;
import com.securityexpert.nexus.ui2.packetanalysis.export.ReferenceCompare;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import com.securityexpert.nexus.ui2.packetanalysis.topology.CaseSetup;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.regex.Pattern;

/**
 * Read-only query projections over an analysed case {@code project.duckdb}, mirroring the reference API routes
 * ({@code state}, {@code flows}, {@code ladder}, {@code waterfall}, {@code charts}, {@code frames}, {@code diagnosis},
 * {@code timeseries}, {@code topology/overlay}, {@code topology/inspect}, {@code findings}, {@code events},
 * {@code dns}, {@code field-diff}, and {@code export/findings}).
 */
public final class CaseQueries {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MOD = 1L << 32;
    private static final int WATERFALL_LIMIT = 20000;
    private static final Pattern HTTP_REQUEST = Pattern.compile("^[A-Z]+ [^\\r\\n]+ HTTP/1\\.[01]\\r\\n");
    private static final Pattern HTTP_RESPONSE = Pattern.compile("^HTTP/1\\.[01] ([2-5][0-9]{2})[ \\r]");

    private static final List<String> LOSS_CLASSES = List.of(
            "impactful_loss",
            "recovered_loss",
            "unrecovered_loss",
            "handshake_blocked",
            "confirmed_device_drop"
    );

    private CaseQueries() {
    }

    @SuppressWarnings("unchecked")
    public static Object settingGet(Db db, String key) {
        try {
            Object[] row = db.one("SELECT value FROM settings WHERE key=?", key);
            return row == null || row[0] == null ? null : Evidence.loads((String) row[0]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Topology loadTopology(Db db) {
        Object raw = settingGet(db, "topology");
        if (raw == null) {
            return new Topology();
        }
        try {
            return Topology.parse(MAPPER.readTree(PyJsonWriter.dumps(raw, false)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> readInventory(Db db) {
        List<Row> rows;
        try {
            rows = db.rows("SELECT id,path,name,state,checkpoint,inventory,error FROM captures ORDER BY name");
        } catch (RuntimeException e) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : rows) {
            Map<String, Object> capture = new LinkedHashMap<>(r);
            Object invRaw = capture.get("inventory");
            Map<String, Object> info = invRaw instanceof String s && !s.isBlank()
                    ? (Map<String, Object>) Evidence.loads(s)
                    : new LinkedHashMap<>();
            if ("ready".equals(capture.get("state")) && !info.containsKey("observed_max_caplen")) {
                try {
                    Object[] stats = db.one(
                            "SELECT max(caplen),min(caplen) FILTER(WHERE caplen<wirelen),"
                                    + "max(caplen) FILTER(WHERE caplen<wirelen) FROM packets WHERE capture_id=?",
                            capture.get("id"));
                    if (stats != null) {
                        info.put("observed_max_caplen", stats[0]);
                        info.put("truncated_caplen_min", stats[1]);
                        info.put("truncated_caplen_max", stats[2]);
                    }
                } catch (RuntimeException ignored) {
                    // packets table may not exist
                }
            }
            capture.put("inventory", info);
            out.add(capture);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> state(Db db, String projectLabel) {
        Map<String, Object> preferences = (Map<String, Object>) settingGet(db, "preferences");
        if (preferences == null) {
            preferences = new LinkedHashMap<>();
        }
        Object topology = settingGet(db, "topology");
        if (topology == null) {
            topology = defaultTopologyMap();
        }
        Map<String, Object> report = (Map<String, Object>) settingGet(db, "report");
        Object conversion = settingGet(db, "fortinet_conversion");
        Object caseContext = settingGet(db, "case_context");
        Object suggestedTopology = settingGet(db, "suggested_topology");
        List<Map<String, Object>> captures = readInventory(db);
        Map<String, Object> preflight = captures.isEmpty() ? Map.of("files", List.of(), "overlap",
                Map.of("status", "unknown", "reason", "No captures ingested"))
                : CaseSetup.inventoryOverview(captures);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", Engine.VERSION);
        out.put("project", projectLabel);
        out.put("captures", captures);
        out.put("preflight", preflight);
        out.put("case_context", caseContext);
        out.put("suggested_topology", suggestedTopology);
        out.put("topology", topology);
        out.put("report", report);
        out.put("settings", preferences);
        out.put("fortinet_conversion", conversion);
        out.put("tshark", Map.of("path", "tshark", "error", ""));
        out.put("job", Map.of("state", "idle", "busy", false));
        return out;
    }

    private static Map<String, Object> defaultTopologyMap() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("points", List.of());
        t.put("forward", List.of());
        t.put("reverse", List.of());
        t.put("client_cidrs", List.of());
        t.put("bucket_seconds", 1.0);
        t.put("stall_ms", 200.0);
        t.put("dns_timeout_ms", 2000.0);
        t.put("min_eligible_ratio", 0.9);
        t.put("report_timezone", "UTC");
        return t;
    }

    // ------------------------------------------------------------------ pages.py: flow_page, ladder, event_page, findings_page

    public static Map<String, Object> flowPage(
            Db db,
            long offset,
            long limit,
            String search,
            String filterBy,
            String sort,
            Double start,
            Double end,
            List<String> points) {
        if (settingGet(db, "report") == null) {
            return Map.of("total", 0L, "items", List.of());
        }
        String safeSearch = search == null ? "" : search;
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        clauses.add("tuple ILIKE ?");
        params.add("%" + safeSearch + "%");
        if (points != null && !points.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(points.size(), "?"));
            clauses.add("EXISTS(SELECT 1 FROM obs o WHERE o.flow=flow_summary.flow AND o.point IN ("
                    + placeholders + ") AND (? IS NULL OR corrected>=?) AND (? IS NULL OR corrected<?))");
            params.addAll(points);
            params.addAll(Arrays.asList(start, start, end, end));
        }
        if (start != null || end != null) {
            clauses.add("EXISTS(SELECT 1 FROM obs o WHERE o.flow=flow_summary.flow "
                    + "AND (? IS NULL OR corrected>=?) AND (? IS NULL OR corrected<?))");
            params.addAll(Arrays.asList(start, start, end, end));
        }
        Map<String, String> filters = Map.of(
                "impactful", "impactful_loss>0",
                "resets", "has_reset",
                "device_drops", "confirmed_device_drop>0",
                "handshakes", "handshake_incomplete"
        );
        if (filterBy != null && filters.containsKey(filterBy)) {
            clauses.add(filters.get(filterBy));
        }
        Set<String> allowedSorts = Set.of(
                "bytes",
                "max_stall_ms",
                "impactful_loss",
                "retrans_observations",
                "start",
                "reset_origin",
                "handshake_rtt_ms"
        );
        String order = sort != null && allowedSorts.contains(sort) ? sort : "bytes";
        String where = String.join(" AND ", clauses);
        long total = db.count("SELECT count(*) FROM flow_summary WHERE " + where, params.toArray());
        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(limit);
        queryParams.add(offset);
        List<Row> items = db.rows(
                "SELECT * FROM flow_summary WHERE " + where + " ORDER BY " + order + " DESC NULLS LAST,flow LIMIT ? OFFSET ?",
                queryParams.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("items", items);
        return out;
    }

    public static void labelHostEvents(List<Row> events, Topology topology) {
        if (topology == null || topology.points.isEmpty()) {
            return;
        }
        Map<String, Point> points = topology.pointMap();
        for (Row item : events) {
            String pointA = item.str("point_a");
            String pointB = item.str("point_b");
            if (pointA == null || pointB == null || !points.containsKey(pointA) || !points.containsKey(pointB)) {
                continue;
            }
            boolean recovered = Evidence.truthy(item.get("recovery_key")) && Evidence.truthy(item.get("is_data"));
            String kind = HostPath.hostLossType(points, pointA, pointB, item.str("kind"), recovered);
            if (kind != null) {
                item.put("recovery_class", item.get("kind"));
                item.put("kind", kind);
            }
        }
    }

    public static Map<String, Object> ladder(Db db, String flow, long offset, long limit, Double start, Double end) {
        if (settingGet(db, "report") == null) {
            return Map.of("items", List.of(), "total", 0L, "events", List.of());
        }
        List<Row> keys = db.rows(
                "SELECT packet_key,min(corrected) AS ts,bool_or(retrans) AS retrans,"
                        + "min(seq) AS seq,min(ack) AS ack,min(flags) AS flags,min(length) AS length,min(direction) AS direction "
                        + "FROM obs WHERE flow=? AND (? IS NULL OR corrected>=?) AND (? IS NULL OR corrected<?) "
                        + "GROUP BY packet_key ORDER BY ts NULLS LAST,packet_key LIMIT ? OFFSET ?",
                flow, start, start, end, end, limit, offset);
        for (Row key : keys) {
            key.put("evidence", Evidence.evidence(db, "o.packet_key=?", List.of(key.get("packet_key")), 64));
        }
        List<Row> events = db.rows(
                "SELECT * FROM events WHERE flow=? AND (? IS NULL OR ts>=?) AND (? IS NULL OR ts<?) ORDER BY ts LIMIT 200",
                flow, start, start, end, end);
        labelHostEvents(events, loadTopology(db));
        List<Row> localMetrics = db.rows(
                "SELECT point,min(tuple_key) AS tuple,min(reverse_tuple) AS reverse_tuple,"
                        + "count(*) FILTER(WHERE retrans) AS retransmissions,count(*) FILTER(WHERE zero_window) AS zero_windows,"
                        + "count(*) FILTER(WHERE (flags&4)>0) AS resets,max(rtt)*1000 AS max_rtt_ms "
                        + "FROM obs WHERE flow=? AND (? IS NULL OR corrected>=?) AND (? IS NULL OR corrected<?) "
                        + "GROUP BY point ORDER BY point",
                flow, start, start, end, end);
        for (Row metric : localMetrics) {
            List<Row> handshake = db.rows(
                    "SELECT s.frame AS syn_frame,sa.frame AS synack_frame,"
                            + "(sa.ts-s.ts)*1000 AS syn_to_synack_ms,min(a.ts-sa.ts)*1000 AS synack_to_ack_ms "
                            + "FROM obs s JOIN obs sa ON sa.point=s.point AND sa.stream=s.stream "
                            + "AND (sa.flags&18)=18 AND sa.ack=(s.seq+1)%4294967296 AND sa.ts>s.ts AND sa.ts<s.ts+60 "
                            + "LEFT JOIN obs a ON a.point=s.point AND a.stream=s.stream AND a.tuple_key=s.tuple_key "
                            + "AND (a.flags&18)=16 AND a.ack=(sa.seq+1)%4294967296 AND a.ts>sa.ts AND a.ts<sa.ts+60 "
                            + "WHERE s.point=? AND s.flow=? AND s.proto='TCP' AND (s.flags&18)=2 "
                            + "AND (? IS NULL OR s.corrected>=?) AND (? IS NULL OR s.corrected<?) "
                            + "GROUP BY s.frame,sa.frame,s.ts,sa.ts ORDER BY s.ts LIMIT 1",
                    metric.get("point"), flow, start, start, end, end);
            metric.put("handshake", handshake.isEmpty() ? null : handshake.get(0));
        }
        long total = db.count(
                "SELECT count(DISTINCT packet_key) FROM obs WHERE flow=? AND (? IS NULL OR corrected>=?) AND (? IS NULL OR corrected<?)",
                flow, start, start, end, end);
        List<Row> filters = db.rows(
                "SELECT f.capture_id,c.name AS file,f.display_filter FROM flow_filters f "
                        + "JOIN captures c ON c.id=f.capture_id WHERE f.flow=? ORDER BY c.name",
                flow);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", keys);
        out.put("total", total);
        out.put("events", events);
        out.put("local_metrics", localMetrics);
        out.put("flow_filters", filters);
        return out;
    }

    public static Map<String, Object> eventPage(
            Db db, String pointA, String pointB, String direction, long offset, long limit, Double start, Double end) {
        if (settingGet(db, "report") == null) {
            return Map.of("items", List.of(), "total", 0L);
        }
        List<Row> data = db.rows(
                "SELECT * FROM events WHERE (? IS NULL OR point_a=?) AND (? IS NULL OR point_b=?) "
                        + "AND (? IS NULL OR direction=?) AND (? IS NULL OR ts>=?) AND (? IS NULL OR ts<?) "
                        + "ORDER BY ts LIMIT ? OFFSET ?",
                pointA, pointA, pointB, pointB, direction, direction, start, start, end, end, limit, offset);
        labelHostEvents(data, loadTopology(db));
        for (Row item : data) {
            Object br = item.get("byte_ranges");
            if (br instanceof String s && !s.isBlank()) {
                item.put("byte_ranges", Evidence.loads(s));
            }
            item.put("evidence", Evidence.evidence(
                    db,
                    "o.packet_key IN (?,?,?)",
                    Arrays.asList(item.get("packet_key"), item.get("recovery_key"), item.get("support_key"))));
        }
        long total = db.count(
                "SELECT count(*) FROM events WHERE (? IS NULL OR point_a=?) AND (? IS NULL OR point_b=?) "
                        + "AND (? IS NULL OR direction=?) AND (? IS NULL OR ts>=?) AND (? IS NULL OR ts<?)",
                pointA, pointA, pointB, pointB, direction, direction, start, start, end, end);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", data);
        out.put("total", total);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> findingsPage(Db db, Double start, Double end) {
        Map<String, Object> report = (Map<String, Object>) settingGet(db, "report");
        if (report == null) {
            return Map.of("items", List.of());
        }
        List<Map<String, Object>> reportFindings =
                (List<Map<String, Object>>) report.getOrDefault("findings", List.of());
        if (start == null && end == null) {
            return Map.of("items", reportFindings);
        }
        List<Row> groups = db.rows(
                "SELECT point_a,point_b,direction,kind,count(*) AS count,min(ts) AS first,max(ts) AS last,"
                        + "max(recovery_ms) AS max_recovery_ms FROM events WHERE (? IS NULL OR ts>=?) AND (? IS NULL OR ts<?) "
                        + "GROUP BY point_a,point_b,direction,kind ORDER BY first",
                start, start, end, end);
        Map<String, Map<String, Object>> originals = new LinkedHashMap<>();
        for (Map<String, Object> f : reportFindings) {
            originals.put((String) f.get("id"), f);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Row g : groups) {
            String hop = g.str("direction") + ":" + g.str("point_a") + ":" + g.str("point_b");
            String key = hop + ":" + g.str("kind");
            Map<String, Object> f = originals.get(key);
            if (f == null) {
                continue;
            }
            Object[] event = db.one(
                    "SELECT packet_key,recovery_key,support_key FROM events WHERE point_a=? AND point_b=? "
                            + "AND direction=? AND kind=? AND (? IS NULL OR ts>=?) AND (? IS NULL OR ts<?) ORDER BY ts LIMIT 1",
                    g.get("point_a"), g.get("point_b"), g.get("direction"), g.get("kind"), start, start, end, end);
            String typeStr = ((String) f.get("type")).replace('_', ' ');
            String text = g.get("count") + " " + typeStr + " events in the selected interval.";
            Map<String, Object> copy = new LinkedHashMap<>(f);
            copy.put("headline", text);
            copy.put("summary", text);
            copy.put("time_range", Arrays.asList(g.get("first"), g.get("last")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", g.get("count"));
            m.put("max_recovery_ms", g.get("max_recovery_ms"));
            copy.put("metrics", m);
            copy.put("evidence", event == null ? List.of()
                    : Evidence.evidence(db, "o.packet_key IN (?,?,?)", Arrays.asList(event)));
            items.add(copy);
        }
        List<Map<String, Object>> exportFindings = (List<Map<String, Object>>) settingGet(db, "export_findings");
        if (exportFindings != null) {
            for (Map<String, Object> f : exportFindings) {
                String id = (String) f.get("id");
                if (id != null && (id.startsWith("integrity:") || id.startsWith("security:") || id.startsWith("media:"))) {
                    List<Object> tr = (List<Object>) f.get("time_range");
                    Double t0 = tr != null && !tr.isEmpty() && tr.get(0) instanceof Number n0 ? n0.doubleValue() : null;
                    Double t1 = tr != null && tr.size() > 1 && tr.get(1) instanceof Number n1 ? n1.doubleValue() : null;
                    if ((start == null || (t1 != null && t1 >= start)) && (end == null || (t0 != null && t0 < end))) {
                        items.add(f);
                    }
                }
            }
        }
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < reportFindings.size(); i++) {
            order.put((String) reportFindings.get(i).get("id"), i);
        }
        items.sort(Comparator.comparingInt(f -> order.getOrDefault((String) f.get("id"), order.size())));
        return Map.of("items", items);
    }

    // ------------------------------------------------------------------ pilot.py: topology_overlay, topology_inspect

    @SuppressWarnings("unchecked")
    public static Map<String, Object> topologyOverlay(Db db, Double start, Double end) {
        Map<String, Object> report = (Map<String, Object>) settingGet(db, "report");
        Topology topology = loadTopology(db);
        if (report == null) {
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("start", start);
            scope.put("end", end);
            return Map.of("segments", List.of(), "nodes", List.of(), "scope", scope);
        }
        Map<String, Object> window = (Map<String, Object>) report.getOrDefault("window", Map.of());
        Double winStart = window.get("start") instanceof Number n ? n.doubleValue() : null;
        Double winEnd = window.get("end") instanceof Number n ? n.doubleValue() : null;
        Double effStart = start == null ? winStart : start;
        Double effEnd = end == null ? winEnd : end;
        boolean analyzed = (winStart == null || (effStart != null && effStart >= winStart))
                && (winEnd == null || (effEnd != null && effEnd <= winEnd));

        List<Row> observations = db.rows(
                "SELECT point,direction,count(*) AS packets,"
                        + "count(*) FILTER(WHERE eligible) AS eligible_packets,"
                        + "count(*) FILTER(WHERE retrans) AS retrans_observations FROM obs "
                        + "WHERE (? IS NULL OR corrected>=?) AND (? IS NULL OR corrected<?) GROUP BY point,direction",
                effStart, effStart, effEnd, effEnd);
        Map<List<String>, Row> counts = new LinkedHashMap<>();
        for (Row r : observations) {
            counts.put(List.of(r.str("point"), r.str("direction")), r);
        }
        List<Row> losses = db.rows(
                "SELECT point_a,point_b,direction,kind,count(*) AS n FROM events "
                        + "WHERE (? IS NULL OR ts>=?) AND (? IS NULL OR ts<?) GROUP BY ALL",
                effStart, effStart, effEnd, effEnd);
        List<Row> latencies = db.rows(
                "SELECT point_a,point_b,direction,quantile_cont((time_b-time_a)*1000,.95) AS p95_ms "
                        + "FROM observation_matches WHERE (? IS NULL OR time_a>=?) AND (? IS NULL OR time_a<?) GROUP BY ALL",
                effStart, effStart, effEnd, effEnd);
        Map<List<String>, Object> latency = new LinkedHashMap<>();
        for (Row r : latencies) {
            latency.put(List.of(r.str("point_a"), r.str("point_b"), r.str("direction")), r.get("p95_ms"));
        }
        List<Map<String, Object>> selected = (List<Map<String, Object>>) findingsPage(db, effStart, effEnd).get("items");
        Map<String, Long> deviceDrops = new LinkedHashMap<>();
        for (Row r : db.rows(
                "SELECT device,count(*) AS n FROM device_proofs WHERE status='confirmed_device_drop' "
                        + "AND (? IS NULL OR time>=?) AND (? IS NULL OR time<?) GROUP BY device",
                effStart, effStart, effEnd, effEnd)) {
            if (r.str("device") != null) {
                deviceDrops.put(r.str("device"), r.lng("n"));
            }
        }
        Map<String, Map<String, Object>> coverage = new LinkedHashMap<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) report.getOrDefault("coverage", List.of())) {
            coverage.put((String) c.get("point"), c);
        }
        List<Map<String, Object>> segments = new ArrayList<>();
        for (Map<String, Object> base : (List<Map<String, Object>>) report.getOrDefault("segments", List.of())) {
            String a = (String) base.get("point_a");
            String b = (String) base.get("point_b");
            String d = (String) base.get("direction");
            Row c = counts.get(List.of(a, d));
            long eligible = c != null ? c.lng("eligible_packets") : 0L;
            long packets = c != null ? c.lng("packets") : 0L;
            Map<String, Long> classes = new LinkedHashMap<>();
            for (Row r : losses) {
                if (Objects.equals(r.get("point_a"), a) && Objects.equals(r.get("point_b"), b) && Objects.equals(r.get("direction"), d)) {
                    classes.put(r.str("kind"), r.lng("n"));
                }
            }
            Double ratio = packets > 0 ? (double) eligible / (double) packets : null;
            boolean covered = isCovered(coverage.get(a), effStart, effEnd) && isCovered(coverage.get(b), effStart, effEnd);
            boolean valid = covered && analyzed && base.get("reason") == null
                    && ratio != null && ratio >= topology.minEligibleRatio;
            long lossSum = 0L;
            for (String k : LOSS_CLASSES) {
                lossSum += classes.getOrDefault(k, 0L);
            }
            List<String> findingIds = new ArrayList<>();
            for (Map<String, Object> f : selected) {
                if (Objects.equals(f.get("hop"), base.get("id"))) {
                    findingIds.add((String) f.get("id"));
                }
            }
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("id", base.get("id"));
            seg.put("point_a", a);
            seg.put("point_b", b);
            seg.put("direction", d);
            seg.put("loss_percent", valid && eligible > 0 ? 100.0 * lossSum / eligible : null);
            seg.put("p95_ms", valid ? latency.get(List.of(a, b, d)) : null);
            seg.put("classes", classes);
            seg.put("retrans_observations", c != null ? c.lng("retrans_observations") : 0L);
            seg.put("eligible_packets", eligible);
            seg.put("reason", base.get("reason") != null ? base.get("reason")
                    : (valid ? null : "No sufficient matchable observations in selected range"));
            seg.put("finding_ids", findingIds);
            segments.add(seg);
        }
        Set<String> devices = new LinkedHashSet<>();
        for (Point p : topology.points) {
            devices.add(p.device());
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String device : devices) {
            List<String> pts = new ArrayList<>();
            for (Point p : topology.points) {
                if (Objects.equals(p.device(), device)) {
                    pts.add(p.id());
                }
            }
            List<Map<String, Object>> owned = new ArrayList<>();
            for (Map<String, Object> s : segments) {
                if (pts.contains((String) s.get("point_a")) || pts.contains((String) s.get("point_b"))) {
                    owned.add(s);
                }
            }
            long obsTotal = 0L;
            long retransTotal = 0L;
            for (Row r : observations) {
                if (pts.contains(r.str("point"))) {
                    obsTotal += r.lng("packets");
                    retransTotal += r.lng("retrans_observations");
                }
            }
            long supportedLoss = 0L;
            Set<String> findingSet = new LinkedHashSet<>();
            for (Map<String, Object> s : owned) {
                if (s.get("loss_percent") != null) {
                    Map<String, Long> cls = (Map<String, Long>) s.get("classes");
                    for (String k : LOSS_CLASSES) {
                        supportedLoss += cls.getOrDefault(k, 0L);
                    }
                }
                findingSet.addAll((List<String>) s.get("finding_ids"));
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("device", device);
            node.put("confirmed_device_drops", deviceDrops.getOrDefault(device, 0L));
            node.put("points", pts);
            node.put("observations", obsTotal);
            node.put("retrans_observations", retransTotal);
            node.put("supported_loss_events", supportedLoss);
            node.put("findings", (long) findingSet.size());
            nodes.add(node);
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("start", effStart);
        scope.put("end", effEnd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("segments", segments);
        out.put("nodes", nodes);
        out.put("scope", scope);
        out.put("note", "Retransmissions and node totals are capture observations, potentially counted at multiple points; they do not assign fault. Latency carries segment clock uncertainty.");
        return out;
    }

    private static boolean isCovered(Map<String, Object> cov, Double start, Double end) {
        if (cov == null || !(cov.get("start") instanceof Number cs) || !(cov.get("end") instanceof Number ce)) {
            return false;
        }
        return (start == null || start >= cs.doubleValue()) && (end == null || end <= ce.doubleValue());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> topologyInspect(Db db, String point, String segment, Double start, Double end) {
        Map<String, Object> overlay = topologyOverlay(db, start, end);
        Map<String, Object> scope = (Map<String, Object>) overlay.get("scope");
        Double effStart = scope.get("start") instanceof Number n ? n.doubleValue() : null;
        Double effEnd = scope.get("end") instanceof Number n ? n.doubleValue() : null;
        Topology topology = loadTopology(db);
        List<Map<String, Object>> overlaySegments = (List<Map<String, Object>>) overlay.get("segments");
        List<String> points = new ArrayList<>();
        List<String> hops = new ArrayList<>();
        if (point != null && !point.isBlank()) {
            Point chosen = null;
            for (Point p : topology.points) {
                if (p.id().equals(point)) {
                    chosen = p;
                    break;
                }
            }
            if (chosen == null) {
                throw new IllegalArgumentException("Unknown capture point");
            }
            for (Point p : topology.points) {
                if (Objects.equals(p.device(), chosen.device())) {
                    points.add(p.id());
                }
            }
            for (Map<String, Object> s : overlaySegments) {
                if (points.contains((String) s.get("point_a")) || points.contains((String) s.get("point_b"))) {
                    hops.add((String) s.get("id"));
                }
            }
        } else {
            Map<String, Object> chosen = null;
            for (Map<String, Object> s : overlaySegments) {
                if (Objects.equals(s.get("id"), segment)) {
                    chosen = s;
                    break;
                }
            }
            if (chosen == null) {
                throw new IllegalArgumentException("Unknown segment");
            }
            points.add((String) chosen.get("point_a"));
            points.add((String) chosen.get("point_b"));
            hops.add(segment);
        }
        Map<String, Object> flows = flowPage(db, 0L, 50L, "", "", "bytes", effStart, effEnd, points);
        List<Map<String, Object>> allFindings = (List<Map<String, Object>>) findingsPage(db, effStart, effEnd).get("items");
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> f : allFindings) {
            if (hops.contains((String) f.get("hop"))) {
                findings.add(f);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("findings", findings);
        out.put("flows", flows);
        out.put("points", points);
        out.put("hops", hops);
        out.put("scope", scope);
        return out;
    }

    // ------------------------------------------------------------------ timeseries.py: timeseries_page

    @SuppressWarnings("unchecked")
    public static Map<String, Object> timeseriesPage(Db db) {
        Map<String, Object> report = (Map<String, Object>) settingGet(db, "report");
        if (report == null || !(report.get("timeseries") instanceof Map<?, ?> tsMeta)) {
            return Map.of("items", List.of(), "metrics", Map.of());
        }
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) tsMeta);
        out.put("items", db.rows("SELECT * FROM segment_buckets ORDER BY direction,segment,bucket"));
        return out;
    }

    // ------------------------------------------------------------------ stream_charts.py: charts, frame_evidence

    @SuppressWarnings("unchecked")
    public static Map<String, Object> charts(
            Db db,
            String flow,
            String point,
            String direction,
            Double start,
            Double end,
            long limit,
            boolean local) {
        Map<String, Object> report = (Map<String, Object>) settingGet(db, "report");
        Object rawTopology = settingGet(db, "topology");
        if (report == null || rawTopology == null) {
            return Map.of("points", List.of(), "packets", List.of(), "buckets", List.of(), "reason", "Run analysis first");
        }
        Topology topology = loadTopology(db);
        Transport.prepare(db, topology, report);
        Set<String> present = new LinkedHashSet<>();
        for (Object[] r : db.tuples("SELECT DISTINCT point FROM transport_tcp WHERE flow=?", flow)) {
            present.add((String) r[0]);
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (Point p : topology.points) {
            if (present.contains(p.id())) {
                points.add(Map.of("point", p.id(), "label", p.label()));
            }
        }
        if (points.isEmpty()) {
            return Map.of("points", List.of(), "packets", List.of(), "buckets", List.of(),
                    "reason", "No TCP observations for this conversation");
        }
        String chosenPoint = (point == null || point.isBlank()) ? (String) points.get(0).get("point") : point;
        if (!present.contains(chosenPoint)) {
            throw new IllegalArgumentException("Capture point does not belong to this TCP conversation");
        }
        Map<String, Point> pointMap = topology.pointMap();
        Map<String, Map<String, Object>> clocks = (Map<String, Map<String, Object>>) report.getOrDefault("clocks", Map.of());
        Map<String, Object> model = clocks.getOrDefault(pointMap.get(chosenPoint).captureId(), Map.of());
        Map<String, Object> reference = !topology.forward.isEmpty()
                ? clocks.getOrDefault(pointMap.get(topology.forward.get(0)).captureId(), Map.of())
                : Map.of();
        boolean aligned = model.get("offset") != null && Objects.equals(model.get("domain"), reference.get("domain"));
        Double effStart = (!aligned && !local) ? null : start;
        Double effEnd = (!aligned && !local) ? null : end;
        String timeColumn = aligned ? "corrected" : "ts";
        String dir = (direction == null || direction.isBlank()) ? "forward" : direction;
        Object[] originRow = db.one(
                "SELECT coalesce((SELECT isn FROM transport_handshake WHERE flow=? AND point=? AND direction=?),"
                        + "arg_min(seq,ts)) FROM transport_tcp WHERE flow=? AND point=? AND direction=? AND length>0",
                flow, chosenPoint, dir, flow, chosenPoint, dir);
        if (originRow == null || originRow[0] == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("points", points);
            empty.put("packets", List.of());
            empty.put("buckets", List.of());
            empty.put("reason", "No data in this direction");
            empty.put("point", chosenPoint);
            return empty;
        }
        long origin = ((Number) originRow[0]).longValue();
        Object[] handshake = db.one(
                "SELECT handshake FROM transport_handshake WHERE flow=? AND point=? AND direction=?",
                flow, chosenPoint, dir);
        String where = "flow=? AND point=? AND (? IS NULL OR " + timeColumn + ">=?) AND (? IS NULL OR " + timeColumn + "<?)";
        String packetWhere = "t.flow=? AND t.point=? AND (? IS NULL OR t." + timeColumn + ">=?) AND (? IS NULL OR t." + timeColumn + "<?)";
        List<Row> data = db.rows(
                "SELECT t.frame,t." + timeColumn + " AS time,t.raw_seq,t.raw_ack,t.length,"
                        + "CASE WHEN t.direction=? AND t.length>0 THEN 'data' ELSE 'ack' END AS kind,"
                        + "CASE WHEN t.direction=? THEN (t.seq-?+4294967296)%4294967296 ELSE (t.ack-?+4294967296)%4294967296 END AS sequence,"
                        + "t.retrans OR t.fast_retrans OR t.spurious AS retransmission,t.out_of_order,"
                        + "w.receive_window,t.indexed,t.trusted FROM transport_tcp t LEFT JOIN transport_acks w "
                        + "ON w.flow=t.flow AND w.point=t.point AND w.frame=t.frame AND w.direction=? "
                        + "WHERE " + packetWhere
                        + " AND ((t.direction=? AND t.length>0 AND (t.flags&2)=0) OR (t.direction<>? AND (t.flags&16)>0)) "
                        + "ORDER BY time,frame LIMIT ?",
                dir, dir, origin, origin, dir,
                flow, chosenPoint, effStart, effStart, effEnd, effEnd,
                dir, dir, limit);
        for (Row packet : data) {
            Long seq = packet.get("sequence") instanceof Number n ? n.longValue() : null;
            if (seq != null && seq >= 2147483648L) {
                packet.put("sequence", null);
                seq = null;
            }
            Long rw = packet.get("receive_window") instanceof Number n ? n.longValue() : null;
            packet.put("window_edge", "ack".equals(packet.get("kind")) && seq != null && rw != null ? seq + rw : null);
            if (!packet.bool("trusted")) {
                packet.put("retransmission", null);
            }
        }
        long count = db.count(
                "SELECT count(*) FROM transport_tcp WHERE " + where
                        + " AND ((direction=? AND length>0 AND (flags&2)=0) OR (direction<>? AND (flags&16)>0))",
                flow, chosenPoint, effStart, effStart, effEnd, effEnd, dir, dir);
        Object[] extent = db.one(
                "SELECT min(" + timeColumn + "),max(" + timeColumn + ") FROM transport_tcp WHERE " + where,
                flow, chosenPoint, effStart, effStart, effEnd, effEnd);
        Object[] capture = db.one(
                "SELECT min(" + timeColumn + "),max(" + timeColumn + ") FROM obs WHERE point=?",
                chosenPoint);
        double width = topology.raw != null && topology.raw.hasNonNull("bucket_seconds")
                ? topology.raw.get("bucket_seconds").asDouble(1.0)
                : 1.0;
        Double lo = effStart != null ? effStart : (extent != null && extent[0] instanceof Number n ? n.doubleValue() : null);
        Double hi = effEnd != null ? effEnd : (extent != null && extent[1] instanceof Number n ? n.doubleValue() : null);
        if (lo == null || hi == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("points", points);
            empty.put("packets", List.of());
            empty.put("buckets", List.of());
            empty.put("reason", "No observations in this range");
            empty.put("point", chosenPoint);
            return empty;
        }
        double capLo = capture != null && capture[0] instanceof Number n ? Math.max(n.doubleValue(), lo) : lo;
        double capHi = capture != null && capture[1] instanceof Number n ? Math.min(n.doubleValue(), hi) : hi;
        List<Row> buckets = db.rows(
                "WITH b AS(SELECT floor((" + timeColumn + "-?)/?)::BIGINT AS bucket,"
                        + "sum(length*8) FILTER(WHERE direction=? AND (flags&2)=0) AS bits,"
                        + "(median(rtt) FILTER(WHERE direction<>? AND trusted))*1000 AS rtt_ms,"
                        + "arg_min(frame," + timeColumn + ") FILTER(WHERE direction=? AND length>0) AS data_frame,"
                        + "arg_max(frame,rtt) FILTER(WHERE direction<>? AND rtt IS NOT NULL) AS rtt_frame "
                        + "FROM transport_tcp WHERE " + where + " GROUP BY bucket), grid AS("
                        + "SELECT unnest(range(0,least(10000,ceil((?-?)/?)::BIGINT+1))) AS bucket) "
                        + "SELECT g.bucket,?+g.bucket*? AS time,"
                        + "CASE WHEN ?+g.bucket*?>=? AND ?+(g.bucket+1)*?<=? THEN coalesce(b.bits,0)/? END AS throughput_bps,"
                        + "b.rtt_ms,b.data_frame,b.rtt_frame,"
                        + "CASE WHEN ?+g.bucket*?<? OR ?+(g.bucket+1)*?>? THEN 'partial / not capturing' ELSE 'capturing' END AS coverage "
                        + "FROM grid g LEFT JOIN b USING(bucket) ORDER BY g.bucket",
                lo, width, dir, dir, dir, dir,
                flow, chosenPoint, effStart, effStart, effEnd, effEnd,
                hi, lo, width,
                lo, width,
                lo, width, capLo, lo, width, capHi, width,
                lo, width, capLo, lo, width, capHi);
        boolean hasHandshake = handshake != null && Evidence.truthy(handshake[0]);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", points);
        out.put("point", chosenPoint);
        out.put("direction", dir);
        out.put("aligned", aligned);
        out.put("packets", data);
        out.put("buckets", buckets);
        out.put("total", count);
        out.put("bucket_seconds", width);
        out.put("reason", null);
        out.put("clock_uncertainty_ms", model.get("uncertainty_ms"));
        out.put("note", aligned
                ? "Clock-corrected timestamps; " + (hasHandshake
                        ? "SYN-anchored sequence bytes."
                        : "Sequence origin is first observed byte; handshake/window scale unknown.")
                : "Local unaligned clock: brush filters this chart point only; cross-point time comparison is unknown.");
        out.put("sampling_note", data.size() + " of " + count
                + " packet points shown. Rate/RTT buckets use all indexed observations; narrow the time brush for omitted packet evidence.");
        return out;
    }

    public static List<Map<String, Object>> frameEvidence(Db db, String flow, String point, List<Long> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        return Evidence.evidence(
                db,
                "o.flow=? AND o.point=? AND o.frame IN(SELECT unnest(?::BIGINT[]))",
                Arrays.asList(flow, point, new ArrayList<>(frames)),
                frames.size());
    }

    public static Map<String, Object> framesPage(
            Db db, String flow, String point, List<Long> frames, long limit, boolean includeRawPrefix) {
        if (settingGet(db, "report") == null) {
            return Map.of("items", List.of(), "total", 0L);
        }
        String f = (flow == null || flow.isBlank()) ? null : flow;
        String p = (point == null || point.isBlank()) ? null : point;
        List<Map<String, Object>> items;
        if (frames != null && !frames.isEmpty() && f != null && p != null) {
            items = Evidence.evidence(
                    db,
                    "o.flow=? AND o.point=? AND o.frame IN(SELECT unnest(?::BIGINT[]))",
                    Arrays.asList(f, p, new ArrayList<>(frames)),
                    frames.size(),
                    includeRawPrefix);
        } else if (frames != null && !frames.isEmpty()) {
            items = Evidence.evidence(
                    db,
                    "(? IS NULL OR o.flow=?) AND (? IS NULL OR o.point=?) AND o.frame IN(SELECT unnest(?::BIGINT[]))",
                    Arrays.asList(f, f, p, p, new ArrayList<>(frames)),
                    (int) Math.min(200, Math.max(1, frames.size() * 4L)),
                    includeRawPrefix);
        } else {
            items = Evidence.evidence(
                    db,
                    "(? IS NULL OR o.flow=?) AND (? IS NULL OR o.point=?)",
                    Arrays.asList(f, f, p, p),
                    (int) Math.min(200, Math.max(1, limit)),
                    includeRawPrefix);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", (long) items.size());
        return out;
    }

    public static Map<String, Object> diagnosis(Db db, String flow) {
        String f = (flow == null || flow.isBlank()) ? null : flow;
        return Transport.profiles(db, f, true);
    }

    // ------------------------------------------------------------------ waterfall.py + proxy_waterfall.py

    @SuppressWarnings("unchecked")
    public static Map<String, Object> waterfall(Db db, String flow, Double start, Double end, int requestIndex) {
        Map<String, Object> report = (Map<String, Object>) settingGet(db, "report");
        Object rawTopology = settingGet(db, "topology");
        if (report == null || rawTopology == null) {
            return Map.of("items", List.of(), "requests", List.of(), "reason", "Run analysis first");
        }
        Topology topology = loadTopology(db);
        Map<String, Object> proxies = (Map<String, Object>) report.getOrDefault("proxies", Map.of());
        boolean hasProxyTx = Evidence.truthy(proxies.get("transactions"));
        boolean hasNonF5Proxy = topology.points.stream()
                .anyMatch(p -> "full_proxy".equals(p.translation()) && !"f5".equals(p.vendor()));
        if (hasProxyTx && hasNonF5Proxy) {
            return proxyWaterfall(db, report, topology, flow, start, end, requestIndex);
        }
        List<Row> packets = db.rows(
                "SELECT * FROM obs WHERE flow=? AND proto='TCP' ORDER BY ts,point,frame LIMIT ?",
                flow, WATERFALL_LIMIT + 1);
        if (packets.size() > WATERFALL_LIMIT) {
            return Map.of("items", List.of(), "requests", List.of(),
                    "reason", "Flow exceeds the 20,000-observation waterfall budget");
        }
        Map<String, Point> points = topology.pointMap();
        List<String> forward = topology.forward;
        List<String> reverse = topology.reverse.isEmpty() ? Topology.reversed(forward) : topology.reverse;
        if (forward.size() < 2) {
            return Map.of("items", List.of(), "requests", List.of(),
                    "reason", "An ordered path with at least two points is required");
        }
        String client = forward.get(0);
        String server = forward.get(forward.size() - 1);
        Map<String, List<Row>> byPoint = new LinkedHashMap<>();
        for (Point p : topology.points) {
            byPoint.put(p.id(), new ArrayList<>());
        }
        for (Row pkt : packets) {
            byPoint.computeIfAbsent(pkt.str("point"), k -> new ArrayList<>()).add(pkt);
        }
        boolean fullProxy = topology.points.stream().anyMatch(p -> "full_proxy".equals(p.translation()));
        String fallback = fullProxy
                ? "unknown (full proxy: request correlation unavailable)"
                : "Missing or unmatchable endpoint frame";
        boolean hasByteRanges = db.count("SELECT count(*) FROM byte_flows WHERE flow=?", flow) > 0;
        Set<List<Object>> refKeys = new LinkedHashSet<>();

        List<Map<String, Object>> items = new ArrayList<>();
        Row syn = byPoint.getOrDefault(client, List.of()).stream()
                .filter(p -> (p.lng("flags") & 18L) == 2L && inWindow(p, start, end))
                .findFirst().orElse(null);
        Map<String, Row> syns = tracePacket(db, packets, hasByteRanges, syn, false);
        double synServerTs = syn != null ? syns.getOrDefault(server, syn).dbl("ts") : 0.0;
        Row sa = syn == null ? null : byPoint.getOrDefault(server, List.of()).stream()
                .filter(p -> (p.lng("flags") & 18L) == 18L
                        && p.lng("ack") == Math.floorMod(syn.lng("seq") + 1L, MOD)
                        && p.dbl("ts") >= synServerTs)
                .findFirst().orElse(null);
        Map<String, Row> sas = tracePacket(db, packets, hasByteRanges, sa, false);
        double saClientTs = sa != null ? sas.getOrDefault(client, sa).dbl("ts") : 0.0;
        Row ack = (syn == null || sa == null) ? null : byPoint.getOrDefault(client, List.of()).stream()
                .filter(p -> (p.lng("flags") & 18L) == 16L
                        && p.lng("ack") == Math.floorMod(sa.lng("seq") + 1L, MOD)
                        && p.lng("seq") == Math.floorMod(syn.lng("seq") + 1L, MOD)
                        && p.dbl("ts") >= saClientTs)
                .findFirst().orElse(null);
        Double origin = syn != null && syn.get("corrected") instanceof Number n ? n.doubleValue() : null;
        List<Map<String, Object>> bars = new ArrayList<>();
        bars.addAll(buildLegs(db, packets, hasByteRanges, report, points, refKeys, fallback, syn, forward, "SYN", "forward", origin, false));
        bars.add(buildBar(report, points, refKeys, fallback, syns.get(server), sa,
                "Server SYN → SYN/ACK turnaround", "server_turnaround", "reverse", server, server, origin, null));
        bars.addAll(buildLegs(db, packets, hasByteRanges, report, points, refKeys, fallback, sa, reverse, "SYN/ACK", "reverse", origin, false));
        bars.add(buildBar(report, points, refKeys, fallback, sas.get(client), ack,
                "Client SYN/ACK → ACK turnaround", "client_turnaround", "forward", client, client, origin, null));
        bars.addAll(buildLegs(db, packets, hasByteRanges, report, points, refKeys, fallback, ack, forward, "ACK", "forward", origin, false));
        Map<String, Object> hsItem = new LinkedHashMap<>();
        hsItem.put("kind", "handshake");
        hsItem.put("title", "TCP three-way handshake");
        hsItem.put("bars", bars);
        hsItem.put("origin", origin);
        items.add(hsItem);

        List<Row> sourcePool = !byPoint.getOrDefault(server, List.of()).isEmpty()
                ? byPoint.get(server) : byPoint.getOrDefault(client, List.of());
        Map<Long, Row> dedupCandidates = new LinkedHashMap<>();
        for (int i = sourcePool.size() - 1; i >= 0; i--) {
            Row p = sourcePool.get(i);
            if ("forward".equals(p.str("direction")) && p.lng("length") > 0L
                    && matchesAsciiPrefix(p.str("prefix"), HTTP_REQUEST)
                    && inWindow(p, start, end)) {
                dedupCandidates.put(p.lng("seq"), p);
            }
        }
        List<Row> candidates = new ArrayList<>(dedupCandidates.values());
        candidates.sort(Comparator.comparingDouble(p -> p.dbl("ts")));
        List<Map<String, Object>> choices = new ArrayList<>();
        for (int i = 0; i < Math.min(candidates.size(), 100); i++) {
            Row p = candidates.get(i);
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("index", (long) i);
            ch.put("label", firstLineAscii(p.str("prefix")));
            ch.put("time", p.get("corrected"));
            choices.add(ch);
        }
        if (!candidates.isEmpty() && requestIndex >= 0 && requestIndex < Math.min(candidates.size(), 100)) {
            Row req = candidates.get(requestIndex);
            List<Row> local = new ArrayList<>();
            for (Row p : byPoint.getOrDefault(req.str("point"), List.of())) {
                if ("forward".equals(p.str("direction")) && p.lng("length") > 0L && p.dbl("ts") >= req.dbl("ts")) {
                    local.add(p);
                }
            }
            Object[] sr = Transport.requestSize(req, local);
            Integer size = (Integer) sr[0];
            String reason = (String) sr[1];
            Row response = null;
            for (Row p : byPoint.getOrDefault(server, List.of())) {
                if ("reverse".equals(p.str("direction"))
                        && p.dbl("ts") > req.dbl("ts")
                        && matchesAsciiPrefix(p.str("prefix"), HTTP_RESPONSE)
                        && size != null
                        && Math.floorMod(p.lng("ack") - req.lng("seq"), MOD) >= size
                        && Math.floorMod(p.lng("ack") - req.lng("seq"), MOD) < MOD / 2) {
                    response = p;
                    break;
                }
            }
            if (response != null && requestIndex + 1 < candidates.size()
                    && candidates.get(requestIndex + 1).dbl("ts") < response.dbl("ts")) {
                reason = "HTTP pipelining/overlap: response association unknown";
            }
            Row last = (size != null && response != null)
                    ? Transport.completion(local, req, size, response.dbl("ts"))
                    : null;
            Row firstRequest = null;
            for (Row p : byPoint.getOrDefault(client, List.of())) {
                if ("forward".equals(p.str("direction")) && p.lng("seq") == req.lng("seq") && p.lng("length") > 0L) {
                    firstRequest = p;
                    break;
                }
            }
            Row originRow = firstRequest != null ? firstRequest : req;
            Double reqOrigin = originRow.get("corrected") instanceof Number n ? n.doubleValue() : null;
            Map<String, Row> tracedLast = tracePacket(db, packets, hasByteRanges, last, false);
            List<Map<String, Object>> httpBars = new ArrayList<>();
            httpBars.add(buildBar(report, points, refKeys, fallback, firstRequest, tracedLast.get(client),
                    "Client request transmission / retry span", "request_transmission", "forward", client, client, reqOrigin, null));
            httpBars.addAll(buildLegs(db, packets, hasByteRanges, report, points, refKeys, fallback, last, forward,
                    "Request completion", "forward", reqOrigin, false));
            httpBars.add(buildBar(report, points, refKeys, fallback,
                    server.equals(req.str("point")) ? last : null, response,
                    "Server processing: last request byte → first response byte", "server_processing", "reverse",
                    server, server, reqOrigin, reason));
            httpBars.addAll(buildLegs(db, packets, hasByteRanges, report, points, refKeys, fallback, response, reverse,
                    "First response byte", "reverse", reqOrigin, true));
            Map<String, Object> httpItem = new LinkedHashMap<>();
            httpItem.put("kind", "http");
            httpItem.put("title", choices.get(requestIndex).get("label"));
            httpItem.put("bars", httpBars);
            httpItem.put("origin", reqOrigin);
            httpItem.put("note", "Request completion and time to first response byte; not response-body download time. Up to 100 requests selectable. Chunked requests and pipelining stay unknown.");
            items.add(httpItem);
        }
        if (!refKeys.isEmpty()) {
            Map<List<Object>, Map<String, Object>> lookup = Proxies.frameLookup(db, refKeys);
            for (Map<String, Object> item : items) {
                for (Map<String, Object> stage : (List<Map<String, Object>>) item.get("bars")) {
                    List<List<Object>> rawKeys = (List<List<Object>>) stage.get("evidence");
                    List<Map<String, Object>> resolved = new ArrayList<>();
                    for (List<Object> k : rawKeys) {
                        if (lookup.containsKey(k)) {
                            resolved.add(lookup.get(k));
                        }
                    }
                    stage.put("evidence", resolved);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("requests", choices);
        out.put("reason", !candidates.isEmpty() ? null
                : "No captured cleartext HTTP/1.x request line in this window; TLS is not decoded");
        return out;
    }

    private static boolean inWindow(Row p, Double start, Double end) {
        Double c = p.get("corrected") instanceof Number n ? n.doubleValue() : null;
        return (start == null || (c != null && c >= start)) && (end == null || (c != null && c < end));
    }

    private static Map<String, Row> tracePacket(
            Db db, List<Row> packets, boolean hasByteRanges, Row seed, boolean first) {
        if (seed == null) {
            return Map.of();
        }
        Map<String, Row> found = new LinkedHashMap<>();
        for (Row p : packets) {
            if (Objects.equals(p.get("packet_key"), seed.get("packet_key")) && p.bool("eligible")) {
                found.put(p.str("point"), p);
            }
        }
        if (hasByteRanges) {
            List<Object[]> peers = db.tuples(
                    "SELECT DISTINCT b.point,b.source_frame FROM byte_obs a JOIN byte_obs b "
                            + "USING(packet_key) WHERE a.point=? AND a.source_frame=? AND a.is_atom AND b.is_atom "
                            + "AND a.eligible AND b.eligible AND (NOT ? OR (a.lo<=? AND a.hi>?))",
                    seed.get("point"), seed.get("frame"), first, seed.get("seq"), seed.get("seq"));
            Set<List<Object>> peerSet = new LinkedHashSet<>();
            for (Object[] pr : peers) {
                peerSet.add(Arrays.asList(pr[0], pr[1]));
            }
            for (Row p : packets) {
                if (p.bool("eligible") && peerSet.contains(Arrays.asList(p.get("point"), p.get("frame")))) {
                    String pt = p.str("point");
                    if (!found.containsKey(pt)
                            || (first ? p.dbl("ts") < found.get(pt).dbl("ts") : p.dbl("ts") > found.get(pt).dbl("ts"))) {
                        found.put(pt, p);
                    }
                }
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildBar(
            Map<String, Object> report,
            Map<String, Point> points,
            Set<List<Object>> refKeys,
            String fallback,
            Row a,
            Row b,
            String label,
            String kind,
            String direction,
            String pa,
            String pb,
            Double origin,
            String initialReason) {
        Map<String, Map<String, Object>> clocks = (Map<String, Map<String, Object>>) report.getOrDefault("clocks", Map.of());
        Map<String, Object> ma = clocks.getOrDefault(points.get(pa).captureId(), Map.of());
        Map<String, Object> mb = clocks.getOrDefault(points.get(pb).captureId(), Map.of());
        String reason = initialReason;
        Double uncertainty = null;
        if (("full_proxy".equals(points.get(pa).translation()) || "full_proxy".equals(points.get(pb).translation()))
                && !pa.equals(pb)) {
            reason = "unknown (full proxy: request correlation unavailable)";
        }
        if (a == null || b == null || !a.bool("eligible") || !b.bool("eligible")) {
            if (reason == null) {
                reason = fallback;
            }
        }
        Double duration = null;
        String finalLabel = label;
        if (a != null && b != null && a.get("corrected") instanceof Number ca && b.get("corrected") instanceof Number cb) {
            if (ma.get("uncertainty") instanceof Number ua && mb.get("uncertainty") instanceof Number ub) {
                uncertainty = Objects.equals(a.get("capture_id"), b.get("capture_id"))
                        ? 0.0
                        : 1000.0 * (ua.doubleValue() + ub.doubleValue());
            }
            if (uncertainty != null) {
                double ja = ma.get("timestamp_jitter") instanceof Number n ? n.doubleValue() : 0.0;
                double jb = mb.get("timestamp_jitter") instanceof Number n ? n.doubleValue() : 0.0;
                uncertainty += 1000.0 * (ja + jb);
            }
            if (uncertainty == null || "unknown".equals(ma.get("confidence")) || "unknown".equals(mb.get("confidence"))) {
                if (reason == null) {
                    reason = "Clock alignment unreliable or uncertainty unverified";
                }
            }
            duration = (cb.doubleValue() - ca.doubleValue()) * 1000.0;
            if (duration < -Math.max(0.001, uncertainty != null ? uncertainty : 0.0)) {
                if (reason == null) {
                    reason = "Clock/order inconsistency: negative elapsed time beyond timestamp uncertainty";
                }
            } else if (duration < 0.0) {
                duration = 0.0;
                finalLabel += " (within timestamp noise)";
            }
        } else {
            if (reason == null) {
                reason = "Clock alignment unreliable";
            }
        }
        for (Map<String, Object> s : (List<Map<String, Object>>) report.getOrDefault("segments", List.of())) {
            if (Objects.equals(s.get("point_a"), pa) && Objects.equals(s.get("point_b"), pb)
                    && Objects.equals(s.get("direction"), direction)) {
                if (s.get("reason") != null && reason == null) {
                    reason = (String) s.get("reason");
                }
                break;
            }
        }
        List<List<Object>> evKeys = new ArrayList<>();
        for (Row pkt : new Row[] {a, b}) {
            if (pkt != null) {
                List<Object> k = Arrays.asList(pkt.get("point"), pkt.get("frame"));
                refKeys.add(k);
                if (!evKeys.contains(k)) {
                    evKeys.add(k);
                }
            }
        }
        Double startMs = (a != null && a.get("corrected") instanceof Number ca && origin != null && reason == null)
                ? (ca.doubleValue() - origin) * 1000.0
                : null;
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("label", finalLabel);
        bar.put("kind", kind);
        bar.put("direction", direction);
        bar.put("point_a", pa);
        bar.put("point_b", pb);
        bar.put("duration_ms", reason != null ? null : duration);
        bar.put("uncertainty_ms", uncertainty);
        bar.put("start_ms", startMs);
        bar.put("reason", reason);
        bar.put("evidence", evKeys);
        bar.put("note", pa.equals(pb)
                ? "Same-capture offset cancels; timestamp precision and residual drift remain approximate"
                : "Clock-corrected estimate; coalesced-frame timestamps are not individual wire timestamps");
        return bar;
    }

    private static List<Map<String, Object>> buildLegs(
            Db db,
            List<Row> packets,
            boolean hasByteRanges,
            Map<String, Object> report,
            Map<String, Point> points,
            Set<List<Object>> refKeys,
            String fallback,
            Row seed,
            List<String> path,
            String label,
            String direction,
            Double origin,
            boolean first) {
        Map<String, Row> matched = tracePacket(db, packets, hasByteRanges, seed, first);
        List<Map<String, Object>> legs = new ArrayList<>();
        for (int i = 0; i + 1 < path.size(); i++) {
            String a = path.get(i);
            String b = path.get(i + 1);
            String kind = Objects.equals(points.get(a).device(), points.get(b).device()) ? "device_dwell" : "link";
            legs.add(buildBar(report, points, refKeys, fallback, matched.get(a), matched.get(b),
                    label + ": " + points.get(a).label() + " → " + points.get(b).label(),
                    kind, direction, a, b, origin, null));
        }
        return legs;
    }

    /** The reference dispatches to {@code proxy_waterfall.waterfall}; the engine port is {@link Proxies#waterfall}. */
    private static Map<String, Object> proxyWaterfall(
            Db db,
            Map<String, Object> report,
            Topology topology,
            String flow,
            Double start,
            Double end,
            int requestIndex) {
        return Proxies.waterfall(db, report, topology, flow, start, end, requestIndex);
    }

    private static boolean matchesAsciiPrefix(String hex, Pattern pattern) {
        if (hex == null || hex.isEmpty()) {
            return false;
        }
        String text = new String(HexFormat.of().parseHex(hex), StandardCharsets.ISO_8859_1);
        return pattern.matcher(text).find();
    }

    private static String firstLineAscii(String hex) {
        if (hex == null || hex.isEmpty()) {
            return "";
        }
        byte[] bytes = HexFormat.of().parseHex(hex);
        int end = bytes.length;
        for (int i = 0; i + 1 < bytes.length; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
                end = i;
                break;
            }
        }
        StringBuilder sb = new StringBuilder(end);
        for (int i = 0; i < end; i++) {
            int b = bytes[i] & 0xFF;
            sb.append(b >= 32 && b < 127 ? (char) b : '\uFFFD');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ field_diff.py: field_diffs, packet_diff

    public static Map<String, Object> fieldDiffs(Db db, Topology topology, String packetKey, int limit) {
        return FieldDiff.fieldDiffs(db, topology, packetKey, limit);
    }

    public static Map<String, Object> packetDiff(Db db, String packetKey) {
        String key = (packetKey == null || packetKey.isBlank()) ? null : packetKey;
        return FieldDiff.packetDiff(db, key);
    }

    // ------------------------------------------------------------------ dns.py: dns_page

    @SuppressWarnings("unchecked")
    public static Map<String, Object> dnsPage(
            Db db, String point, String resolver, Double start, Double end, long offset, long limit) {
        Map<String, Object> report = (Map<String, Object>) settingGet(db, "report");
        if (report == null) {
            return Map.of("items", List.of(), "summary", List.of(), "total", 0L, "reason", "Run analysis first");
        }
        Topology topology = loadTopology(db);
        return Dns.page(db, topology, report,
                (point == null || point.isBlank()) ? null : point,
                (resolver == null || resolver.isBlank()) ? null : resolver,
                start, end, offset, limit);
    }

    // ------------------------------------------------------------------ export.py / engine_export.py / reference.py

    public static Map<String, Object> exportData(Db db, boolean findingsOnly) {
        return Export.exportData(db, findingsOnly);
    }

    public static String exportReport(Db db, String format) {
        return Export.exportReport(db, format);
    }

    public static byte[] exportTables(Db db, String format) {
        return EngineExport.exportTables(db, format);
    }

    public static Map<String, Object> compareReference(Object data, Object reference) {
        return ReferenceCompare.compareReference(data, reference);
    }

    public static Map<String, Object> referenceTemplate(Object report) {
        return ReferenceCompare.referenceTemplate(report);
    }

    public static Map<String, Object> compareRuns(Object before, Object after) {
        return ReferenceCompare.compareRuns(before, after);
    }

    /** {@code app.reference_export}: the reference template of the saved report. */
    public static Map<String, Object> referenceTemplate(Db db) {
        Object saved = settingGet(db, "report");
        if (!Evidence.truthy(saved)) {
            throw new IllegalArgumentException("Run analysis first");
        }
        return ReferenceCompare.referenceTemplate(saved);
    }

    /**
     * {@code app.compare}: the saved report against a reference template and/or a previous analysis export.
     * Either input may be null; at least one is required.
     */
    public static Map<String, Object> compare(Db db, Object reference, Object against) {
        Object saved = settingGet(db, "report");
        if (!Evidence.truthy(saved)) {
            throw new IllegalArgumentException("Run analysis first");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (reference != null) {
            result.putAll(ReferenceCompare.compareReference(saved, reference));
        }
        if (against != null) {
            if (!(against instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Analysis export must be an object");
            }
            result.put("diff", ReferenceCompare.compareRuns(against, saved));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Choose reference and/or against");
        }
        return result;
    }

    // ------------------------------------------------------------------ security.py: session_evidence

    /** Representative frames of one lifecycle bucket ({@code security.session_evidence}). */
    public static Map<String, Object> sessionEvidence(Db db, String point, double start, double end) {
        if (!(start < end)) {
            throw new IllegalArgumentException("Start must precede end");
        }
        Object saved = settingGet(db, "report");
        Set<Object> known = new LinkedHashSet<>();
        if (saved instanceof Map<?, ?> report && report.get("sessions") instanceof Map<?, ?> sessions
                && sessions.get("points") instanceof List<?> points) {
            for (Object p : points) {
                if (p instanceof Map<?, ?> m) {
                    known.add(m.get("point"));
                }
            }
        }
        if (!Evidence.truthy(saved) || !known.contains(point)) {
            throw new IllegalArgumentException("Unknown lifecycle point or no saved analysis");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evidence", Evidence.evidence(db,
                "o.point=? AND (o.corrected>=? AND o.corrected<? OR EXISTS(\n"
                        + "            SELECT 1 FROM session_lifecycle s WHERE s.point=o.point AND s.syn_frame=o.frame\n"
                        + "            AND s.start<? AND s.half_open_end>=?))",
                Arrays.asList(point, start, end, end, start), 64));
        out.put("note", "Representative bucket frames and carried half-open SYNs; stored session_buckets use the "
                + "complete population.");
        return out;
    }
}
