package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static com.securityexpert.nexus.ui2.packetanalysis.engine.PyOps.dict;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyMath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reference {@code timeseries.py}: bounded, coverage-aware segment buckets from matched observations. */
final class Timeseries {
    static final List<String> CLASSES = List.of("recovered_loss", "impactful_loss", "unrecovered_loss", "handshake_blocked",
            "confirmed_device_drop", "capture_miss", "unknown");
    /** METRICS: key to (label, unit, tooltip), in the reference insertion order. */
    static final Map<String, String[]> METRICS = new LinkedHashMap<>();

    static {
        METRICS.put("throughput_bps", new String[] {"Throughput", "bit/s",
            "Upstream observed wire bytes × 8 per full bucket second; includes retransmissions."});
        METRICS.put("pps", new String[] {"Packet rate", "packets/s",
            "All upstream observations per full bucket second, including control packets."});
        METRICS.put("loss_percent", new String[] {"Network loss", "%",
            "Supported recovered, impactful, unrecovered, blocked-handshake and confirmed device-drop events / eligible upstream packets."});
        METRICS.put("retrans_percent", new String[] {"Retransmissions", "%",
            "tshark retransmissions / observed upstream TCP packets; a local TCP symptom, not hop attribution."});
        METRICS.put("latency_p50_ms", new String[] {"Transit p50", "ms",
            "Median corrected downstream minus upstream time for shared eligible packet occurrences."});
        METRICS.put("latency_p95_ms", new String[] {"Transit p95", "ms",
            "95th percentile corrected transit time; clock uncertainty applies."});
        METRICS.put("latency_max_ms", new String[] {"Transit maximum", "ms",
            "Maximum corrected transit time among matched packets; clock uncertainty applies."});
        METRICS.put("rtt_ms", new String[] {"RTT", "ms",
            "Median tshark ACK RTT at the upstream point; this is not the isolated hop delay."});
        METRICS.put("new_connections", new String[] {"New connections", "connections",
            "Distinct TCP sessions with an initial SYN at this upstream point in the bucket."});
        METRICS.put("failed_handshakes", new String[] {"Failed handshakes", "connections",
            "Distinct sessions with a supported blocked-handshake event at this segment."});
        METRICS.put("resets", new String[] {"Resets", "packets", "Observed upstream TCP RST packets."});
        METRICS.put("zero_windows", new String[] {"Zero windows", "packets", "Upstream TCP zero-window announcements reported by tshark."});
        for (String c : CLASSES) {
            METRICS.put(c + "_percent", new String[] {PyOps.capitalize(c.replace('_', ' ')), "%",
                "Classified events / eligible upstream packets; capture misses and unknown appearances are not network loss."});
        }
    }

    private Timeseries() {
    }

    static void build(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        te.timeseries = buildTimeseries(ctx, te);
    }

    static Map<String, Object> buildTimeseries(EngineContext ctx, TimeEvents te) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Object width = te.bucketSeconds;
        List<Row> valid = new ArrayList<>();
        for (Row c : ctx.coverage) {
            if (c.get("start") != null && c.get("end") != null) {
                valid.add(c);
            }
        }
        Object lo = topology.start;
        if (lo == null) {
            for (Row c : valid) {
                lo = lo == null ? c.get("start") : PyOps.min(lo, c.get("start"));
            }
        }
        Object hi = topology.end;
        if (hi == null) {
            for (Row c : valid) {
                hi = hi == null ? c.get("end") : PyOps.max(hi, c.get("end"));
            }
        }
        List<String> metricSql = new ArrayList<>();
        for (String m : METRICS.keySet()) {
            metricSql.add("\"" + m + "\" DOUBLE");
        }
        db.exec(Sql.fmt("timeseries", "segment_buckets", "metric_sql", String.join(",", metricSql)));
        Map<String, Object> metricsMeta = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : METRICS.entrySet()) {
            metricsMeta.put(e.getKey(), dict("label", e.getValue()[0], "unit", e.getValue()[1], "tooltip", e.getValue()[2]));
        }
        Map<String, Object> meta = dict("bucket_seconds", width, "start", lo, "end", hi, "bucket_count", 0L, "metrics",
                metricsMeta);
        if (lo == null || hi == null || PyOps.le(hi, lo)) {
            return meta;
        }
        Object origin = PyOps.mul(PyMath.floor(PyOps.div(lo, width)), width);
        long count = PyMath.ceil(PyOps.div(PyOps.sub(hi, origin), width));
        if (count * ctx.segments.size() > 200000) {
            throw new IllegalArgumentException("Too many heatmap cells; increase bucket_seconds or narrow the analysis window");
        }
        Object stop = PyOps.add(origin, PyOps.mul(count, width));
        meta.put("start", origin);
        meta.put("end", stop);
        meta.put("bucket_count", count);
        Map<Object, Row> byPoint = new LinkedHashMap<>();
        for (Row c : valid) {
            byPoint.put(c.get("point"), c);
        }
        db.exec(Sql.fmt("timeseries", "bucket_obs", "origin", PyOps.str(origin), "width", PyOps.str(width), "stop",
                PyOps.str(stop)));
        Map<String, Object> window = dict("start", ctx.start, "end", ctx.end, "common_start", ctx.commonStart, "common_end",
                ctx.commonEnd);
        List<Object[]> columns = db.tuples(Sql.get("timeseries", "describe"));
        List<String> names = new ArrayList<>();
        Map<String, Object> schemaRow = new LinkedHashMap<>();
        for (Object[] c : columns) {
            names.add((String) c[0]);
            schemaRow.put((String) c[0], c[1]);
        }
        String schema = PyJson.dumps(List.of(schemaRow));
        for (Map<String, Object> s : ctx.segments) {
            String a = (String) s.get("point_a");
            String b = (String) s.get("point_b");
            String d = (String) s.get("direction");
            Row ca = byPoint.get(a);
            Row cb = byPoint.get(b);
            Object left = ca != null && cb != null ? PyOps.max(ca.get("start"), cb.get("start")) : null;
            Object right = ca != null && cb != null ? PyOps.min(ca.get("end"), cb.get("end")) : null;
            Map<Object, Row> aggregate = new LinkedHashMap<>();
            for (Row r : db.rows(Sql.get("timeseries", "aggregate"), a, d)) {
                aggregate.put(r.get("bucket"), r);
            }
            Map<Object, Object> downstreamQuality = new LinkedHashMap<>();
            for (Object[] r : db.tuples(Sql.get("timeseries", "downstream_quality"), b, d)) {
                downstreamQuality.put(r[0], r[1]);
            }
            Map<Object, Row> latency = new LinkedHashMap<>();
            for (Row r : db.rows(Sql.get("timeseries", "latency"), origin, width, a, b, d)) {
                latency.put(r.get("bucket"), r);
            }
            Map<Object, Map<String, Row>> events = new LinkedHashMap<>();
            for (Row r : db.rows(Sql.get("timeseries", "event_buckets"), a, b, d, origin, width)) {
                events.computeIfAbsent(r.get("bucket"), k -> new LinkedHashMap<>()).put(r.str("kind"), r);
            }
            List<Map<String, Object>> records = new ArrayList<>();
            for (long i = 0; i < count; i++) {
                Object start = PyOps.add(origin, PyOps.mul(i, width));
                Object end = PyOps.add(origin, PyOps.mul(i + 1, width));
                String state = left == null || right == null ? "unknown coverage"
                        : PyOps.le(end, left) || PyOps.ge(start, right) ? "not capturing"
                        : PyOps.lt(start, left) || PyOps.gt(end, right) ? "partial coverage" : "capturing";
                Map<String, Object> x = aggregate.containsKey(i) ? aggregate.get(i) : new LinkedHashMap<>();
                Map<String, Object> y = latency.containsKey(i) ? latency.get(i) : new LinkedHashMap<>();
                Map<String, Row> ev = events.containsKey(i) ? events.get(i) : new LinkedHashMap<>();
                Object eligible = PyOps.get(x, "eligible", 0L);
                Object miss = n(ev, "capture_miss", "n");
                Object unknown = n(ev, "unknown", "n");
                String reason = (String) s.get("reason");
                Object first = PyOps.truthy(x.get("packets")) ? (Object) PyOps.div(PyOps.get(x, "matchable", 0L), x.get("packets"))
                        : (Object) 1L;
                Object ratio = PyOps.min(first, downstreamQuality.containsKey(i) ? downstreamQuality.get(i) : 1L);
                if (!PyOps.truthy(reason) && PyOps.lt(ratio, te.minEligibleRatio)) {
                    reason = "Insufficient matchable packets in this bucket ("
                            + PyOps.fmt(PyOps.mul(100L, PyOps.sub(1L, ratio)), ".1f") + "% excluded)";
                }
                Map<String, Object> metrics = new LinkedHashMap<>();
                for (String k : METRICS.keySet()) {
                    metrics.put(k, null);
                }
                if (state.equals("capturing")) {
                    metrics.put("throughput_bps", PyOps.div(PyOps.get(x, "bits", 0L), width));
                    metrics.put("pps", PyOps.div(PyOps.get(x, "packets", 0L), width));
                    metrics.put("rtt_ms", x.get("rtt_ms"));
                    metrics.put("new_connections", PyOps.get(x, "new_connections", 0L));
                    metrics.put("resets", PyOps.get(x, "resets", 0L));
                    metrics.put("zero_windows", PyOps.get(x, "zero_windows", 0L));
                    metrics.put("retrans_percent", PyOps.truthy(x.get("tcp"))
                            ? (Object) PyOps.div(PyOps.mul(100L, PyOps.get(x, "retrans", 0L)), x.get("tcp")) : null);
                    boolean classified = window.get("start") != null && window.get("end") != null
                            && window.get("common_start") != null && window.get("common_end") != null
                            && PyOps.ge(start, PyOps.max(window.get("start"), window.get("common_start")))
                            && PyOps.le(end, PyOps.min(window.get("end"), window.get("common_end")));
                    if (!PyOps.truthy(reason) && classified) {
                        for (String k : CLASSES) {
                            metrics.put(k + "_percent", PyOps.truthy(eligible)
                                    ? (Object) PyOps.div(PyOps.mul(100L, n(ev, k, "n")), eligible) : null);
                        }
                        if (PyOps.truthy(eligible)) {
                            List<Object> parts = new ArrayList<>();
                            for (String k : CLASSES.subList(0, 5)) {
                                parts.add(metrics.get(k + "_percent"));
                            }
                            metrics.put("loss_percent", PyOps.sum(parts));
                        } else {
                            metrics.put("loss_percent", null);
                        }
                        metrics.put("failed_handshakes", n(ev, "handshake_blocked", "flows"));
                    }
                    if (!PyOps.truthy(reason)) {
                        for (String k : List.of("latency_p50_ms", "latency_p95_ms", "latency_max_ms")) {
                            metrics.put(k, y.get(k));
                        }
                    }
                    if (!classified && !PyOps.truthy(reason)) {
                        reason = "Outside classified common window; loss rates unknown";
                    }
                } else {
                    reason = state;
                }
                List<Object> lossParts = new ArrayList<>();
                for (String k : CLASSES.subList(0, 5)) {
                    lossParts.add(n(ev, k, "n"));
                }
                List<Object> record = new ArrayList<>(List.of(s.get("id"), d, i, start, end, state));
                record.add(reason);
                record.add(eligible);
                record.add(PyOps.get(y, "matched", 0L));
                record.add(miss);
                record.add(unknown);
                record.add(PyOps.sum(lossParts));
                record.add(PyOps.get(x, "matchable", 0L));
                record.add(PyOps.get(x, "tcp", 0L));
                record.add(PyOps.get(x, "retrans", 0L));
                record.add(n(ev, "handshake_blocked", "flows"));
                record.addAll(metrics.values());
                Map<String, Object> named = new LinkedHashMap<>();
                for (int k = 0; k < names.size(); k++) {
                    named.put(names.get(k), record.get(k));
                }
                records.add(named);
            }
            if (!records.isEmpty()) {
                db.exec(Sql.get("timeseries", "insert"), PyJson.dumps(records), schema);
            }
        }
        return meta;
    }

    private static Object n(Map<String, Row> ev, String kind, String key) {
        Row r = ev.get(kind);
        return r != null && r.containsKey(key) ? r.get(key) : 0L;
    }
}
