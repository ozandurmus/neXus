package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static com.securityexpert.nexus.ui2.packetanalysis.engine.PyOps.dict;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reference {@code incident.py}: presentation projections of the engine tables, not a second analysis engine. */
final class Incident {
    private Incident() {
    }

    private static List<Object> key(Object point, Object frame) {
        return Aggregates.key(point, frame);
    }

    @SuppressWarnings("unchecked")
    static void buildIncident(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Object> host = te.hostReport;
        Map<String, Object> cycles = te.cycleReport;
        List<String> hosts = new ArrayList<>();
        for (Topology.Point p : topology.points) {
            if (p.hostCapture()) {
                hosts.add(p.id());
            }
        }
        List<Row> timeline = new ArrayList<>();
        if (!hosts.isEmpty() && db.count(Sql.get("incident", "host_path_series_exists")) > 0) {
            List<String> retransPoints = !hosts.isEmpty() ? hosts
                    : new ArrayList<>(topology.forward.subList(0, Math.min(1, topology.forward.size())));
            timeline = db.rows(Sql.get("incident", "timeline"), hosts, retransPoints);
        }
        Map<Object, List<Object>> boundaryRefs = new LinkedHashMap<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) PyOps.get(host, "items", new ArrayList<>())) {
            for (Map<String, Object> r : (List<Map<String, Object>>) item.get("series")) {
                boundaryRefs.computeIfAbsent(r.get("start"), k -> new ArrayList<>()).addAll((List<Object>) r.get("evidence"));
            }
        }
        Map<Object, List<List<Object>>> txKeys = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("incident", "tx_keys"), hosts)) {
            txKeys.put(r.get("start"), new ArrayList<>(List.of(key(r.get("point_a"), r.get("fa")), key(r.get("point_b"), r.get("fb")))));
        }
        for (Row r : timeline) {
            List<List<Object>> keys = new ArrayList<>();
            keys.add(key(r.remove("point"), r.remove("frame")));
            keys.addAll(txKeys.getOrDefault(r.get("start"), List.of()));
            r.put("_keys", keys);
        }
        Aggregates.attach(db, timeline);
        for (Row r : timeline) {
            List<Object> ev = new ArrayList<>((List<Object>) PyOps.get(r, "evidence", new ArrayList<>()));
            ev.addAll(boundaryRefs.getOrDefault(r.get("start"), List.of()));
            r.put("evidence", new ArrayList<>(ev.subList(0, Math.min(8, ev.size()))));
        }
        Object window = host.get("window");
        db.exec(Sql.get("incident", "cycle_details"));
        List<Row> detail = db.rows(Sql.get("incident", "detail"));
        Map<List<Object>, Object> old = new LinkedHashMap<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) cycles.get("top")) {
            old.put(Arrays.asList(r.get("cycle_id"), r.get("point")), PyOps.get(r, "evidence", new ArrayList<>()));
        }
        for (Row r : detail) {
            Object ev = old.get(Arrays.asList(r.get("cycle_id"), r.get("point")));
            r.put("evidence", ev != null ? ev : new ArrayList<>());
        }
        cycles.put("top", detail);
        List<Map<String, Object>> series = (List<Map<String, Object>>) cycles.get("series");
        for (Map<String, Object> r : series) {
            r.put("_keys", new ArrayList<>(List.of(key(r.get("point"), r.get("evidence_frame")))));
        }
        Aggregates.attach(db, series);
        List<Row> topPairs = db.rows(Sql.get("incident", "top_pairs"));
        for (Row r : topPairs) {
            r.put("_keys", new ArrayList<>(List.of(key(r.get("point_a"), r.get("frame_a")), key(r.get("point_b"), r.get("frame_b")))));
        }
        Aggregates.attach(db, topPairs);
        List<Map<String, Object>> bars = new ArrayList<>();
        LinkedHashSet<Object> cycleIds = new LinkedHashSet<>();
        for (Row r : detail) {
            cycleIds.add(r.get("cycle_id"));
        }
        for (Object cycleId : cycleIds) {
            List<Row> points = new ArrayList<>();
            for (Row r : detail) {
                if (Objects.equals(r.get("cycle_id"), cycleId)) {
                    points.add(r);
                }
            }
            List<Row> pair = new ArrayList<>();
            for (Row r : topPairs) {
                if (Objects.equals(r.get("cycle_id"), cycleId)) {
                    pair.add(r);
                }
            }
            Row local = points.stream().filter(r -> hosts.contains(r.get("point"))).findFirst().orElse(null);
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Row r : pair) {
                parts.add(dict("name", r.get("hop") + " " + r.get("component"), "component", r.get("component"), "ms", r.get("added_ms"),
                        "uncertainty_ms", r.get("uncertainty_ms"), "reason", r.get("reason"), "evidence", r.get("evidence")));
            }
            List<Row> expected = db.rows(Sql.get("incident", "edges"));
            LinkedHashSet<Object> known = new LinkedHashSet<>();
            for (Row r : pair) {
                known.add(r.get("hop"));
            }
            for (Row edge : expected) {
                if (known.contains(edge.get("hop"))) {
                    continue;
                }
                String component = "forward".equals(edge.get("direction")) ? "RX" : "TX";
                List<Object> ev = new ArrayList<>();
                for (Row r : points) {
                    if (Objects.equals(r.get("point"), edge.get("a")) || Objects.equals(r.get("point"), edge.get("b"))) {
                        ev.addAll((List<Object>) r.get("evidence"));
                    }
                }
                parts.add(dict("name", edge.get("hop") + " " + component, "component", component, "ms", null, "uncertainty_ms",
                        edge.get("uncertainty_ms"), "reason", "No paired cycle at this edge; component unknown", "evidence", ev));
            }
            if (local != null) {
                parts.add(dict("name", "server-local", "component", "local", "ms", local.get("reason") == null ? local.get("turnaround_ms") : null,
                        "uncertainty_ms", 0L, "reason", local.get("reason"), "evidence", local.get("evidence")));
            }
            Object time = null;
            Object total = null;
            for (Row r : points) {
                if (r.get("request_first") != null) {
                    time = time == null ? r.get("request_first") : PyOps.min(time, r.get("request_first"));
                }
                if (r.get("cycle_ms") != null) {
                    total = total == null ? r.get("cycle_ms") : PyOps.max(total, r.get("cycle_ms"));
                }
            }
            bars.add(dict("cycle_id", cycleId, "flow", points.get(0).get("flow"), "time", time, "total_ms", total, "rx_ms",
                    componentSum(parts, "RX"), "tx_ms", componentSum(parts, "TX"), "parts", parts));
        }
        db.exec(Sql.get("incident", "cycle_flow_detail"));
        cycles.put("flow_ports", db.rows(Sql.get("incident", "flow_detail_rows")));
        List<Row> quality = db.rows(Sql.get("incident", "quality"));
        Map<List<Object>, Object> offloadIndex = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("incident", "offload"))) {
            offloadIndex.put(Arrays.asList(r.get("point_a"), r.get("point_b"), r.get("direction"), r.get("start")), r.get("offload_mismatch"));
        }
        Map<List<Object>, Row> qr = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("incident", "quality_refs"))) {
            qr.put(Arrays.asList(r.get("point_a"), r.get("point_b"), r.get("direction"), r.get("start")), r);
        }
        for (Row r : quality) {
            List<Object> k = Arrays.asList(r.get("point_a"), r.get("point_b"), r.get("direction"), r.get("start"));
            Object offload = offloadIndex.containsKey(k) ? offloadIndex.get(k) : 0L;
            r.put("offload_mismatch", PyOps.min(r.get("unknown"), offload));
            Object[][] counts = {{"capture_miss_percent", r.get("capture_misses")}, {"loss_percent", r.get("loss")},
                {"offload_mismatch_percent", r.get("offload_mismatch")},
                {"unknown_percent", PyOps.sub(r.get("unknown"), r.get("offload_mismatch"))}};
            for (Object[] nc : counts) {
                r.put((String) nc[0], PyOps.truthy(r.get("observations"))
                        ? (Object) PyOps.div(PyOps.mul(100L, nc[1]), r.get("observations")) : null);
            }
            Map<String, Object> e = qr.containsKey(k) ? qr.get(k) : Map.of();
            r.put("_keys", new ArrayList<>(List.of(key(r.get("point_a"), e.get("fa")), key(r.get("point_b"), e.get("fb")))));
        }
        Aggregates.attach(db, quality);
        List<Row> deliveryRows = db.rows(Sql.get("incident", "delivery_rows"));
        for (Row r : deliveryRows) {
            Object fa = r.remove("fa");
            Object fb = r.remove("fb");
            r.put("_keys", new ArrayList<>(List.of(key(r.get("point_a"), fa), key(r.get("point_b"), fb))));
        }
        Aggregates.attach(db, deliveryRows);
        List<Map<String, Object>> holdRelease = new ArrayList<>();
        for (Map<String, Object> f : ctx.findings) {
            if ("hold_release".equals(f.get("type"))) {
                holdRelease.add(f);
            }
        }
        List<Map<String, Object>> items = (List<Map<String, Object>>) PyOps.get(host, "items", new ArrayList<>());
        te.incident = dict("timeline", timeline, "cycle_bars", bars, "delivery", deliveryRows, "quality", quality, "hold_release",
                holdRelease, "window", window, "headline", items.isEmpty() ? "Host-path aggregate not computed" : items.get(0).get("headline"),
                "loss_groups", PyOps.get(host, "loss_groups", new ArrayList<>()), "scope",
                "Saved analysis window. Each chart selection filters the shared time range; baseline and aggregate headline remain full-window.",
                "quality_note",
                "Offload mismatch means an unknown copy pair with different segmentation and a >1500-byte capture unit. Other unknowns remain separate; capture miss is supported by later/ACK evidence. Rates use observed physical pair units, not bytes. Chart rows are capped at 10,000; engine exports retain all rows.");
    }

    private static Object componentSum(List<Map<String, Object>> parts, String component) {
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> r : parts) {
            if (component.equals(r.get("component"))) {
                if (r.get("ms") == null) {
                    return null;
                }
                values.add(r.get("ms"));
            }
        }
        return PyOps.sum(values);
    }
}
