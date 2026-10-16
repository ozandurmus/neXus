package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static com.securityexpert.nexus.ui2.packetanalysis.engine.PyOps.dict;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reference {@code security.py}: observed TCP lifecycle and conservative, local stateful-device heuristics. */
final class Security {
    static final String LIFECYCLE_NOTE = "Observed SYN-anchored sessions, not the device's internal table. SYN retries count once; "
            + "established requires a sequence-consistent SYN/SYNACK/final ACK at the same point. "
            + "Half-open occupancy ends at establishment, RST, FIN or the configured observation timeout. "
            + "FIN means closure started, not proof both endpoints released state. Missing SYN is unknown. "
            + "Capture absence, timeout and source diversity do not prove intent or resource exhaustion.";
    static final String SECURITY_NOTE = "Heuristics describe observed traffic, never an attack or proven device capacity failure. "
            + "Rates use distinct SYN-anchored sessions and completed observation deadlines; source spread "
            + "is not proof of spoofing. Healthy completion elsewhere makes a local missing handshake unknown. "
            + "Capture quality, matching, clock and complete coverage gate absence-based claims.";
    static final Set<String> STATEFUL = Set.of("Firewall", "IPS", "Load Balancer", "Proxy", "WAF");

    private Security() {
    }

    // ------------------------------------------------------------------ lifecycle

    static void lifecycle(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        te.sessions = buildLifecycle(ctx, te);
    }

    static Map<String, Object> buildLifecycle(EngineContext ctx, TimeEvents te) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Object timeout = te.sessionTimeoutSeconds;
        db.exec(Sql.get("security", "session_syns"));
        boolean hasSyn = db.one(Sql.get("security", "has_syn")) != null;
        String source = hasSyn ? "obs" : "(SELECT * FROM obs WHERE FALSE)";
        db.exec(Sql.fmt("security", "session_replies", "source", source));
        db.exec(Sql.fmt("security", "session_acks", "source", source));
        db.exec(Sql.fmt("security", "session_lifecycle", "source", source, "timeout", PyOps.str(timeout)));
        Map<String, Object> series = te.timeseries;
        Object origin = series.get("start");
        Object width = te.bucketSeconds;
        Object count = PyOps.get(series, "bucket_count", 0L);
        Map<Object, Row> bypoint = new LinkedHashMap<>();
        for (Row c : ctx.coverage) {
            bypoint.put(c.get("point"), c);
        }
        db.exec(Sql.get("security", "session_points"));
        db.execMany(Sql.get("security", "session_points_insert"), pointRows(topology, bypoint));
        db.exec(Sql.get("security", "session_buckets"));
        if (origin != null && PyOps.truthy(count)) {
            db.exec(Sql.fmt("security", "session_buckets_insert", "origin", PyOps.str(origin), "width", PyOps.str(width),
                    "count", PyOps.str(count)));
        }
        Map<String, Object> window = dict("start", ctx.start, "end", ctx.end);
        db.exec(Sql.get("security", "lifecycle_window"));
        db.exec(Sql.get("security", "lifecycle_window_insert"), window.get("start"), window.get("end"));
        Map<Object, Row> indexed = new LinkedHashMap<>();
        for (Row p : db.rows(Sql.get("security", "lifecycle_points"))) {
            indexed.put(p.get("point"), p);
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (Topology.Point point : topology.points) {
            Map<String, Object> p = indexed.get(point.id());
            if (p == null) {
                p = dict("point", point.id(), "syn_sent", null, "synack_seen", null, "established", null, "closed", null,
                        "rst_closed", null, "fin_closed", null, "first_frame", null);
            }
            Map<String, Object> cov = bypoint.containsKey(point.id()) ? bypoint.get(point.id()) : Map.of();
            p.put("label", point.label());
            p.put("status", p.get("syn_sent") != null && cov.get("start") != null ? "observed" : "unknown");
            p.put("tooltip", LIFECYCLE_NOTE);
            p.put("evidence", p.get("first_frame") != null
                    ? Evidence.evidence(db, "o.point=? AND o.frame=?", Arrays.asList(point.id(), p.get("first_frame")), 1)
                    : new ArrayList<>());
            points.add(p);
        }
        db.exec(Sql.get("security", "handshake_stops"));
        List<Row> failures = db.rows(Sql.get("security", "failures"));
        long failureCount = db.count(Sql.get("security", "failure_count"));
        List<Object> keys = new ArrayList<>();
        for (Row f : failures) {
            keys.add(f.get("packet_key"));
        }
        Map<List<Object>, Object> keyByFrame = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("security", "failure_frames"), keys)) {
            keyByFrame.put(List.of(r.get("point"), r.get("frame")), r.get("packet_key"));
        }
        List<Map<String, Object>> refs = Evidence.evidence(db, "o.packet_key IN (SELECT unnest(?::VARCHAR[]))",
                List.of(keys), 1600, false);
        Map<Object, List<Map<String, Object>>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> ref : refs) {
            List<Object> k = List.of(ref.get("point"), ref.get("frame"));
            if (!keyByFrame.containsKey(k)) {
                throw new IllegalStateException("KeyError: " + k);
            }
            byKey.computeIfAbsent(keyByFrame.get(k), x -> new ArrayList<>()).add(ref);
        }
        for (Row f : failures) {
            f.put("evidence", byKey.containsKey(f.get("packet_key")) ? byKey.get(f.get("packet_key")) : new ArrayList<>());
            f.put("reason", "Supported SYN or SYN/ACK stopping boundary; internal device cause unknown");
        }
        return dict("points", points, "buckets", db.rows(Sql.get("security", "session_bucket_rows")), "failed_handshakes",
                failures, "failed_handshake_count", failureCount, "timeout_seconds", timeout, "note", LIFECYCLE_NOTE, "scope",
                "Full saved analysis window; not filtered by the UI brush");
    }

    private static List<Object[]> pointRows(Topology topology, Map<Object, Row> bypoint) {
        List<Object[]> out = new ArrayList<>();
        for (Topology.Point p : topology.points) {
            Row c = bypoint.get(p.id());
            out.add(new Object[] {p.id(), c == null ? null : c.get("start"), c == null ? null : c.get("end")});
        }
        return out;
    }

    // ------------------------------------------------------------------ heuristics

    static Map<String, Object> finding(Db db, String kind, String point, Object start, Object end, String summary,
            Map<String, Object> metrics, boolean supported, String hop, String device, List<Map<String, Object>> refs) {
        List<Map<String, Object>> proof = refs != null ? refs
                : Evidence.evidence(db, "o.point=? AND o.corrected>=? AND o.corrected<? AND (o.flags&2)>0",
                        Arrays.asList(point, start, end), 16);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", "security:" + kind + ":" + point + ":" + PyOps.str(start));
        f.put("type", kind);
        f.put("hop", hop != null && !hop.isEmpty() ? hop : "observed:" + point + ":" + point);
        f.put("device", device);
        f.put("direction", hop != null && !hop.isEmpty() ? hop.split(":", -1)[0] : "observed");
        f.put("severity", supported ? "low" : "unknown");
        f.put("confidence", supported ? "supported" : "unknown");
        f.put("time_range", PyOps.list(start, end));
        f.put("summary", summary);
        f.put("headline", summary);
        f.put("tooltip", SECURITY_NOTE);
        f.put("metrics", metrics);
        f.put("evidence", proof);
        f.put("cause", "unknown");
        f.put("clock_caveat", "Clock-corrected observations; clock uncertainty and coverage apply");
        return f;
    }

    static void checks(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        te.clockJson.clear();
        for (Map.Entry<String, ClockModel> e : ctx.models.entrySet()) {
            te.clockJson.put(e.getKey(), e.getValue().json());
        }
        Object[] result = securityChecks(ctx, te);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) result[0];
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) result[1];
        te.securityFindings = findings;
        te.securityReport = report;
        ctx.findings.addAll(findings);
    }

    @SuppressWarnings("unchecked")
    static Object[] securityChecks(EngineContext ctx, TimeEvents te) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Object> series = te.timeseries;
        Object width = te.bucketSeconds;
        Object origin = series.get("start");
        Map<String, Object> thresholds = dict("syn_rate", te.securitySynRate, "completion_ratio", te.securityCompletionRatio,
                "sources", te.securityMinSources, "sustained_buckets", te.securitySustainedBuckets, "observation_timeout_seconds",
                te.sessionTimeoutSeconds, "half_open_floor", te.securityHalfOpenFloor);
        Map<Object, Row> bypoint = new LinkedHashMap<>();
        for (Row c : ctx.coverage) {
            bypoint.put(c.get("point"), c);
        }
        Map<Object, Map<String, Object>> inventories = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("security", "inventories"))) {
            inventories.put(r.get("id"), (Map<String, Object>) Evidence.loads(r.str("inventory")));
        }
        Map<String, Topology.Point> points = ctx.topology.pointMap();
        List<Map<String, Object>> notes = new ArrayList<>();
        List<String> usable = new ArrayList<>();
        for (Topology.Point p : topology.points) {
            Map<String, Object> cov = bypoint.containsKey(p.id()) ? bypoint.get(p.id()) : Map.of();
            Map<String, Object> inv = inventories.get(p.captureId());
            if (inv == null) {
                throw new IllegalStateException("KeyError: " + p.captureId());
            }
            String reason = null;
            Map<String, Object> clock = te.clockJson.get(p.captureId());
            if (cov.get("start") == null || cov.get("end") == null || clock == null || clock.get("uncertainty") == null) {
                reason = "Clock or complete point coverage unavailable";
            } else if (List.of("ifdrop", "osdrop", "damaged_tail", "truncated_tail", "timestamp_excluded_counts").stream()
                    .anyMatch(k -> PyOps.truthy(inv.get(k)))) {
                reason = "Capture quality prevents absence-based security inference";
            } else if (ctx.segments.stream().anyMatch(s -> (p.id().equals(s.get("point_a")) || p.id().equals(s.get("point_b")))
                    && PyOps.truthy(s.get("reason")))) {
                reason = "Adjacent segment matching/coverage/translation insufficient";
            }
            if (reason != null) {
                notes.add(dict("point", p.id(), "status", "unknown", "reason", reason));
            } else {
                usable.add(p.id());
            }
        }
        Object maxSessions = db.scalar(Sql.get("security", "max_sessions"));
        Object floor = te.securityScanTargets;
        floor = PyOps.min(floor, te.securityHalfOpenFloor);
        floor = PyOps.min(floor, PyOps.mul(te.securitySynRate, width));
        if (PyOps.lt(maxSessions, floor)) {
            for (String table : List.of("security_cohorts", "security_fanout", "pressure_spikes")) {
                db.exec(Sql.fmt("security", "drop_table", "table", table));
            }
            thresholds.put("scan_window_seconds", te.securityScanWindowSeconds);
            thresholds.put("scan_targets", te.securityScanTargets);
            thresholds.put("scan_failed_ratio", te.securityScanFailedRatio);
            thresholds.put("half_open_floor", te.securityHalfOpenFloor);
            return new Object[] {new ArrayList<Map<String, Object>>(), dict("note", SECURITY_NOTE, "thresholds", thresholds,
                    "quality", notes, "status", "Below all heuristic event floors", "max_observed_syn_sessions_per_point",
                    maxSessions)};
        }
        db.exec(Sql.get("security", "security_sessions"));
        db.exec(Sql.get("security", "security_cohorts"));
        List<Map<String, Object>> findings = new ArrayList<>();
        if (origin == null) {
            return new Object[] {findings, dict("note", SECURITY_NOTE, "thresholds", thresholds, "quality", notes)};
        }
        Object timeout = te.sessionTimeoutSeconds;
        db.exec(Sql.get("security", "security_points"));
        db.execMany(Sql.get("security", "security_points_insert"), pointRows(topology, bypoint));
        db.exec(Sql.fmt("security", "security_cohorts_insert", "origin", PyOps.str(origin), "width", PyOps.str(width),
                "timeout", PyOps.str(timeout), "series_end", PyOps.str(series.get("end"))), usable);
        List<Row> waves = db.rows(Sql.fmt("security", "waves", "n", PyOps.str(te.securitySustainedBuckets)),
                te.securitySynRate, te.securityMinSources, te.securityCompletionRatio);
        for (Row wave : waves) {
            Map<String, Object> metrics = new LinkedHashMap<>(wave);
            metrics.put("count", wave.get("sessions"));
            metrics.put("thresholds", thresholds);
            metrics.put("scope", "signal observed here; not a fault attribution");
            String point = wave.str("point");
            findings.add(finding(db, "syn_flood_like", point, wave.get("start"), wave.get("finish"),
                    "SYN-flood-like pattern (heuristic) at " + points.get(point).label() + ": " + PyOps.fmt(wave.get("syn_rate"), ".1f")
                            + "+ SYN sessions/s, " + PyOps.str(wave.get("sources")) + "+ observed sources over "
                            + PyOps.str(wave.get("buckets")) + " buckets; SYN/ACK ratio " + PyOps.fmt(wave.get("synack_ratio"), ".1%")
                            + ", established ratio " + PyOps.fmt(wave.get("established_ratio"), ".1%") + ".",
                    metrics, true, null, null, null));
        }
        findings.addAll(scanChecks(ctx, te, usable, series));
        findings.addAll(pressureChecks(ctx, te, usable));
        Set<Object> baselinePoints = new LinkedHashSet<>();
        for (Row r : db.rows(Sql.get("security", "baseline_points"))) {
            baselinePoints.add(r.get("point"));
        }
        for (Topology.Point p : topology.points) {
            if (STATEFUL.contains(p.kind()) && !baselinePoints.contains(p.id())) {
                notes.add(dict("point", p.id(), "status", "unknown", "reason",
                        "Insufficient early healthy cohorts for session-pressure baseline"));
            }
        }
        for (Map<String, Object> f : findings) {
            String[] parts = ((String) f.get("hop")).split(":", -1);
            Set<String> hopPoints = new LinkedHashSet<>(Arrays.asList(parts).subList(1, parts.length));
            List<Object> estimates = new ArrayList<>();
            boolean all = true;
            for (String hp : hopPoints) {
                Map<String, Object> clock = te.clockJson.get(points.get(hp).captureId());
                Object u = clock == null ? null : clock.get("uncertainty");
                estimates.add(u);
                all &= u != null;
            }
            PyOps.map(f.get("metrics")).put("clock_uncertainty_ms", all ? PyOps.mul(PyOps.sum(estimates), 1000L) : null);
        }
        thresholds.put("scan_window_seconds", te.securityScanWindowSeconds);
        thresholds.put("scan_targets", te.securityScanTargets);
        thresholds.put("scan_failed_ratio", te.securityScanFailedRatio);
        return new Object[] {findings, dict("note", SECURITY_NOTE, "thresholds", thresholds, "quality", notes)};
    }

    static List<Map<String, Object>> scanChecks(EngineContext ctx, TimeEvents te, List<String> usable, Map<String, Object> series) {
        Db db = ctx.db;
        Object width = te.securityScanWindowSeconds;
        Object timeout = te.sessionTimeoutSeconds;
        db.exec(Sql.fmt("security", "security_fanout", "width", PyOps.str(width), "series_start", PyOps.str(series.get("start")),
                "timeout", PyOps.str(timeout), "series_end", PyOps.str(series.get("end"))), usable);
        List<Row> candidates = db.rows(Sql.get("security", "scan_candidates"), te.securityScanTargets, te.securityScanFailedRatio);
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Row c : candidates) {
            List<Map<String, Object>> refs = Evidence.evidence(db,
                    "o.point=? AND o.src=? AND (o.flags&2)>0 AND o.corrected>=? AND o.corrected<=?",
                    Arrays.asList(c.get("point"), c.get("source"), c.get("start"), c.get("finish")), 16);
            Map<String, Object> metrics = new LinkedHashMap<>(c);
            metrics.put("count", c.get("sessions"));
            metrics.put("thresholds", dict("window_seconds", width, "targets", te.securityScanTargets, "failed_ratio",
                    te.securityScanFailedRatio));
            metrics.put("note", "Peak rolling-window counts; displayed interval spans qualifying windows, not one aggregate count.");
            findings.add(finding(db, "scan_like_fanout", c.str("point"), c.get("start"), c.get("finish"),
                    "scan-like fan-out (heuristic): source " + PyOps.str(c.get("source")) + " touched " + PyOps.str(c.get("targets"))
                            + " destinations/ports in a " + PyOps.fmt(width, "g") + " s rolling window with "
                            + PyOps.fmt(c.get("failed_ratio"), ".1%") + " unestablished handshakes at " + c.str("point") + ".",
                    metrics, true, null, null, refs));
        }
        return findings;
    }

    static List<Map<String, Object>> pressureChecks(EngineContext ctx, TimeEvents te, List<String> usable) {
        Db db = ctx.db;
        Object width = te.bucketSeconds;
        Object floor = te.securitySynRate;
        db.exec(Sql.fmt("security", "pressure_baseline", "width", PyOps.str(width)));
        db.exec(Sql.fmt("security", "pressure_spikes", "floor", PyOps.str(floor), "half_open_floor",
                PyOps.str(te.securityHalfOpenFloor), "width", PyOps.str(width)), usable);
        if (db.one(Sql.get("security", "any_spike")) == null) {
            return new ArrayList<>();
        }
        Map<String, Topology.Point> points = ctx.topology.pointMap();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> segment : ctx.segments) {
            String a = (String) segment.get("point_a");
            String b = (String) segment.get("point_b");
            String device = points.get(a).device().equals(points.get(b).device()) ? points.get(a).device() : null;
            if (PyOps.truthy(device) && !STATEFUL.contains(points.get(a).kind())) {
                continue;
            }
            Row event = db.rows(Sql.get("security", "segment_drops"), a, b, segment.get("direction")).get(0);
            if (event.get("onset") == null || PyOps.lt(event.get("drops"), 3L)) {
                continue;
            }
            List<Row> spikes = db.rows(Sql.get("security", "spike"), a, event.get("onset"), PyOps.sub(event.get("onset"), width));
            if (spikes.isEmpty()) {
                continue;
            }
            Row spike = spikes.get(0);
            List<Row> positive = PyOps.truthy(device)
                    ? db.rows(Sql.get("security", "positive_proofs"), a, b, segment.get("direction"), device, event.get("onset"), width)
                    : new ArrayList<>();
            boolean supported = positive.size() >= 3 && !PyOps.truthy(segment.get("reason"));
            List<Map<String, Object>> refs = new ArrayList<>(Evidence.evidence(db,
                    "o.point=? AND o.corrected>=? AND o.corrected<? AND (o.flags&2)>0",
                    Arrays.asList(a, spike.get("start"), spike.get("end")), 8));
            refs.addAll(Evidence.evidence(db, "o.packet_key=?", Arrays.asList(event.get("packet_key")), 8));
            for (Row proof : positive.subList(0, Math.min(2, positive.size()))) {
                refs.addAll(Evidence.evidence(db, "o.point=? AND o.frame=?", Arrays.asList(proof.get("point"), proof.get("frame")), 1));
            }
            String summary = supported ? "possible session-table pressure at " + PyOps.str(device) + " (corroboration, not proof)"
                    : "Session-table pressure unknown at " + (PyOps.truthy(device) ? device : (String) segment.get("label"))
                            + ": generic disappearance without aligned positive device evidence";
            List<Map<String, Object>> deviceEvidence = new ArrayList<>();
            for (Row p : positive) {
                deviceEvidence.add(dict("device", p.get("device"), "stage", p.get("stage"), "frame", p.get("frame"), "time_source",
                        p.get("time_source")));
            }
            findings.add(finding(db, "session_table_pressure", a, spike.get("start"), event.get("onset"), summary,
                    dict("count", event.get("drops"), "drop_onset", event.get("onset"), "spike", spike, "correlation_window_seconds",
                            width, "device_evidence", deviceEvidence, "conclusion", supported ? "corroboration_only" : "unknown"),
                    supported, (String) segment.get("id"), device, refs));
        }
        return findings;
    }
}
