package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static com.securityexpert.nexus.ui2.packetanalysis.engine.PyOps.dict;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyRound;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Py;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Reference {@code host_path.py}: matched boundary delay, separate from same-host service time. */
final class HostPath {
    static final String NOTE = "Delay is observed between a path capture point and an OS host capture. It includes capture/NIC/driver/GRO/TSO timing and is not proof of application processing or a specific driver cause. Byte ranges preserve offload matching. Local service time uses separately decoded HTTP boundaries; opaque protocols remain unknown.";
    static final String RTP_NOTE = "Sequence gaps are observations, not automatically network loss. Streams are scoped by canonical directional tuple and SSRC, never SSRC/port alone. RFC 3550 jitter uses arrival order and a declared/SDP/static payload clock; absent or changing clocks stay unknown. Hop attribution requires independently supported matching, clocks and coverage.";
    static final Map<String, Integer> RANK = Map.of("high", 0, "low", 1, "quality", 2, "unknown", 3);

    private HostPath() {
    }

    /** {@code rtp.media_finding}: the shared finding shape of the time and event detectors. */
    static Map<String, Object> mediaFinding(String kind, Map<String, Object> segment, Object ts, String summary,
            Map<String, Object> metrics, List<Map<String, Object>> refs, boolean supported) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", "media:" + kind + ":" + segment.get("id") + ":" + PyOps.str(PyOps.get(metrics, "flow", "")) + ":"
                + PyOps.str(PyOps.get(metrics, "call_id", "")) + ":" + PyOps.str(ts));
        f.put("type", kind);
        f.put("hop", segment.get("id"));
        f.put("device", segment.get("device"));
        f.put("direction", segment.get("direction"));
        f.put("time_range", PyOps.list(ts, ts));
        f.put("severity", supported ? "low" : "unknown");
        f.put("confidence", supported ? "supported" : "unknown");
        f.put("summary", summary);
        f.put("headline", summary);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", 1L);
        m.putAll(metrics);
        f.put("metrics", m);
        f.put("evidence", refs);
        f.put("tooltip", RTP_NOTE);
        f.put("cause", "unknown");
        return f;
    }

    static Map<String, Object> findSegment(List<Map<String, Object>> segments, Object id) {
        for (Map<String, Object> s : segments) {
            if (Objects.equals(s.get("id"), id)) {
                return s;
            }
        }
        throw new IllegalStateException("StopIteration: segment " + id);
    }

    // ------------------------------------------------------------------ host_path

    static void hostPath(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        te.hostReport = build(ctx, te);
        te.hostFindings = new ArrayList<>();
    }

    static Map<String, Object> build(EngineContext ctx, TimeEvents te) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Topology.Point> points = topology.pointMap();
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> s : ctx.segments) {
            if (("forward".equals(s.get("direction")) && points.get(s.get("point_b")).hostCapture())
                    || ("reverse".equals(s.get("direction")) && points.get(s.get("point_a")).hostCapture())) {
                selected.add(s);
            }
        }
        if (selected.isEmpty()) {
            return dict("items", new ArrayList<>(), "streams", new ArrayList<>(), "series", new ArrayList<>(), "local_service",
                    new LinkedHashMap<>(), "note", NOTE);
        }
        db.exec(Sql.get("host_path", "host_edges"));
        List<Object[]> edges = new ArrayList<>();
        for (Map<String, Object> s : selected) {
            Object reason = PyOps.truthy(s.get("reason")) ? s.get("reason")
                    : s.get("offset_uncertainty_ms") == null ? "Host boundary clock uncertainty unverified" : null;
            edges.add(new Object[] {s.get("id"), s.get("point_a"), s.get("point_b"), s.get("direction"),
                "forward".equals(s.get("direction")) ? "rx" : "tx", s.get("latency_uncertainty_ms"), reason});
        }
        db.execMany(Sql.get("host_path", "host_edges_insert"), edges);
        db.exec(Sql.get("host_path", "host_transit"), topology.start, topology.start, topology.end, topology.end);
        db.exec(Sql.get("host_path", "host_path_series"));
        List<String> hosts = new ArrayList<>();
        for (Topology.Point p : topology.points) {
            if (p.hostCapture()) {
                hosts.add(p.id());
            }
        }
        List<Row> service = db.rows(Sql.get("host_path", "service"), hosts);
        for (Row r : service) {
            r.put("evidence", Evidence.evidence(db, "o.point=? AND o.frame IN (?,?)",
                    Arrays.asList(r.get("point"), r.get("request_frame"), r.get("response_frame")), 2));
        }
        db.exec(Sql.get("host_path", "host_bursts"), hosts);
        List<Row> turnaround = db.rows(Sql.get("host_path", "turnaround"));
        for (Row r : turnaround) {
            r.put("evidence", Evidence.evidence(db, "o.point=? AND o.frame IN (?,?)",
                    Arrays.asList(r.get("point"), r.get("request_frame"), r.get("response_frame")), 2));
            r.put("meaning", "ACK-consistent local data-burst turnaround; application request boundaries unknown");
        }
        Object[] aggregate = db.one(Sql.get("host_path", "service_aggregate"), hosts);
        List<Row> streams = db.rows(Sql.get("host_path", "streams"));
        for (Row stream : streams) {
            Map<String, Object> segment = null;
            for (Map<String, Object> s : selected) {
                if (Objects.equals(s.get("id"), stream.get("hop"))) {
                    segment = s;
                    break;
                }
            }
            stream.put("evidence", Evidence.evidence(db, "(o.point=? AND o.packet_key=?) OR(o.point=? AND o.packet_key=?)",
                    Arrays.asList(segment.get("point_a"), stream.get("evidence_key"), segment.get("point_b"), stream.get("evidence_key_b")),
                    2, false));
            stream.put("tooltip", NOTE);
            stream.put("local_service", service.stream().filter(r -> Objects.equals(r.get("flow"), stream.get("flow"))).findFirst()
                    .orElse(null));
            stream.put("transport_turnaround", turnaround.stream().filter(r -> Objects.equals(r.get("flow"), stream.get("flow")))
                    .findFirst().orElse(null));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("items", new ArrayList<>());
        report.put("streams", streams);
        report.put("series", db.rows(Sql.get("host_path", "series_rows")));
        report.put("local_service", dict("samples", aggregate[0], "p95_ms", aggregate[1], "flows", service, "transport_turnaround",
                turnaround, "reason", PyOps.truthy(aggregate[0]) ? null : "No uniquely decoded application boundaries"));
        report.put("note", NOTE);
        report.put("scope", "Saved selected window; RX/TX shares are byte-weighted excess boundary delay");
        return aggregateHost(ctx, te, selected, report);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> aggregateHost(EngineContext ctx, TimeEvents te, List<Map<String, Object>> segments,
            Map<String, Object> report) {
        Db db = ctx.db;
        List<Row> globalSeries = db.rows(Sql.get("host_path", "global_series"));
        List<Row> streams = (List<Row>) report.get("streams");
        Object uncertainty = 0L;
        boolean first = true;
        for (Row s : streams) {
            Object u = PyOps.truthy(s.get("uncertainty_ms")) ? s.get("uncertainty_ms") : 0L;
            uncertainty = first ? u : PyOps.max(uncertainty, u);
            first = false;
        }
        Object[] lw = Onsets.latencyWindow(globalSeries, PyOps.max(1L, PyOps.mul(2L, uncertainty)), "latency_p95_ms");
        Map<String, Object> window = (Map<String, Object>) lw[0];
        String why = (String) lw[1];
        if (window != null) {
            window.put("start", window.get("time"));
        }
        report.put("window", window);
        report.put("window_reason", why);
        List<Map<String, Object>> items = new ArrayList<>();
        List<Object> parts = new ArrayList<>();
        for (Row s : streams) {
            if (!PyOps.truthy(s.get("reason"))) {
                parts.add(PyOps.truthy(s.get("delay_byte_ms")) ? s.get("delay_byte_ms") : 0L);
            }
        }
        Object total = PyOps.sum(parts);
        for (String direction : List.of("rx", "tx")) {
            List<Map<String, Object>> details = new ArrayList<>();
            for (Row s : streams) {
                if (direction.equals(s.get("direction"))) {
                    details.add(s);
                }
            }
            List<Map<String, Object>> affected = new ArrayList<>();
            for (Map<String, Object> s : details) {
                if (!PyOps.truthy(s.get("reason")) && PyOps.truthy(s.get("delayed_bytes"))) {
                    affected.add(s);
                }
            }
            if (affected.isEmpty()) {
                continue;
            }
            Map<String, Object> dominant = PyOps.maxBy(affected, PyOps.byNum(s -> s.get("max_ms")));
            Map<String, Object> segment = findSegment(segments, dominant.get("hop"));
            Row item = db.rows(Sql.get("host_path", "direction_item"), direction).get(0);
            List<Row> series = db.rows(Sql.get("host_path", "direction_series"), direction);
            for (Row r : series) {
                Object hop = r.remove("evidence_hop");
                Map<String, Object> edge = findSegment(segments, hop);
                Object keyA = r.remove("evidence_key");
                Object keyB = r.remove("evidence_key_b");
                r.put("evidence", Evidence.evidence(db, "(o.point=? AND o.packet_key=?) OR(o.point=? AND o.packet_key=?)",
                        Arrays.asList(edge.get("point_a"), keyA, edge.get("point_b"), keyB), 2, false));
            }
            Map<String, Object> current = window;
            if (current == null) {
                Object twiceUncertainty = PyOps.mul(2L, item.get("uncertainty_ms"));
                boolean absolute = PyOps.ge(item.get("max_ms"), PyOps.max(te.stallMs, twiceUncertainty));
                if (!absolute && (series.size() >= 8 || PyOps.le(item.get("p95_ms"), PyOps.max(1L, twiceUncertainty)))) {
                    continue;
                }
                boolean baselineMissing = globalSeries.isEmpty()
                        || PyOps.gt(globalSeries.get(0).get("latency_p95_ms"), PyOps.max(1L, PyOps.mul(2L, uncertainty)));
                why = absolute && baselineMissing ? "no pre-event coverage" : why;
                current = dict("start", series.get(0).get("start"), "time", series.get(0).get("start"), "end",
                        series.get(series.size() - 1).get("end"), "status", "unknown", "absolute_threshold_ms",
                        absolute ? te.stallMs : null, "reason", why, "recovery_confirmed", false);
            }
            List<Row> periods = db.rows(Sql.get("host_path", "periods"), current.get("start"), current.get("end"), direction);
            if ("unknown".equals(current.get("status"))) {
                for (Row period : periods) {
                    period.put("period", "observed; onset/recovery unknown");
                }
            }
            for (Map<String, Object> detail : details) {
                detail.put("window", current);
            }
            Set<String> hops = new TreeSet<>(Py.STR_ORDER);
            Set<Object> flows = new LinkedHashSet<>();
            for (Map<String, Object> s : affected) {
                hops.add((String) s.get("hop"));
                flows.add(s.get("flow"));
            }
            item.put("hop", dominant.get("hop"));
            item.put("hops", new ArrayList<>(hops));
            item.put("direction", direction);
            item.put("flow", null);
            item.put("affected_flow_count", (long) flows.size());
            item.put("flow_details", details);
            item.put("series", series);
            item.put("window", current);
            item.put("window_reason", why);
            item.put("periods", periods);
            item.put("delay_share_percent", PyOps.truthy(total)
                    ? (Object) PyOps.div(PyOps.mul(100L, item.get("delay_byte_ms")), total) : null);
            item.put("evidence", dominant.get("evidence"));
            item.put("tooltip", NOTE);
            item.put("segment_label", segment.get("label"));
            items.add(item);
        }
        report.put("items", items);
        return report;
    }

    // ------------------------------------------------------------------ finish_host_summary

    @SuppressWarnings("unchecked")
    static void finishHostSummary(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Object> report = te.hostReport;
        List<String> hosts = new ArrayList<>();
        for (Topology.Point p : topology.points) {
            if (p.hostCapture()) {
                hosts.add(p.id());
            }
        }
        Row aggregate = db.rows(Sql.get("host_path", "cycle_aggregate"), hosts).get(0);
        PyOps.map(report.get("local_service")).put("cycle_aggregate", aggregate);
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) report.get("items")) {
            Map<String, Object> local = PyOps.map(report.get("local_service"));
            String text;
            if (Objects.equals(local.get("samples"), aggregate.get("samples")) && PyOps.truthy(aggregate.get("samples"))) {
                text = "the application responds in " + PyOps.fmt(aggregate.get("p95_ms"), ".3f") + " ms locally (HTTP cycle p95 over "
                        + PyOps.str(aggregate.get("samples")) + " cycles)";
            } else if (PyOps.truthy(aggregate.get("samples"))) {
                text = "host-local data turnaround p95 " + PyOps.fmt(aggregate.get("p95_ms"), ".3f") + " ms over "
                        + PyOps.str(aggregate.get("samples")) + " cycles; application boundaries unknown";
            } else {
                text = "host-local turnaround unknown";
            }
            Map<String, Object> w = PyOps.map(item.get("window"));
            String headline = "Delay occurs between " + ((String) item.get("segment_label")).replace(" → ", " and ") + "; " + text + ". "
                    + PyOps.str(item.get("affected_flow_count")) + " affected flow(s).";
            if ("unknown".equals(w.get("status"))) {
                headline += " Onset unknown: " + PyOps.str(w.get("reason")) + "; recovery unknown.";
            } else {
                Map<String, Object> startLabels = Headlines.timeLabels(PyOps.dbl(w.get("start")), topology.reportTimezone);
                Map<String, Object> endLabels = Headlines.timeLabels(PyOps.dbl(w.get("end")), topology.reportTimezone);
                headline += " Global event window " + startLabels.get("local") + " / " + startLabels.get("utc") + " through "
                        + endLabels.get("utc") + "; recovery " + (PyOps.truthy(w.get("recovery_confirmed")) ? "confirmed" : "unknown") + ".";
            }
            headline += " Clock uncertainty ±" + PyOps.fmt(item.get("uncertainty_ms"), ".3f") + " ms.";
            item.put("local_service", report.get("local_service"));
            item.put("headline", headline);
            Map<String, Object> segment = findSegment(ctx.segments, item.get("hop"));
            String kind = "rx".equals(item.get("direction")) ? "host_receive_delay" : "host_transmit_delay";
            Map<String, Object> metrics = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : item.entrySet()) {
                if (!List.of("evidence", "series", "flow_details", "tooltip").contains(e.getKey())) {
                    metrics.put(e.getKey(), e.getValue());
                }
            }
            Map<String, Object> f = mediaFinding(kind, segment, w.get("start"), headline, metrics,
                    (List<Map<String, Object>>) item.get("evidence"), true);
            f.put("time_range", PyOps.list(w.get("start"), w.get("end")));
            f.put("tooltip", NOTE);
            f.put("cause", "host_capture_boundary");
            List<Object> flows = new ArrayList<>();
            for (Map<String, Object> s : (List<Map<String, Object>>) item.get("flow_details")) {
                if (PyOps.truthy(s.get("delayed_bytes")) && !PyOps.truthy(s.get("reason"))) {
                    flows.add(s.get("flow"));
                }
            }
            PyOps.map(f.get("metrics")).put("flows", flows);
            findings.add(f);
        }
        ctx.findings.addAll(findings);
    }

    // ------------------------------------------------------------------ polish_findings

    static String hostLossType(Map<String, Topology.Point> points, String a, String b, String kind, boolean recovered) {
        if (!List.of("impactful_loss", "recovered_loss").contains(kind) || !recovered) {
            return null;
        }
        if (points.get(a).hostCapture() && !points.get(b).hostCapture()) {
            return "host_transmit_path_loss";
        }
        if (points.get(b).hostCapture() && !points.get(a).hostCapture()) {
            return "host_receive_path_loss";
        }
        return null;
    }

    static boolean pyEq(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if ((a instanceof Long || a instanceof Double) && (b instanceof Long || b instanceof Double)) {
            return PyOps.numEq(a, b);
        }
        return a.equals(b);
    }

    @SuppressWarnings("unchecked")
    static void polishFindings(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        List<Map<String, Object>> findings = ctx.findings;
        Map<String, Topology.Point> points = ctx.topology.pointMap();
        Map<Object, Map<String, Object>> hops = new LinkedHashMap<>();
        for (Map<String, Object> s : ctx.segments) {
            hops.put(s.get("id"), s);
        }
        for (Map<String, Object> f : findings) {
            Map<String, Object> s = hops.get(f.get("hop"));
            if (s == null) {
                continue;
            }
            Map<String, Object> m = PyOps.map(f.get("metrics"));
            String kind = hostLossType(points, (String) s.get("point_a"), (String) s.get("point_b"), (String) f.get("type"),
                    pyEq(PyOps.get(m, "delivered_retries", 0L), m.get("count")) && pyEq(PyOps.get(m, "data_count", 0L), m.get("count")));
            if (kind != null) {
                m.put("recovery_class", f.get("type"));
                f.put("type", kind);
                f.put("cause", "host_capture_boundary");
                String text = PyOps.str(m.get("count")) + " " + kind.replace('_', ' ') + " event(s) between "
                        + ((String) s.get("label")).replace(" → ", " and ")
                        + "; original byte range absent across the boundary, later retry delivered.";
                f.put("headline", text);
                f.put("summary", text);
                f.put("tooltip", "Host/wire boundary localisation, not proof of a NIC, driver or application cause. Capture-miss and uncertain events keep their original classification.");
            }
        }
        List<Map<String, Object>> delays = new ArrayList<>();
        for (Map<String, Object> f : findings) {
            if (List.of("host_receive_delay", "host_transmit_delay").contains(f.get("type")) && "supported".equals(f.get("confidence"))
                    && PyOps.truthy(PyOps.map(f.get("metrics")).get("window"))) {
                delays.add(f);
            }
        }
        Map<String, Object> dominant = null;
        if (!delays.isEmpty()) {
            Map<String, Object> candidate = PyOps.maxBy(delays, PyOps.byNum(f -> PyOps.sub(PyOps.map(f.get("metrics")).get("max_ms"),
                    PyOps.map(f.get("metrics")).get("uncertainty_ms"))));
            Map<String, Object> cm = PyOps.map(candidate.get("metrics"));
            Object added = PyOps.sub(cm.get("max_ms"), cm.get("uncertainty_ms"));
            List<Object> competing = new ArrayList<>();
            for (Map<String, Object> i : (List<Map<String, Object>>) PyOps.get(te.onsets, "items", new ArrayList<>())) {
                if ("latency_p95_ms".equals(i.get("metric")) && !"host_capture_boundary".equals(i.get("scope"))) {
                    Map<String, Object> hop = hops.containsKey(i.get("segment")) ? hops.get(i.get("segment")) : Map.of();
                    Object value = PyOps.truthy(hop.get("max_ms")) ? hop.get("max_ms") : PyOps.get(i, "value", 0L);
                    competing.add(PyOps.sub(value, PyOps.get(i, "baseline_value", 0L)));
                }
            }
            Object best = competing.isEmpty() ? 0L : Onsets.maxOf(competing);
            if (PyOps.gt(added, best)) {
                dominant = candidate;
                dominant.put("severity", "high");
                cm.put("primary_degradation", true);
                hops.get(dominant.get("hop")).put("severity", "high");
                te.onsets.put("summary", dominant.get("headline"));
                if (!"unknown".equals(PyOps.map(cm.get("window")).get("status"))) {
                    te.onsets.put("status", "detected");
                    te.onsets.put("global_event_window", cm.get("window"));
                }
            }
        }
        Map<String, Object> chosen = dominant;
        findings.sort((x, y) -> {
            int c = Boolean.compare(x != chosen, y != chosen);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(RANK.get((String) x.get("severity")), RANK.get((String) y.get("severity")));
            return c != 0 ? c : CoreReport.compareNullable(CoreReport.t0(x), CoreReport.t0(y));
        });
        te.primaryHost = dominant;
    }

    // ------------------------------------------------------------------ classify_chronic_losses

    @SuppressWarnings("unchecked")
    static void classifyChronicLosses(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        Db db = ctx.db;
        Map<String, Object> hostReport = te.hostReport;
        Map<String, Object> onsets = te.onsets;
        List<Map<String, Object>> findings = ctx.findings;
        Double start = ctx.start;
        Double end = ctx.end;
        Map<String, Object> window = (Map<String, Object>) hostReport.get("window");
        if (!PyOps.truthy(window) || "unknown".equals(window.get("status"))) {
            window = (Map<String, Object>) onsets.get("global_event_window");
        }
        if (!PyOps.truthy(window) || "unknown".equals(window.get("status"))) {
            Map<String, Object> netOnset = null;
            for (Map<String, Object> i : (List<Map<String, Object>>) PyOps.get(onsets, "items", new ArrayList<>())) {
                Object windowEnd = PyOps.truthy(i.get("window_end")) ? i.get("window_end") : i.get("end");
                if (List.of("network_segment", "host_capture_boundary").contains(i.get("scope")) && i.get("time") != null
                        && windowEnd != null) {
                    netOnset = i;
                    break;
                }
            }
            if (netOnset != null) {
                Object windowEnd = PyOps.truthy(netOnset.get("window_end")) ? netOnset.get("window_end") : netOnset.get("end");
                window = dict("start", netOnset.get("time"), "time", netOnset.get("time"), "end", windowEnd, "status", "detected");
                if (!PyOps.truthy(hostReport.get("window"))) {
                    hostReport.put("window", window);
                }
            }
        }
        if (!PyOps.truthy(window) || "unknown".equals(window.get("status"))) {
            return;
        }
        Object wStart = window.get("start");
        Object wEnd = window.get("end");
        if (start == null || end == null || wStart == null || wEnd == null) {
            return;
        }
        if (!(PyOps.lt(start, wStart) && PyOps.lt(wStart, wEnd) && PyOps.lt(wEnd, end))) {
            return;
        }
        Set<String> lossTypes = Set.of("impactful_loss", "recovered_loss", "unrecovered_loss", "confirmed_device_drop",
                "host_transmit_path_loss", "host_receive_path_loss");
        String labelText = "chronic low-rate loss (not event-correlated)";
        Object durBefore = PyOps.max(1e-6, PyOps.sub(wStart, start));
        Object durDuring = PyOps.max(1e-6, PyOps.sub(wEnd, wStart));
        Object durAfter = PyOps.max(1e-6, PyOps.sub(end, wEnd));
        for (Map<String, Object> s : ctx.segments) {
            if (PyOps.truthy(s.get("reason")) || !PyOps.truthy(s.get("eligible_packets")) || s.get("loss_percent") == null) {
                continue;
            }
            Row stats = db.rows(Sql.get("host_path", "chronic_stats"), wStart, wStart, wEnd, wEnd, s.get("point_a"), s.get("point_b"),
                    s.get("direction")).get(0);
            if (!(PyOps.gt(stats.get("drops_before"), 0L) && PyOps.gt(stats.get("drops_during"), 0L)
                    && PyOps.gt(stats.get("drops_after"), 0L))) {
                continue;
            }
            Row totals = db.rows(Sql.get("host_path", "chronic_totals"), wStart, wStart, wEnd, wEnd, s.get("point_a"),
                    s.get("direction"), start, start, end, end).get(0);
            if (!(PyOps.gt(totals.get("total_before"), 0L) && PyOps.gt(totals.get("total_during"), 0L)
                    && PyOps.gt(totals.get("total_after"), 0L))) {
                continue;
            }
            double rateBefore = PyOps.div(PyOps.mul(100.0, stats.get("drops_before")), totals.get("total_before"));
            double rateDuring = PyOps.div(PyOps.mul(100.0, stats.get("drops_during")), totals.get("total_during"));
            double rateAfter = PyOps.div(PyOps.mul(100.0, stats.get("drops_after")), totals.get("total_after"));
            double lpsBefore = PyOps.div(stats.get("drops_before"), durBefore);
            double lpsDuring = PyOps.div(stats.get("drops_during"), durDuring);
            double lpsAfter = PyOps.div(stats.get("drops_after"), durAfter);
            double maxRate = max3(rateBefore, rateDuring, rateAfter);
            if (PyOps.ge(s.get("loss_percent"), 1.0) || maxRate >= 1.0) {
                continue;
            }
            double pctRatio = maxRate / Math.max(1e-9, min3(rateBefore, rateDuring, rateAfter));
            double lpsRatio = max3(lpsBefore, lpsDuring, lpsAfter) / Math.max(1e-9, min3(lpsBefore, lpsDuring, lpsAfter));
            boolean notSpiking = rateDuring <= 2.5 * Math.min(rateBefore, rateAfter) || lpsDuring <= 2.5 * Math.min(lpsBefore, lpsAfter);
            if (!((pctRatio <= 3.0 || lpsRatio <= 3.0) && notSpiking)) {
                continue;
            }
            Object totalDrops = stats.get("total_drops");
            Object medRto = stats.get("median_recovery_ms");
            String typicalRecovery = medRto != null
                    ? PyOps.str(totalDrops) + " drops, ~" + PyRound.round(PyOps.dbl(medRto)) + " ms RTO each"
                    : PyOps.str(totalDrops) + " drops, unrecovered";
            Map<String, Object> periodRates = dict("before_loss_percent", PyRound.round(rateBefore, 4), "during_loss_percent",
                    PyRound.round(rateDuring, 4), "after_loss_percent", PyRound.round(rateAfter, 4), "before_per_s",
                    PyRound.round(lpsBefore, 3), "during_per_s", PyRound.round(lpsDuring, 3), "after_per_s", PyRound.round(lpsAfter, 3));
            String rateText = "before " + PyOps.fmt(rateBefore, ".3f") + "% (" + PyOps.fmt(lpsBefore, ".2f") + "/s), during "
                    + PyOps.fmt(rateDuring, ".3f") + "% (" + PyOps.fmt(lpsDuring, ".2f") + "/s), after " + PyOps.fmt(rateAfter, ".3f")
                    + "% (" + PyOps.fmt(lpsAfter, ".2f") + "/s)";
            s.put("chronic_loss", true);
            s.put("loss_suspect", false);
            s.put("loss_class", labelText);
            s.put("typical_recovery", typicalRecovery);
            s.put("period_rates", periodRates);
            Object uncertainty = PyOps.truthy(s.get("latency_uncertainty_ms")) ? s.get("latency_uncertainty_ms")
                    : PyOps.truthy(s.get("offset_uncertainty_ms")) ? s.get("offset_uncertainty_ms") : 0L;
            if (!PyOps.truthy(s.get("reason")) && s.get("p95_ms") != null && s.get("max_ms") != null
                    && PyOps.lt(PyOps.add(s.get("p95_ms"), uncertainty), 1L) && PyOps.lt(PyOps.add(s.get("max_ms"), uncertainty), 10L)) {
                s.put("chronic_loss_summary", "transit clean; chronic loss " + PyOps.fmt(s.get("loss_percent"), ".3f") + "%");
            }
            List<Map<String, Object>> matching = new ArrayList<>();
            for (Map<String, Object> f : findings) {
                if (Objects.equals(f.get("hop"), s.get("id")) && lossTypes.contains(f.get("type"))) {
                    matching.add(f);
                }
            }
            if (!matching.isEmpty()) {
                Map<String, Object> primary = matching.get(0);
                String summaryText = s.get("label") + ": " + labelText + " — " + typicalRecovery + "; rate per period: " + rateText + ".";
                primary.put("chronic_loss", true);
                primary.put("loss_class", labelText);
                primary.put("severity", "low");
                primary.put("summary", summaryText);
                primary.put("headline", summaryText);
                Map<String, Object> m = PyOps.map(primary.get("metrics"));
                m.put("chronic_loss", true);
                m.put("loss_class", labelText);
                m.put("typical_recovery", typicalRecovery);
                m.put("period_rates", periodRates);
                m.put("count", totalDrops);
                for (Map<String, Object> extra : matching.subList(1, matching.size())) {
                    for (int k = 0; k < findings.size(); k++) {
                        if (findings.get(k) == extra) {
                            findings.remove(k);
                            break;
                        }
                    }
                }
            }
        }
    }

    private static double max3(double a, double b, double c) {
        double m = a;
        if (b > m) {
            m = b;
        }
        if (c > m) {
            m = c;
        }
        return m;
    }

    private static double min3(double a, double b, double c) {
        double m = a;
        if (b < m) {
            m = b;
        }
        if (c < m) {
            m = c;
        }
        return m;
    }

    // ------------------------------------------------------------------ group_host_losses

    @SuppressWarnings("unchecked")
    static void groupHostLosses(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        Map<String, Topology.Point> points = ctx.topology.pointMap();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> s : ctx.segments) {
            Topology.Point pa = points.get(s.get("point_a"));
            Topology.Point pb = points.get(s.get("point_b"));
            if (!(pa.hostCapture() || pb.hostCapture())) {
                continue;
            }
            List<Map<String, Object>> members = new ArrayList<>();
            for (Map<String, Object> f : ctx.findings) {
                if (Objects.equals(f.get("hop"), s.get("id")) && List.of("impactful_loss", "recovered_loss", "unrecovered_loss",
                        "host_transmit_path_loss", "host_receive_path_loss").contains(f.get("type"))) {
                    members.add(f);
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            Map<String, Object> counts = new LinkedHashMap<>();
            for (Map<String, Object> f : members) {
                String type = (String) f.get("type");
                counts.put(type, PyOps.add(PyOps.get(counts, type, 0L), PyOps.get(PyOps.map(f.get("metrics")), "count", 0L)));
            }
            String role = pa.hostCapture() ? "TX" : "RX";
            String text = "Loss between " + pa.label() + " and " + pb.label() + " (host " + role
                    + " path or network; no mirror point in between)";
            List<Object> ids = new ArrayList<>();
            for (Map<String, Object> f : members) {
                ids.add(f.get("id"));
            }
            groups.add(dict("hop", s.get("id"), "headline", text, "counts", counts, "finding_ids", ids, "evidence",
                    members.get(0).get("evidence")));
            for (Map<String, Object> f : members) {
                PyOps.map(f.get("metrics")).put("boundary_loss_heading", text);
            }
        }
        te.hostReport.put("loss_groups", groups);
    }
}
