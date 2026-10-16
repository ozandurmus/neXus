package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static com.securityexpert.nexus.ui2.packetanalysis.engine.PyOps.dict;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Reference {@code microbursts.py}: local capture-rate excursions; a nearby loss never proves a cause. */
final class Microbursts {
    static final String NOTE = "Rolling capture-byte rate, not physical line capacity. At least 8 packets and 4× the median "
            + "nonempty fixed-window rate (floor 1 Mbps). SPAN copies are excluded. Offload, timestamp batching "
            + "and incomplete coverage can obscure wire timing. Nearby loss is corroboration only; cause unknown.";

    private Microbursts() {
    }

    static void build(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        te.microbursts = buildMicrobursts(ctx, te, ctx.start, ctx.end);
    }

    private static ClockModel model(EngineContext ctx, String captureId) {
        ClockModel m = ctx.models.get(captureId);
        return m != null ? m : new ClockModel();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> buildMicrobursts(EngineContext ctx, TimeEvents te, Double start, Double end) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        double width = PyOps.div(te.microburstWindowMs, 1000L);
        String w = PyOps.str(width);
        db.exec(Sql.get("microbursts", "burst_clocks"));
        List<Object[]> clocks = new ArrayList<>();
        for (Topology.Point p : topology.points) {
            ClockModel m = model(ctx, p.captureId());
            clocks.add(new Object[] {p.id(), PyOps.obj(m.offset), PyOps.obj(m.drift), PyOps.obj(m.epoch)});
        }
        db.execMany(Sql.get("microbursts", "burst_clocks_insert"), clocks);
        db.exec(Sql.get("microbursts", "burst_wire"), PyOps.div(te.duplicateUs, 1e6), start, start, end, end);
        db.exec(Sql.fmt("microbursts", "microburst_windows", "width", w));
        db.exec(Sql.fmt("microbursts", "burst_baseline", "width", w));
        db.exec(Sql.fmt("microbursts", "burst_candidates", "width", w));
        db.exec(Sql.get("microbursts", "microbursts"));
        List<Row> points = db.rows(Sql.fmt("microbursts", "points", "width", w));
        long total = db.count(Sql.get("microbursts", "total"));
        List<Row> items = db.rows(Sql.get("microbursts", "items"));
        Set<List<Object>> keys = new LinkedHashSet<>();
        List<Row> all = new ArrayList<>(points);
        all.addAll(items);
        for (Row r : all) {
            for (String field : List.of("first_frame", "peak_frame", "last_frame")) {
                if (r.get(field) != null) {
                    keys.add(List.of(r.get("point"), r.get(field)));
                }
            }
        }
        List<Object> refs = new ArrayList<>();
        for (List<Object> k : keys) {
            refs.add(dict("point", k.get(0), "frame", k.get(1)));
        }
        Map<List<Object>, Row> proof = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("microbursts", "proof"), PyJson.dumps(refs))) {
            proof.put(List.of(r.get("point"), r.get("frame")), r);
        }
        Map<String, Topology.Point> bypoint = topology.pointMap();
        for (Row p : points) {
            p.put("burst_count", PyOps.truthy(p.get("burst_count")) ? p.get("burst_count") : 0L);
            p.put("frequency_hz", PyOps.ge(p.get("observed_seconds"), width)
                    ? (PyOps.truthy(p.get("frequency_hz")) ? p.get("frequency_hz") : 0L) : null);
            p.put("label", bypoint.get(p.str("point")).label());
            p.put("window_ms", te.microburstWindowMs);
            p.put("tooltip", NOTE);
            p.put("evidence", wireEvidence(proof, p.get("point"), PyOps.list(p.get("first_frame"), p.get("peak_frame"),
                    p.get("last_frame"))));
            if (p.get("peak_observed_mbps") == null) {
                p.put("burst_count", null);
                p.put("frequency_hz", null);
                p.put("tooltip", p.get("tooltip") + " No complete, non-offload measurement window; rate/burst absence unknown.");
            }
        }
        List<Map<String, Object>> onsetItems = (List<Map<String, Object>>) PyOps.get(te.onsets, "items", new ArrayList<>());
        for (Row item : items) {
            ClockModel model = model(ctx, bypoint.get(item.str("point")).captureId());
            item.put("cause", "unknown");
            item.put("tooltip", NOTE);
            item.put("corrected_start", model.correct(item.dbl("start")));
            item.put("corrected_end", model.correct(item.dbl("end")));
            item.put("clock_uncertainty_ms", model.uncertainty != null ? PyOps.mul(PyOps.obj(model.uncertainty), 1000L) : null);
            item.put("evidence", wireEvidence(proof, item.get("point"), PyOps.list(item.get("first_frame"), item.get("peak_frame"),
                    item.get("last_frame"))));
            item.put("loss_associations", associations(item, onsetItems, model));
            if (PyOps.numEq(item.get("duration_ms"), 0L)) {
                item.put("duration_ms", null);
            }
        }
        return dict("window_ms", te.microburstWindowMs, "points", points, "items", items, "total", total, "note",
                NOTE + " Up to 200 strongest burst episodes shown; point counts use all episodes.");
    }

    static List<Map<String, Object>> wireEvidence(Map<List<Object>, Row> lookup, Object point, List<Object> frames) {
        Set<Long> unique = new TreeSet<>();
        for (Object f : frames) {
            if (f != null) {
                unique.add((Long) f);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long frame : unique) {
            Row r = lookup.get(List.of(point, frame));
            if (r == null) {
                continue;
            }
            Object ts = r.get("ts");
            result.add(dict("point", point, "capture_id", r.get("capture_id"), "file", r.get("file"), "frame", r.get("frame"),
                    "observed_time", ts, "corrected_time", null, "display_filter", "frame.number == " + PyOps.str(r.get("frame")),
                    "content_filter", "frame.len == " + PyOps.str(r.get("wirelen")) + " && frame.time_epoch >= "
                            + PyOps.fmt(PyOps.sub(ts, 1e-6), ".9f") + " && frame.time_epoch <= " + PyOps.fmt(PyOps.add(ts, 1e-6), ".9f"),
                    "filter_note", "Physical capture-frame rate evidence; timestamp/length filters can match simultaneous copies"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> associations(Map<String, Object> burst, List<Map<String, Object>> onsets, ClockModel model) {
        Double end = model.correct(PyOps.dbl(burst.get("end")));
        if (end == null || model.uncertainty == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> onset : onsets) {
            if (!java.util.Objects.equals(onset.get("point_a"), burst.get("point")) || !"loss_percent".equals(onset.get("metric"))) {
                continue;
            }
            if (onset.get("clock_uncertainty_ms") == null) {
                continue;
            }
            List<Object> stamps = new ArrayList<>();
            List<Map<String, Object>> evidence = (List<Map<String, Object>>) PyOps.get(onset, "evidence", new ArrayList<>());
            for (Map<String, Object> r : evidence) {
                Object ct = r.get("corrected_time");
                if (java.util.Objects.equals(r.get("point"), burst.get("point")) && ct != null && PyOps.le(onset.get("time"), ct)
                        && PyOps.lt(ct, onset.get("end"))) {
                    stamps.add(ct);
                }
            }
            if (stamps.isEmpty()) {
                continue;
            }
            Object first = Onsets.minOf(stamps);
            Object gap = PyOps.mul(PyOps.sub(first, end), 1000L);
            Object u = PyOps.obj(model.uncertainty);
            Object uncertainty = PyOps.add(PyOps.mul(PyOps.truthy(u) ? u : 0L, 1000L),
                    PyOps.truthy(onset.get("clock_uncertainty_ms")) ? onset.get("clock_uncertainty_ms") : 0L);
            if (PyOps.le(PyOps.mul(uncertainty, -1L), gap) && PyOps.le(gap, PyOps.add(100L, uncertainty))) {
                result.add(dict("segment", onset.get("segment"), "loss_time", first, "gap_ms", gap, "uncertainty_ms", uncertainty,
                        "ordering", PyOps.gt(gap, uncertainty) ? "before" : "unknown", "relation", "corroboration_only", "cause",
                        "unknown", "evidence", evidence, "reason",
                        "Capture-rate burst is near the next segment loss onset; this does not establish queue overflow or any loss cause"));
            }
        }
        return result;
    }
}
