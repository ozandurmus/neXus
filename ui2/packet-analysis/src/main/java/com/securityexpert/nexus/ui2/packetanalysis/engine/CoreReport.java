package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reference {@code analysis/report.py}: coverage/window, verdict, order suggestion, lead degradation, assembly. */
final class CoreReport {
    static final List<String> LIMITATIONS = List.of(
            "Observed coverage does not prove uninterrupted capture.",
            "Full proxies link requests, never TCP packets. Ambiguous request candidates remain unknown.",
            "SNI pairs visible TLS setup only; encrypted HTTP request boundaries and TLS 1.3 certificates remain unknown.",
            "Clock domains separated by a proxy cannot establish proxy dwell or cross-domain onset order without shared clock evidence.",
            "HTTP/1.x response association requires complete SYN-anchored contiguous decoded request/response ranges; chunked or coalesced messages remain unknown.",
            "Application indexing isolates capture points, uses two tshark passes and caches at most 10,000 events per point; narrow the capture when this budget is exceeded.",
            "Latency is estimated under minimum-path symmetry; offsets include possible path asymmetry.",
            "SPAN duplicates, unresolved occurrence collisions, ambiguous/incomplete fragment observations and unsupported transports are excluded from packet matching; whole-datagram disappearance is assessed separately.",
            "TCP sessions are joined by shared occurrences; streams without a shared packet stay separate.",
            "Unrecovered disappearance cannot confirm a device drop without positive device evidence.");
    static final Map<String, Integer> RANK = Map.of("high", 0, "low", 1, "quality", 2, "unknown", 3);

    private CoreReport() {
    }

    static void suggestionEvidence(EngineContext ctx) {
        for (Map<String, Object> s : ctx.suggestions) {
            s.put("evidence", Evidence.evidence(ctx.db, "(o.point=? AND o.frame=?) OR (o.point=? AND o.frame=?)",
                    Arrays.asList(s.get("point_a"), s.get("frame_a"), s.get("point_b"), s.get("frame_b"))));
        }
    }

    static void coverageAndWindow(EngineContext ctx) {
        Topology topology = ctx.topology;
        Set<String> used = new LinkedHashSet<>(topology.forward);
        used.addAll(topology.reverse);
        ctx.coverage = ctx.db.rows(Sql.get("report", "coverage"), new ArrayList<>(used));
        boolean known = ctx.coverage.stream().allMatch(x -> x.get("start") != null) && ctx.coverage.size() == used.size();
        Double commonStart = null;
        Double commonEnd = null;
        if (known) {
            for (Row x : ctx.coverage) {
                commonStart = commonStart == null ? x.dbl("start") : Evidence.pyMax(commonStart, x.dbl("start"));
                commonEnd = commonEnd == null ? x.dbl("end") : Evidence.pyMin(commonEnd, x.dbl("end"));
            }
        }
        if (commonStart != null && commonStart >= commonEnd) {
            commonStart = null;
            commonEnd = null;
        }
        ctx.commonStart = commonStart;
        ctx.commonEnd = commonEnd;
        ctx.start = topology.start != null ? topology.start : commonStart;
        ctx.end = topology.end != null ? topology.end : commonEnd;
    }

    @SuppressWarnings("unchecked")
    static void eventsAndInventories(EngineContext ctx) {
        ctx.db.exec(Sql.get("report", "events"));
        ctx.inventories.clear();
        for (Object[] r : ctx.db.tuples(Sql.get("report", "inventories"))) {
            ctx.inventories.put((String) r[0], (Map<String, Object>) Evidence.loads((String) r[1]));
        }
    }

    /** The reference ranks findings before add_headlines: (severity rank, first time), stable. */
    static void rankFindings(EngineContext ctx) {
        ctx.findings.sort((x, y) -> {
            int c = Integer.compare(RANK.get((String) x.get("severity")), RANK.get((String) y.get("severity")));
            return c != 0 ? c : compareNullable(t0(x), t0(y));
        });
    }

    @SuppressWarnings("unchecked")
    static Double t0(Map<String, Object> f) {
        Object v = ((List<Object>) f.get("time_range")).get(0);
        return v == null ? null : ((Number) v).doubleValue();
    }

    static int compareNullable(Double a, Double b) {
        if (a == null || b == null) {
            if (a == null && b == null) {
                return 0;
            }
            throw new IllegalArgumentException("'<' not supported between instances of 'NoneType' and 'float'");
        }
        return a < b ? -1 : a > b ? 1 : 0;
    }

    static String selectVerdict(TimeEvents te, List<Map<String, Object>> findings, boolean inconclusiveQuality,
            List<Map<String, Object>> segments, PathState path) {
        if (Evidence.truthy(te.primaryHost)) {
            return (String) te.primaryHost.get("headline");
        }
        if (te.securityFindings.stream().anyMatch(f -> "supported".equals(f.get("confidence")))) {
            return "Security heuristic patterns observed; interpretation required";
        }
        if (!te.hostFindings.isEmpty()) {
            return "Host receive/transmit path delay observed; application timing reported separately";
        }
        List<Map<String, Object>> pathFindings = new ArrayList<>(path.integrityFindings);
        pathFindings.addAll(path.proxyFindings);
        pathFindings.addAll(path.tlsFindings);
        if (pathFindings.stream().anyMatch(f -> "supported".equals(f.get("confidence")) && !"quality".equals(f.get("severity")))) {
            return "Path integrity findings observed";
        }
        if (findings.stream().anyMatch(f -> "confirmed_device_drop".equals(f.get("type")))) {
            return "Confirmed device drops observed";
        }
        if (findings.stream().anyMatch(f -> "impactful_loss".equals(f.get("type")))) {
            return "Impactful loss observed";
        }
        if (findings.stream().anyMatch(f -> "handshake_blocked".equals(f.get("type")))) {
            return "Handshake failure observed";
        }
        Object sessionSummary = te.sessionReport.get("summary");
        if (Evidence.truthy(sessionSummary)) {
            return "Session events (no network path degradation): " + sessionSummary;
        }
        if (findings.stream().anyMatch(f -> "low".equals(f.get("severity")))) {
            return "Loss observed";
        }
        if (inconclusiveQuality || segments.stream().anyMatch(s -> Evidence.truthy(s.get("reason")))
                || findings.stream().anyMatch(f -> "unknown".equals(f.get("severity")))) {
            return "Inconclusive";
        }
        return "No supported network loss in the selected window";
    }

    @SuppressWarnings("unchecked")
    private static Object[] score(Map<String, Object> f) {
        Map<String, Object> m = (Map<String, Object>) f.get("metrics");
        Object magnitude = firstTruthy(m.get("max_ms"), m.get("max_recovery_ms"), m.get("max_stall_ms"));
        PyNum mag = magnitude == null ? new PyInt(0) : Segments.num(magnitude);
        Object count = m.containsKey("count") ? m.get("count") : 1L;
        PyNum c = Segments.num(count);
        PyNum factor = PyArith.gt(c, new PyInt(1)) ? c : new PyInt(1);
        return new Object[] {Evidence.truthy(m.get("primary_degradation")),
            !(Evidence.truthy(m.get("chronic_loss")) || Evidence.truthy(f.get("chronic_loss"))), Evidence.truthy(m.get("window")),
            PyArith.mul(mag, factor)};
    }

    private static Object firstTruthy(Object... values) {
        for (Object v : values) {
            if (Evidence.truthy(v)) {
                return v;
            }
        }
        return null;
    }

    private static int compareScore(Object[] x, Object[] y) {
        for (int i = 0; i < 3; i++) {
            int c = Boolean.compare((Boolean) x[i], (Boolean) y[i]);
            if (c != 0) {
                return c;
            }
        }
        return PyArith.compare((PyNum) x[3], (PyNum) y[3]);
    }

    /** host_path.lead_degradation on the report: only core finding types exist in this engine version. */
    @SuppressWarnings("unchecked")
    static void leadDegradation(Map<String, Object> report, List<Map<String, Object>> findings) {
        Set<String> allowed = Set.of("cycle_wait", "host_receive_delay", "host_transmit_delay", "host_transmit_path_loss",
                "host_receive_path_loss", "impactful_loss", "recovered_loss", "confirmed_device_drop");
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> f : findings) {
            if (allowed.contains(f.get("type")) && "supported".equals(f.get("confidence"))) {
                candidates.add(f);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        Map<String, Object> primary = candidates.get(0);
        Object[] best = score(primary);
        for (Map<String, Object> f : candidates) {
            Object[] s = score(f);
            if (compareScore(s, best) > 0) {
                primary = f;
                best = s;
            }
        }
        Map<String, Object> chosen = primary;
        findings.sort((x, y) -> {
            int c = Boolean.compare(x != chosen, y != chosen);
            if (c != 0) {
                return c;
            }
            Map<String, Object> mx = (Map<String, Object>) x.get("metrics");
            Map<String, Object> my = (Map<String, Object>) y.get("metrics");
            c = Boolean.compare(Evidence.truthy(x.get("chronic_loss")) || Evidence.truthy(mx.get("chronic_loss")),
                    Evidence.truthy(y.get("chronic_loss")) || Evidence.truthy(my.get("chronic_loss")));
            if (c != 0) {
                return c;
            }
            c = Boolean.compare(!List.of("high", "low").contains(x.get("severity")),
                    !List.of("high", "low").contains(y.get("severity")));
            return c != 0 ? c : compareNullable(t0(x), t0(y));
        });
        report.put("findings", new ArrayList<>(findings.subList(0, Math.min(200, findings.size()))));
        report.put("finding_count", (long) findings.size());
        Object hostPath = report.get("host_path");
        boolean streams = hostPath instanceof Map<?, ?> hp && Evidence.truthy(hp.get("streams"));
        boolean hostCandidate = candidates.stream().anyMatch(f -> ((String) f.get("type")).startsWith("host_"));
        if ("cycle_wait".equals(primary.get("type")) || streams || hostCandidate) {
            report.put("verdict", Evidence.truthy(primary.get("headline")) ? primary.get("headline") : primary.get("summary"));
        } else if ("Path integrity findings observed".equals(report.get("verdict"))) {
            report.put("verdict", Evidence.truthy(primary.get("headline")) ? primary.get("headline") : primary.get("summary"));
        }
    }

    // ------------------------------------------------------------------ assembly

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    static Map<String, Object> assemble(EngineContext ctx, String engineVersion, String generatedAt) {
        Db db = ctx.db;
        long totalFindings = ctx.findings.size();
        List<Row> orderScores = db.rows(Sql.get("report", "order_scores"));
        List<Object> orderPoints = new ArrayList<>();
        for (Row r : orderScores) {
            orderPoints.add(r.get("point"));
        }
        Map<String, Object> autoOrder = map("points", orderPoints, "scores", orderScores, "reason",
                "Suggestion from median TTL and corrected timestamps of shared packets. Your saved drawing remains authoritative.");
        boolean inconclusiveQuality = ctx.quality.stream().anyMatch(q -> q.get("unknown_direction").equals(q.get("packets"))
                || q.get("excluded").equals(q.get("packets")));
        Map<String, Object> prefilter = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : ctx.inventories.entrySet()) {
            Object v = e.getValue().get("prefilter_excluded");
            prefilter.put(ctx.captureNames.get(e.getKey()), v != null || e.getValue().containsKey("prefilter_excluded") ? v : 0L);
        }
        Map<String, Object> clocks = new LinkedHashMap<>();
        for (Map.Entry<String, ClockModel> e : ctx.models.entrySet()) {
            clocks.put(e.getKey(), e.getValue().json());
        }
        TimeEvents te = TimeEvents.of(ctx);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schema_version", 2L);
        r.put("prefilter_excluded", prefilter);
        r.put("analysis_scope", db.rows(Sql.get("report", "analysis_scope")));
        r.put("tunnel_points", db.rows(Sql.get("capture_layers", "tunnel_report")));
        r.put("fragment_points", ctx.path.fragmentQuality);
        r.put("microbursts", te.microbursts);
        r.put("sessions", te.sessions);
        r.put("session_events", te.sessionReport);
        r.put("security", te.securityReport);
        r.put("udp", ctx.sections.get("udp"));
        r.put("rtp", ctx.sections.get("rtp"));
        r.put("sip", ctx.sections.get("sip"));
        r.put("host_path", te.hostReport);
        r.put("cycles", te.cycleReport);
        r.put("incident", te.incident);
        r.put("delivery", te.delivery);
        r.put("application_status", ctx.applicationStatus);
        r.put("proxies", ctx.path.proxies);
        r.put("tls", ctx.path.tlsNotes);
        r.put("integrity_notes", ctx.path.integrityNotes);
        r.put("f5", ctx.sections.get("f5"));
        r.put("vendor_device_events", te.deviceReport.getOrDefault("vendor_device_events", new ArrayList<>()));
        r.put("vendor_device_event_count", te.deviceReport.getOrDefault("vendor_device_event_count", 0L));
        r.put("confirmed_device_drops", te.deviceReport.getOrDefault("confirmed_device_drops", new ArrayList<>()));
        r.put("confirmed_device_drop_count", te.deviceReport.getOrDefault("confirmed_device_drop_count", 0L));
        r.put("sequence_translations", sequenceReport(db));
        r.put("offload_points", ctx.byteActive ? db.rows(Sql.get("byte_ranges", "range_notes")) : new ArrayList<>());
        r.put("timeseries", te.timeseries);
        r.put("onsets", te.onsets);
        r.put("engine_version", engineVersion);
        r.put("generated_at", generatedAt);
        r.put("verdict", selectVerdict(te, ctx.findings, inconclusiveQuality, ctx.segments, ctx.path));
        r.put("scope", "Pilot diagnostics; stage clocks, host boundary delay and evidence-led path analysis");
        r.put("window", map("start", ctx.start, "end", ctx.end, "common_start", ctx.commonStart, "common_end", ctx.commonEnd));
        r.put("clocks", clocks);
        r.put("coverage", ctx.coverage);
        r.put("quality", ctx.quality);
        r.put("auto_order_suggestion", autoOrder);
        r.put("nat_suggestions", ctx.suggestions);
        r.put("segments", ctx.segments);
        r.put("findings", new ArrayList<>(ctx.findings.subList(0, Math.min(200, ctx.findings.size()))));
        r.put("finding_count", totalFindings);
        r.put("flow_count", db.count(Sql.get("report", "flow_count")));
        List<String> limitations = new ArrayList<>(LIMITATIONS);
        if (ctx.scopeExcludedSinglePointFlows > 0 || !ctx.hopScope.isEmpty()) {
            limitations.add(NeighbourScope.LIMITATION);  // not in the reference (NeighbourScope)
        }
        r.put("limitations", limitations);
        leadDegradation(r, ctx.findings);
        r.put("unknown_sections", new LinkedHashMap<>(ctx.unknownSections));
        r.put("engine_notes", new ArrayList<>(ctx.engineNotes));
        if (db.tableExists("settings")) {
            if (ctx.topology.raw != null) {
                var rawCopy = ctx.topology.raw.deepCopy();
                if (rawCopy instanceof com.fasterxml.jackson.databind.node.ObjectNode obj && !obj.hasNonNull("stall_ms")) {
                    obj.put("stall_ms", 200L);
                }
                saveSetting(db, "topology", rawCopy);
            }
            saveSetting(db, "export_findings", ctx.findings);
            saveSetting(db, "report", r);
        }
        return r;
    }

    private static void saveSetting(Db db, String key, Object value) {
        db.exec("DELETE FROM settings WHERE key = ?", key);
        db.exec("INSERT INTO settings VALUES (?, ?)", key,
                com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter.dumps(value, false));
    }

    static List<Row> sequenceReport(Db db) {
        List<Row> result = db.rows(Sql.get("translation", "sequence_report"));
        for (Row model : result) {
            model.put("evidence", Evidence.evidence(db, "(o.point=? AND o.frame=?) OR (o.point=? AND o.frame=?)",
                    Arrays.asList(model.get("ingress"), model.get("frame_a"), model.get("egress"), model.get("frame_b")), 4));
        }
        return result;
    }
}
