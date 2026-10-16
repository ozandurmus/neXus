package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static com.securityexpert.nexus.ui2.packetanalysis.engine.PyOps.dict;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Reference {@code cycles.py}: opaque TCP data cycles, observed turns, never invented application requests. */
final class Cycles {
    static final String NOTE = "A cycle is a client data burst followed by the first server data burst. It is not a decoded application request. Pipelining, streaming and missing turns can be ambiguous; ACK/range consistency and coverage are required. RX/TX include network or host-boundary wait, including recovery; the delivery table separates retry evidence.";

    private Cycles() {
    }

    static void buildCycles(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        te.cycleReport = build(ctx, te);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> build(EngineContext ctx, TimeEvents te) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        // engine_topology: the analysis window replaces topology start/end.
        Double start = ctx.start;
        Double end = ctx.end;
        db.exec(Sql.get("cycles", "cycle_points"), te.cycleStallMs, start, start, end, end);
        db.exec(Sql.get("cycles", "cycle_windows"));
        Map<Object, Object[]> windows = new LinkedHashMap<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) PyOps.get(te.hostReport, "streams", new ArrayList<>())) {
            Map<String, Object> w = (Map<String, Object>) s.get("window");
            if (PyOps.truthy(w)) {
                windows.put(s.get("flow"), new Object[] {s.get("flow"), w.get("time"), w.get("end"), w.get("reason")});
            }
        }
        if (!windows.isEmpty()) {
            db.execMany(Sql.get("cycles", "cycle_windows_insert"), new ArrayList<>(windows.values()));
        }
        for (Map<String, Object> onset : (List<Map<String, Object>>) PyOps.get(te.onsets, "items", new ArrayList<>())) {
            if (!List.of("network_segment", "host_capture_boundary").contains(onset.get("scope"))) {
                continue;
            }
            db.exec(Sql.get("cycles", "cycle_windows_onset"), onset.get("time"), onset.get("end"), onset.get("point_a"));
        }
        db.exec(Sql.get("cycles", "cycle_series"), te.bucketSeconds, te.bucketSeconds);
        db.exec(Sql.get("cycles", "cycle_flow_ports"));
        db.exec(Sql.get("cycles", "cycle_periods"));
        db.exec(Sql.get("cycles", "cycle_edges"));
        List<Object[]> edges = new ArrayList<>();
        for (Map<String, Object> s : ctx.segments) {
            if (!s.get("point_a").equals(s.get("point_b"))) {
                edges.add(new Object[] {s.get("id"), s.get("point_a"), s.get("point_b"), s.get("direction"),
                    s.get("latency_uncertainty_ms"), s.get("reason")});
            }
        }
        db.execMany(Sql.get("cycles", "cycle_edges_insert"), edges);
        db.exec(Sql.get("cycles", "cycle_pairs"));
        db.exec(Sql.get("cycles", "cycle_pairs_negative"));
        db.exec(Sql.get("cycles", "cycle_top"), te.cycleTopN);
        db.exec(Sql.get("cycles", "cycle_top_points"));
        List<Row> top = db.rows(Sql.get("cycles", "top_rows"));
        TreeSet<List<Object>> keys = new TreeSet<>(Cycles::compareKey);
        for (Row r : top) {
            if (r.get("request_last_frame") != null) {
                keys.add(List.of(r.get("point"), r.get("request_last_frame")));
            }
            if (PyOps.truthy(r.get("response_frame"))) {
                keys.add(List.of(r.get("point"), r.get("response_frame")));
            }
        }
        if (!keys.isEmpty()) {
            List<Map<String, Object>> refs = Evidence.evidence(db,
                    "(o.point,o.frame) IN (SELECT unnest(?::VARCHAR[]),unnest(?::BIGINT[]))", pointsAndFrames(keys), keys.size(), false);
            Map<List<Object>, Map<String, Object>> lookup = new LinkedHashMap<>();
            for (Map<String, Object> r : refs) {
                lookup.put(List.of(r.get("point"), r.get("frame")), r);
            }
            for (Row r : top) {
                List<Map<String, Object>> ev = new ArrayList<>();
                for (List<Object> k : List.of(Arrays.asList(r.get("point"), r.get("request_last_frame")),
                        Arrays.asList(r.get("point"), r.get("response_frame")))) {
                    if (k.get(1) != null && lookup.containsKey(k)) {
                        ev.add(lookup.get(k));
                    }
                }
                r.put("evidence", ev);
            }
        }
        List<String> hosts = new ArrayList<>();
        for (Topology.Point p : topology.points) {
            if (p.hostCapture()) {
                hosts.add(p.id());
            }
        }
        return dict("note", NOTE, "scope", "Saved analysis window; opaque TCP turns, not application messages", "stall_ms",
                te.cycleStallMs, "top_n", te.cycleTopN, "top", top, "series", db.rows(Sql.get("cycles", "series_rows")), "flow_ports",
                db.rows(Sql.get("cycles", "flow_port_rows")), "periods", db.rows(Sql.get("cycles", "period_rows")), "pairs",
                db.rows(Sql.get("cycles", "pair_rows")), "host_local", db.rows(Sql.get("cycles", "host_local"), hosts));
    }

    /** Python tuple order of (point, frame) keys. */
    static int compareKey(List<Object> a, List<Object> b) {
        int c = PyOps.compareStr((String) a.get(0), (String) b.get(0));
        return c != 0 ? c : PyOps.compare(a.get(1), b.get(1));
    }

    /** The two parallel arrays of {@code [[p for p, f in keys], [f for p, f in keys]]}. */
    static List<Object> pointsAndFrames(java.util.Collection<List<Object>> keys) {
        List<Object> points = new ArrayList<>();
        List<Object> frames = new ArrayList<>();
        for (List<Object> k : keys) {
            points.add(k.get(0));
            frames.add(k.get(1));
        }
        return Arrays.asList(points, frames);
    }

    static void cycleFindings(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        Db db = ctx.db;
        db.exec(Sql.get("cycles", "cycle_degradations"), te.cycleStallMs);
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Row r : db.rows(Sql.get("cycles", "degradation_rows"))) {
            Map<String, Object> s = HostPath.findSegment(ctx.segments, r.get("hop"));
            List<Map<String, Object>> refs = Evidence.evidence(db, "(o.point=? AND o.frame=?) OR(o.point=? AND o.frame=?)",
                    Arrays.asList(r.get("point_a"), r.get("frame_a"), r.get("point_b"), r.get("frame_b")), 2, false);
            Map<String, Object> times = Headlines.timeLabels(PyOps.dbl(r.get("start")), ctx.topology.reportTimezone);
            String text = r.get("component") + " TCP-cycle wait increased between " + ((String) s.get("label")).replace(" → ", " and ")
                    + " by up to " + PyOps.fmt(r.get("max_ms"), ".2f") + " ms from " + times.get("local") + " / " + times.get("utc")
                    + " (baseline " + PyOps.fmt(r.get("baseline_ms"), ".3f") + " ms, uncertainty ±" + PyOps.fmt(r.get("uncertainty_ms"), ".3f")
                    + " ms). Includes holding/recovery, not a claim of packet transit or application processing.";
            r.put("window", dict("start", r.get("start"), "end", r.get("stop")));
            Map<String, Object> f = HostPath.mediaFinding("cycle_wait", s, r.get("start"), text, r, refs, true);
            f.put("severity", "high");
            f.put("time_range", PyOps.list(r.get("start"), r.get("stop")));
            f.put("tooltip", NOTE);
            f.put("cause", "observed_cycle_wait");
            findings.add(f);
        }
        ctx.findings.addAll(findings);
    }
}
