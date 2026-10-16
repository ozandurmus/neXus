package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static com.securityexpert.nexus.ui2.packetanalysis.engine.PyOps.dict;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reference {@code aggregates.py} and {@code incident.attach}: stable reference metrics over existing tables. */
final class Aggregates {
    private Aggregates() {
    }

    /** {@code incident.attach}: replaces each record's {@code _keys} with its evidence references. */
    @SuppressWarnings("unchecked")
    static <T extends Map<String, Object>> List<T> attach(Db db, List<T> records) {
        List<List<List<Object>>> bundles = new ArrayList<>();
        for (T r : records) {
            Object keys = r.remove("_keys");
            bundles.add(keys == null ? new ArrayList<>() : (List<List<Object>>) keys);
        }
        java.util.TreeSet<List<Object>> keys = new java.util.TreeSet<>(Cycles::compareKey);
        for (List<List<Object>> bundle : bundles) {
            for (List<Object> k : bundle) {
                if (PyOps.truthy(k.get(0)) && PyOps.truthy(k.get(1))) {
                    keys.add(k);
                }
            }
        }
        List<Map<String, Object>> refs = keys.isEmpty() ? new ArrayList<>()
                : Evidence.evidence(db, "(o.point,o.frame) IN (SELECT unnest(?::VARCHAR[]),unnest(?::BIGINT[]))",
                        Cycles.pointsAndFrames(keys), keys.size(), false);
        Map<List<Object>, Map<String, Object>> lookup = new LinkedHashMap<>();
        for (Map<String, Object> r : refs) {
            lookup.put(Arrays.asList(r.get("point"), r.get("frame")), r);
        }
        for (int i = 0; i < records.size(); i++) {
            List<Map<String, Object>> ev = new ArrayList<>();
            for (List<Object> k : bundles.get(i)) {
                Map<String, Object> ref = lookup.get(Arrays.asList(k.get(0), k.get(1)));
                if (ref != null) {
                    ev.add(ref);
                }
            }
            records.get(i).put("evidence", ev);
        }
        return records;
    }

    static List<Object> key(Object point, Object frame) {
        return Arrays.asList(point, frame);
    }

    @SuppressWarnings("unchecked")
    static void addAggregates(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Object> hosts = te.hostReport;
        Map<String, Object> cycles = te.cycleReport;
        Map<String, Object> onsets = te.onsets;
        Map<String, Object> window = (Map<String, Object>) hosts.get("window");
        if (PyOps.truthy(window) && "unknown".equals(window.get("status"))) {
            window = null;
        }
        Map<String, Object[]> scopes = new LinkedHashMap<>();
        scopes.put("analysis", new Object[] {ctx.start, ctx.end});
        scopes.put("event", PyOps.truthy(window) ? new Object[] {window.get("start"), window.get("end")} : null);
        Map<String, Topology.Point> points = topology.pointMap();
        db.exec(Sql.get("aggregates", "aggregate_boundaries"));
        List<Object[]> boundary = new ArrayList<>();
        for (Map<String, Object> s : ctx.segments) {
            Topology.Point pa = points.get(s.get("point_a"));
            Topology.Point pb = points.get(s.get("point_b"));
            if (pa.hostCapture() != pb.hostCapture()) {
                boundary.add(new Object[] {s.get("id"), pa.hostCapture() ? s.get("point_a") : s.get("point_b")});
            }
        }
        db.execMany(Sql.get("aggregates", "aggregate_boundaries_insert"), boundary);
        db.exec(Sql.get("aggregates", "aggregate_cycles"));
        db.exec(Sql.get("aggregates", "aggregate_added"));
        String copies = Sql.get("aggregates", "aggregate_copies") + " "
                + (ctx.byteActive ? Sql.get("aggregates", "atom_sql") + " " : "") + Sql.get("aggregates", "ordinary")
                + (ctx.byteActive ? " " + Sql.get("aggregates", "ordinary_bytes") : "");
        db.exec(copies);
        List<Topology.Point> hostPoints = new ArrayList<>();
        for (Topology.Point p : topology.points) {
            if (p.hostCapture()) {
                hostPoints.add(p);
            }
        }
        db.exec(Sql.get("aggregates", "aggregate_host_clocks"));
        for (Topology.Point p : hostPoints) {
            ClockModel m = ctx.models.get(p.captureId());
            if (m == null) {
                throw new IllegalStateException("KeyError: " + p.captureId());
            }
            db.exec(Sql.get("aggregates", "aggregate_host_clocks_insert"), p.id(), p.captureId(), PyOps.obj(m.epoch),
                    PyOps.obj(m.offset), PyOps.obj(m.drift));
        }
        db.exec(Sql.get("aggregates", "aggregate_host_packets"));
        List<String> selected = new ArrayList<>();
        for (Topology.Point p : hostPoints) {
            selected.add(p.id());
        }
        if (selected.isEmpty() && !topology.forward.isEmpty()) {
            selected.add(topology.forward.get(0));
        }
        String predicate = "(? IS NULL OR time>=?) AND (? IS NULL OR time<?)";
        List<Map<String, Object>> hostItems = (List<Map<String, Object>>) hosts.get("items");
        for (Map.Entry<String, Object[]> scopeEntry : scopes.entrySet()) {
            String scope = scopeEntry.getKey();
            Object[] bounds = scopeEntry.getValue();
            if (bounds == null) {
                cycles.put(scope, dict("total", null, "stalled_total", null, "max_added_ms", null, "repeat_groups",
                        dict("forward", null, "reverse", null), "reason", "No supported global event window"));
                hosts.put(scope, dict("rx_share", null, "max_silence_ms", null, "reason", "No supported global event window"));
                onsets.put(scope, dict("retrans_peak_time", null, "retrans_peak_count", null, "reason", "No supported global event window"));
                continue;
            }
            Object start = bounds[0];
            Object end = bounds[1];
            Object[] params = {start, start, end, end};
            boolean event = scope.equals("event");
            Object[] counts = event ? db.one(Sql.get("aggregates", "cycle_counts") + " WHERE " + predicate, params)
                    : db.one(Sql.get("aggregates", "cycle_counts"));
            Map<String, Object> c = dict("total", counts[0], "stalled_total", counts[1], "stall_ms", te.cycleStallMs);
            List<Row> best = db.rows(Sql.fmt("aggregates", "best_added", "predicate", predicate), params);
            if (!best.isEmpty()) {
                Row b = best.get(0);
                List<List<Object>> keys = new ArrayList<>();
                for (Map<String, Object> r : (List<Map<String, Object>>) b.get("frames")) {
                    keys.add(key(r.get("point_a"), r.get("frame_a")));
                    keys.add(key(r.get("point_b"), r.get("frame_b")));
                }
                b.put("_keys", keys);
                attach(db, List.of(b));
                c.put("max_added_ms", b.get("added_ms"));
                c.put("max_added", b);
            } else {
                c.put("max_added_ms", null);
                c.put("max_added", null);
                c.put("max_added_reason", "No complete clock-valid RX + TX boundary pair for a cycle");
            }
            String eventFilter = event ? " AND count(*) FILTER(WHERE " + predicate + ")>0" : "";
            List<Row> repeats = event ? db.rows(Sql.fmt("aggregates", "repeats", "event_filter", eventFilter), params)
                    : db.rows(Sql.fmt("aggregates", "repeats", "event_filter", eventFilter));
            Map<String, Object> repeatGroups = new LinkedHashMap<>();
            for (String d : List.of("forward", "reverse")) {
                Object groups = 0L;
                for (Row r : repeats) {
                    if (d.equals(r.get("direction"))) {
                        groups = r.get("groups");
                        break;
                    }
                }
                repeatGroups.put(d, groups);
            }
            c.put("repeat_groups", repeatGroups);
            cycles.put(scope, c);
            Object[] share = db.one(Sql.fmt("aggregates", "rx_share", "predicate", predicate), params);
            Object rx = share[0];
            Object totalAdded = share[1];
            Map<String, Object> h = dict("rx_share", PyOps.truthy(totalAdded) ? (Object) PyOps.div(PyOps.mul(100L, rx), totalAdded) : null,
                    "rx_share_reason", PyOps.truthy(totalAdded) ? null : "No positive complete cycle-added delay", "silence",
                    new ArrayList<>());
            List<Map<String, Object>> silence = (List<Map<String, Object>>) h.get("silence");
            for (Topology.Point p : hostPoints) {
                Object[] coverage = db.one(Sql.get("aggregates", "host_coverage"), p.id());
                if (start == null || end == null || !PyOps.truthy(coverage[2]) || PyOps.gt(coverage[0], start)
                        || PyOps.lt(coverage[1], end)) {
                    silence.add(dict("point", p.id(), "max_silence_ms", null, "reason",
                            "Clock or coverage does not bracket the requested window", "evidence", new ArrayList<>()));
                    continue;
                }
                List<Row> gap = db.rows(Sql.get("aggregates", "silence"), p.id(), end, start, start, end, end, start);
                Map<String, Object> g = gap.isEmpty() ? dict("max_silence_ms", null, "reason", "No bracketing packet pair") : gap.get(0);
                g.put("point", p.id());
                g.put("_keys", new ArrayList<>(List.of(key(p.id(), g.get("previous_frame")), key(p.id(), g.get("frame")))));
                attach(db, List.of(g));
                silence.add(g);
            }
            boolean allKnown = !silence.isEmpty() && silence.stream().allMatch(r -> r.get("max_silence_ms") != null);
            Object maxSilence = null;
            if (allKnown) {
                for (Map<String, Object> r : silence) {
                    maxSilence = maxSilence == null ? r.get("max_silence_ms") : PyOps.max(maxSilence, r.get("max_silence_ms"));
                }
            }
            h.put("max_silence_ms", maxSilence);
            h.put("silence_note",
                    "All valid host capture frames, including unrelated flows. Observed silence is not proof of a stall or capture continuity.");
            hosts.put(scope, h);
            List<Object> peakParams = new ArrayList<>();
            peakParams.add(selected);
            peakParams.addAll(Arrays.asList(params));
            List<Row> peak = db.rows(Sql.get("aggregates", "retrans_peak"), peakParams.toArray());
            if (!peak.isEmpty()) {
                Row pk = peak.get(0);
                pk.put("_keys", new ArrayList<>(List.of(key(pk.get("point"), pk.get("frame")))));
                attach(db, List.of(pk));
                onsets.put(scope, dict("retrans_peak_time", pk.get("time"), "retrans_peak_count", pk.get("count"), "retrans_peak", pk));
            } else {
                long unknown = db.count(Sql.get("aggregates", "retrans_unknown"), selected);
                onsets.put(scope, dict("retrans_peak_time", null, "retrans_peak_count", unknown > 0 ? null : 0L, "reason",
                        unknown > 0 ? "Retransmission clock unknown" : "No retransmissions observed in scope"));
            }
            for (Map<String, Object> item : hostItems) {
                List<Object> stallParams = new ArrayList<>(List.of(item.get("direction"), te.cycleStallMs));
                stallParams.addAll(Arrays.asList(params));
                long n = db.count(Sql.fmt("aggregates", "stall_flows", "predicate", predicate), stallParams.toArray());
                item.put("affected_flows_ge_stall" + (event ? "_event" : ""), n);
            }
        }
        Map<String, Object> analysisCycles = PyOps.map(cycles.get("analysis"));
        for (String k : List.of("total", "stalled_total", "max_added_ms", "max_added", "repeat_groups")) {
            cycles.put(k, analysisCycles.get(k));
        }
        hosts.put("rx_share", PyOps.map(hosts.get("analysis")).get("rx_share"));
        hosts.put("max_silence_ms", PyOps.map(hosts.get("event")).get("max_silence_ms"));
        Map<String, Object> analysisOnsets = PyOps.map(onsets.get("analysis"));
        for (String k : List.of("retrans_peak_time", "retrans_peak_count", "retrans_peak")) {
            onsets.put(k, analysisOnsets.get(k));
        }
        for (Map<String, Object> item : hostItems) {
            if (scopes.get("event") == null) {
                item.put("affected_flows_ge_stall_event", null);
            }
        }
        cycles.put("aggregate_note",
                "Distinct measured cycle IDs, stalled if any measured point reaches stall_ms. Added time requires both clock-valid host-boundary RX/TX components; positive components include recovery wait. Repeat groups deduplicate capture points and ignore SPAN copies; offload uses canonical byte ranges.");
    }
}
