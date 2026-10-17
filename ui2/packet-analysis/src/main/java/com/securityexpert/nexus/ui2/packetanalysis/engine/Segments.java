package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.NatMapping;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFormat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyMath;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Reference {@code analysis/segments.py} and {@code analysis/classification.py}. */
final class Segments {
    static final Map<String, String> SEVERITY = Map.of(
            "confirmed_device_drop", "high",
            "handshake_blocked", "high",
            "unrecovered_loss", "low",
            "impactful_loss", "high",
            "recovered_loss", "low",
            "capture_miss", "quality",
            "unknown", "unknown");

    private Segments() {
    }

    // ------------------------------------------------------------------ segments.py

    private static boolean hasPendingNat(EngineContext ctx, Point pa, Point pb, String a, String b) {
        Topology topology = ctx.topology;
        boolean pending = pendingSuggestion(ctx, a, b);
        if (pa.device().equals(pb.device()) && (pa.translation().equals("nat") || pb.translation().equals("nat"))) {
            List<String> confirmed = new ArrayList<>();
            for (NatMapping m : topology.natMappings) {
                if (Set.of(m.pointA(), m.pointB()).equals(Set.of(a, b))) {
                    confirmed.addAll(List.of(m.tupleA(), m.tupleB(), Tuples.reverseTuple(m.tupleA()),
                            Tuples.reverseTuple(m.tupleB())));
                }
            }
            long unresolved = ctx.db.count(Sql.get("segments", "unresolved_nat"), a, b, a, b, confirmed);
            pending = pending || unresolved > 0;
        }
        return pending;
    }

    /** A NAT suggestion between a and b that no confirmed mapping covers (first part of {@code _has_pending_nat}). */
    static boolean pendingSuggestion(EngineContext ctx, String a, String b) {
        Topology topology = ctx.topology;
        boolean pending = false;
        for (Map<String, Object> x : ctx.suggestions) {
            if (!(Matching.equalsAny(x.get("point_a"), a, b) && Matching.equalsAny(x.get("point_b"), a, b))) {
                continue;
            }
            boolean confirmed = false;
            for (NatMapping m : topology.natMappings) {
                if (m.pointA().equals(x.get("point_a")) && m.pointB().equals(x.get("point_b"))) {
                    List<Object> pair = Arrays.asList(x.get("tuple_a"), x.get("tuple_b"));
                    if (pair.equals(Arrays.asList(m.tupleA(), m.tupleB()))
                            || pair.equals(Arrays.asList(Tuples.reverseTuple(m.tupleA()), Tuples.reverseTuple(m.tupleB())))) {
                        confirmed = true;
                        break;
                    }
                }
            }
            if (!confirmed) {
                pending = true;
                break;
            }
        }
        return pending;
    }

    @SuppressWarnings("unchecked")
    private static Object[] endpointQuality(EngineContext ctx, String a, String b, String direction) {
        List<Row> qualityRows = ctx.db.rows(Sql.get("segments", "endpoint_quality"), a, b, direction, ctx.start, ctx.start,
                ctx.end, ctx.end);
        Map<String, Object> endpointQuality = new LinkedHashMap<>();
        Map<String, Object> excludedCounts = new LinkedHashMap<>();
        for (String endpoint : List.of(a, b)) {
            List<Row> q = new ArrayList<>();
            for (Row r : qualityRows) {
                if (endpoint.equals(r.get("point"))) {
                    q.add(r);
                }
            }
            long n = 0;
            long good = 0;
            Map<String, Object> excluded = new LinkedHashMap<>();
            for (Row r : q) {
                n += r.lng("count");
                if ("eligible".equals(r.get("reason"))) {
                    good += r.lng("count");
                } else {
                    excluded.put(r.str("reason"), r.get("count"));
                }
            }
            Map<String, Object> eq = new LinkedHashMap<>();
            eq.put("total", n);
            eq.put("eligible", good);
            eq.put("excluded", excluded);
            eq.put("eligible_ratio", n != 0 ? (Object) PyMath.trueDivide(good, n) : null);
            endpointQuality.put(endpoint, eq);
            Map<String, Object> inv = ctx.inventories.get(ctx.points.get(endpoint).captureId());
            Map<String, Object> timeBad = inv.get("timestamp_excluded_counts") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : new LinkedHashMap<>();
            if (!timeBad.isEmpty()) {
                PyNum totalValid = inv.containsKey("packet_count") ? num(inv.get("packet_count")) : new PyInt(0);
                PyNum bad = new PyInt(0);
                for (Object v : timeBad.values()) {
                    bad = PyArith.add(bad, num(v));
                }
                double temporal = PyArith.trueDiv(totalValid, PyArith.add(totalValid, bad)).value();
                Object current = eq.get("eligible_ratio");
                eq.put("eligible_ratio", current != null ? Evidence.pyMin((Double) current, temporal) : temporal);
                eq.put("timestamp_quality_scope", "whole capture; invalid times cannot be placed in the selected window");
                excluded.putAll(timeBad);
            }
            for (Map.Entry<String, Object> e : excluded.entrySet()) {
                Object prev = excludedCounts.get(e.getKey());
                excludedCounts.put(e.getKey(), prev == null ? e.getValue()
                        : ClockModel.json(PyArith.add(num(prev), num(e.getValue()))));
            }
        }
        List<Double> ratios = new ArrayList<>();
        for (Object q : endpointQuality.values()) {
            Object r = ((Map<String, Object>) q).get("eligible_ratio");
            if (r != null) {
                ratios.add((Double) r);
            }
        }
        double eligibleRatio = 0.0;
        if (!ratios.isEmpty()) {
            eligibleRatio = ratios.get(0);
            for (double r : ratios) {
                eligibleRatio = Evidence.pyMin(eligibleRatio, r);
            }
        }
        return new Object[] {endpointQuality, excludedCounts, eligibleRatio};
    }

    static PyNum num(Object v) {
        if (v instanceof Long l) {
            return new PyInt(l);
        }
        if (v instanceof Double d) {
            return new PyFloat(d);
        }
        if (v instanceof Boolean b) {
            return new PyInt(b ? 1 : 0);
        }
        throw new IllegalArgumentException("Not a number: " + v);
    }

    private static Object[] buildSingleSegment(EngineContext ctx, Set<String> unselected, Set<String> outer, String a,
            String b, String direction) {
        Topology topology = ctx.topology;
        Point pa = ctx.points.get(a);
        Point pb = ctx.points.get(b);
        String segmentId = direction + ":" + a + ":" + b;
        boolean unsupported = pa.device().equals(pb.device())
                && (pa.translation().equals("full_proxy") || pb.translation().equals("full_proxy"));
        boolean pendingNat = hasPendingNat(ctx, pa, pb, a, b);
        ClockModel ma = ctx.models.get(pa.captureId());
        ClockModel mb = ctx.models.get(pb.captureId());
        boolean sharedClock = pa.captureId().equals(pb.captureId())
                || (ma.sourceDomain != null && !ma.sourceDomain.isEmpty() && ma.sourceDomain.equals(mb.sourceDomain));
        boolean clocksOk = ma.offset != null && mb.offset != null && Objects.equals(ma.domain, mb.domain);
        PyNum toleranceNum = PyArith.mul(PyArith.add(PyArith.add(PyArith.add(Clocks.orZero(ma.uncertainty),
                Clocks.orZero(mb.uncertainty)), ma.timestampJitter), mb.timestampJitter), new PyInt(1000));
        double toleranceMs = ClockModel.value(toleranceNum);
        Row matched = ctx.db.rows(Sql.get("segments", "matched"),
                com.securityexpert.nexus.ui2.packetanalysis.numeric.PyBuiltins.max(0.001, toleranceMs), a, b, direction,
                ctx.start, ctx.start, ctx.end, ctx.end).get(0);
        Map<String, Object> timing = Clocks.transitQuality(matched, toleranceMs);
        boolean systematic = (Boolean) timing.get("systematic_negative");
        clocksOk = clocksOk && (sharedClock || !systematic);
        if (sharedClock && systematic) {
            @SuppressWarnings("unchecked")
            List<Object> notes = (List<Object>) timing.get("quality_notes");
            notes.add("Shared clock: negative transit concerns capture order/timestamp placement, not relative clock fitting");
        }
        String reason = unsupported ? "Unsupported translation boundary"
                : unselected.contains(a) || unselected.contains(b) ? "Tunnel matching layer undecided or unavailable; loss unknown"
                : outer.contains(a) != outer.contains(b) ? "Incompatible matching layers across tunnel boundary; loss unknown"
                : pendingNat ? "NAT mapping awaits confirmation"
                : !clocksOk ? "Clock alignment unreliable"
                : ctx.commonStart == null ? "No common capture coverage"
                : ctx.start == null || ctx.end == null || ctx.end < ctx.commonStart || ctx.start > ctx.commonEnd
                        ? "Selected window is outside common coverage"
                : null;
        if (pa.vendor().equals("checkpoint") && pb.device().equals(pa.device())) {
            Map<String, Object> inv = ctx.inventories.get(pa.captureId());
            if (!pa.inspectionComplete() || Evidence.truthy(inv.get("damaged_tail")) || Evidence.truthy(inv.get("ifdrop"))
                    || Evidence.truthy(inv.get("osdrop")) || Evidence.truthy(inv.get("timestamp_excluded_counts"))) {
                reason = "Inspection coverage incomplete or not attested";
            }
        }
        String classificationReason = reason;
        if (pa.vendor().equals("checkpoint") && pb.device().equals(pa.device())) {
            classificationReason = reason != null && !reason.isEmpty() ? reason
                    : "Inspection-stage absence has no positive device-drop proof";
        }
        Object[] eq = endpointQuality(ctx, a, b, direction);
        @SuppressWarnings("unchecked")
        Map<String, Object> excludedCounts = (Map<String, Object>) eq[1];
        double eligibleRatio = (Double) eq[2];
        if (eligibleRatio < topology.minEligibleRatio) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Object> e : new TreeMap<>(excludedCounts).entrySet()) {
                parts.add(e.getKey() + ": " + Evidence.pyStr(e.getValue()));
            }
            String detail = parts.isEmpty() ? "no observations" : String.join(", ", parts);
            reason = (reason != null && !reason.isEmpty() ? reason + "; " : "") + "Insufficient matchable packets ("
                    + PyFormat.format(100 * (1 - eligibleRatio), ".1f") + "% excluded: " + detail + ")";
        }
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("eligible_ratio", eligibleRatio);
        segment.put("excluded_counts", excludedCounts);
        segment.put("endpoint_quality", eq[0]);
        segment.put("id", segmentId);
        segment.put("point_a", a);
        segment.put("point_b", b);
        segment.put("label", pa.label() + " → " + pb.label());
        segment.put("location", pa.device().equals(pb.device()) ? "device" : "link");
        segment.put("device", pa.device().equals(pb.device()) ? pa.device() : null);
        segment.put("direction", direction);
        segment.put("reason", reason);
        segment.put("latency_confidence", reason == null || reason.isEmpty() ? "estimated" : "unknown");
        Object offsetUncertainty;
        if (sharedClock) {
            offsetUncertainty = 0.0;
        } else if (ma.uncertainty != null && mb.uncertainty != null) {
            offsetUncertainty = ClockModel.json(PyArith.mul(PyArith.add(Clocks.orZero(ma.uncertainty),
                    Clocks.orZero(mb.uncertainty)), new PyInt(1000)));
        } else {
            offsetUncertainty = null;
        }
        segment.put("offset_uncertainty_ms", offsetUncertainty);
        segment.putAll(matched);
        segment.putAll(timing);
        if (reason != null && !reason.isEmpty()) {
            for (String key : List.of("min_ms", "p50_ms", "p95_ms", "max_ms")) {
                segment.put(key, null);
            }
        }
        return new Object[] {segment, segmentId, classificationReason};
    }

    static void buildSegments(EngineContext ctx) {
        Db db = ctx.db;
        Set<String> outer = new LinkedHashSet<>();
        for (Object[] r : db.tuples(Sql.get("segments", "outer_points"))) {
            outer.add((String) r[0]);
        }
        Set<String> unselected = new LinkedHashSet<>();
        for (Object[] r : db.tuples(Sql.get("segments", "unselected_points"))) {
            unselected.add((String) r[0]);
        }
        db.exec(Sql.get("neighbour_scope", "hop_scope"));
        for (String direction : List.of("forward", "reverse")) {
            List<String> path = direction.equals("forward") ? ctx.topology.forward
                    : ctx.topology.reverse.isEmpty() ? Topology.reversed(ctx.topology.forward) : ctx.topology.reverse;
            for (int idx = 0; idx + 1 < path.size(); idx++) {
                String a = path.get(idx);
                String b = path.get(idx + 1);
                Object[] built = buildSingleSegment(ctx, unselected, outer, a, b, direction);
                @SuppressWarnings("unchecked")
                Map<String, Object> segment = (Map<String, Object>) built[0];
                List<String> downstream = new ArrayList<>(path.subList(Math.min(idx + 2, path.size()), path.size()));
                // Not in the reference (NeighbourScope): flows out of this hop's capture scope.
                long[] scope = NeighbourScope.prepareHop(ctx, a, b, direction, outer, unselected);
                if (scope[0] > 0) {
                    ctx.hopScope.put((String) built[1], scope);
                }
                classifySegmentLosses(ctx, segment, (String) built[1], a, b, direction, downstream, (String) built[2]);
                if (scope[0] > 0) {
                    segment.put("scope_excluded_flows", scope[0]);
                    segment.put("scope_excluded_packets", scope[1]);
                    @SuppressWarnings("unchecked")
                    List<Object> notes = (List<Object>) segment.get("quality_notes");
                    notes.add(String.format(java.util.Locale.ROOT, NeighbourScope.SEGMENT_NOTE, ctx.points.get(b).label(),
                            scope[0], scope[1], ctx.points.get(a).label()));
                }
                ctx.segments.add(segment);
            }
        }
    }

    // ------------------------------------------------------------------ classification.py

    private static void prepareMissingAndRecoveries(EngineContext ctx, String a, String b, String direction,
            List<String> downstream) {
        Db db = ctx.db;
        String missingCondition = Sql.get("classification", "missing_condition");
        if (ctx.byteActive) {
            missingCondition = Sql.fmt("classification", "missing_condition_bytes", "range_condition",
                    Matching.rangeCondition("a"), "missing_condition", missingCondition, "b", b);
        }
        if (NeighbourScope.excludes(ctx, a, b, direction)) {
            missingCondition = NeighbourScope.missingCondition(missingCondition, a, b, direction);
        }
        db.exec(Sql.fmt("classification", "missing", "missing_condition", missingCondition), downstream, a, direction, b,
                ctx.start, ctx.start, ctx.end, ctx.end);
        if (ctx.byteActive) {
            db.exec(Sql.get("byte_ranges", "byte_later"), downstream, downstream, a, b, direction);
            db.exec(Sql.get("byte_ranges", "byte_later_update"));
        }
        db.exec(Sql.get("fragments", "guard_missing"), b);
        db.exec(Sql.get("classification", "recoveries"), b, a);
        db.exec(Sql.get("classification", "retry_counts"));
        db.exec(Sql.get("classification", "retry_update"));
        if (ctx.byteActive) {
            db.exec(Sql.get("byte_ranges", "range_recoveries_delete"), a, b);
            db.exec(Sql.get("byte_ranges", "range_recoveries_insert"), a, b);
        }
        db.exec(Sql.get("classification", "acked"), a);
        if (ctx.byteActive) {
            for (Object[] r : db.tuples(Sql.get("byte_ranges", "ack_guard_flows"), a, b)) {
                Object flow = r[0];
                db.exec(Sql.get("byte_ranges", "ack_guard_missing"), a, b, flow);
                db.exec(Sql.get("byte_ranges", "ack_guard_times"), a, flow);
                db.exec(Sql.get("byte_ranges", "ack_guard_retries"), a, flow);
                db.exec(Sql.get("byte_ranges", "ack_guard_delete"));
            }
        }
        db.exec(Sql.get("classification", "acked_replies"));
        db.exec(Sql.get("classification", "acked_resets"), b);
        db.exec(Sql.get("classification", "failures"), ctx.commonEnd, ctx.topology.stallMs / 1000);
    }

    private static void insertSegmentEvents(EngineContext ctx, String segmentId, String a, String b, String direction,
            String classificationReason) {
        Db db = ctx.db;
        double stall = ctx.topology.stallMs;
        String sql = Sql.fmt("classification", "events_insert",
                "is_data", Sql.get("classification", ctx.byteActive ? "is_data_bytes" : "is_data"),
                "bytes", Sql.get("classification", ctx.byteActive ? "bytes_bytes" : "bytes"),
                "byte_join", ctx.byteActive ? Sql.fmt("classification", "byte_join", "b", b) : "");
        db.exec(sql, segmentId, a, b, direction, classificationReason, ctx.commonStart, ctx.commonStart, ctx.commonEnd,
                stall, stall / 1000, ctx.commonEnd, classificationReason, classificationReason, ctx.commonStart,
                ctx.commonStart, ctx.commonEnd, stall / 1000, ctx.commonEnd);
        if (ctx.hasDeviceProofs) {
            DeviceDrops.upgradeEvents(db, a, b, direction, ctx.points.get(a).device());
        }
    }

    @SuppressWarnings("unchecked")
    private static void recordClassFindingsAndBytes(EngineContext ctx, Map<String, Object> segment, String segmentId,
            String a, String b, String direction, List<Row> classes, String reason) {
        Db db = ctx.db;
        Point pa = ctx.points.get(a);
        Point pb = ctx.points.get(b);
        for (Row cls : classes) {
            String kind = cls.str("kind");
            String severity = SEVERITY.get(kind);
            Object[] event = db.one(Sql.get("classification", "first_event"), a, b, direction, kind);
            List<Map<String, Object>> refs = Evidence.evidence(db, "o.packet_key IN (?,?,?)", Arrays.asList(event));
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("id", segmentId + ":" + kind);
            finding.put("type", kind);
            if (kind.equals("confirmed_device_drop")) {
                finding.putAll(DeviceDrops.findingContext(db, a, b, direction));
            }
            finding.put("severity", severity);
            finding.put("hop", segmentId);
            finding.put("direction", direction);
            finding.put("time_range", new ArrayList<>(Arrays.asList(cls.get("first_seen"), cls.get("last_seen"))));
            finding.put("confidence", !severity.equals("unknown") ? "supported" : "unknown");
            finding.put("metrics", cls);
            finding.put("cause", kind.equals("confirmed_device_drop") ? "device_stage_evidence"
                    : !kind.equals("capture_miss") ? "unknown" : "capture_visibility");
            finding.put("summary", kind.equals("handshake_blocked")
                    ? "Handshake blocked between " + pa.label() + " and " + pb.label() + "; cause unknown."
                    : cls.get("count") + " " + kind.replace('_', ' ') + " event(s) between " + pa.label() + " and "
                            + pb.label() + ".");
            finding.put("evidence", refs);
            finding.put("evidence_note", "First event shown; select the hop to browse all events");
            ctx.findings.add(finding);
        }
        if (ctx.byteActive) {
            Object[] byteParams = {a, direction, ctx.start, ctx.start, ctx.end, ctx.end};
            long byteCount = NeighbourScope.excludes(ctx, a, b, direction)
                    ? db.count(NeighbourScope.filtered(Sql.get("classification", "byte_count")),
                            NeighbourScope.params(byteParams, a, b, direction))
                    : db.count(Sql.get("classification", "byte_count"), byteParams);
            PyNum lost = new PyInt(0);
            for (Row x : classes) {
                if (List.of("recovered_loss", "impactful_loss", "unrecovered_loss").contains(x.str("kind"))) {
                    Object mb = x.get("missing_bytes");
                    lost = PyArith.add(lost, mb == null || Long.valueOf(0).equals(mb) ? new PyInt(0) : num(mb));
                }
            }
            boolean ranged = db.count(Sql.get("classification", "ranged"), a, direction) > 0;
            segment.put("matching_mode", ranged ? "byte_ranges" : "packets");
            segment.put("eligible_bytes", byteCount);
            segment.put("lost_bytes", ClockModel.json(lost));
            segment.put("byte_loss_percent", byteCount != 0 && (reason == null || reason.isEmpty())
                    ? (Object) PyArith.trueDiv(PyArith.mul(new PyInt(100), lost), new PyInt(byteCount)).value() : null);
        }
    }

    private static void classifySegmentLosses(EngineContext ctx, Map<String, Object> segment, String segmentId, String a,
            String b, String direction, List<String> downstream, String classificationReason) {
        Db db = ctx.db;
        Point pa = ctx.points.get(a);
        Point pb = ctx.points.get(b);
        prepareMissingAndRecoveries(ctx, a, b, direction, downstream);
        insertSegmentEvents(ctx, segmentId, a, b, direction, classificationReason);
        Object[] totalParams = {a, direction, ctx.start, ctx.start, ctx.end, ctx.end};
        long total = NeighbourScope.excludes(ctx, a, b, direction)
                ? db.count(NeighbourScope.filtered(Sql.get("classification", "total")),
                        NeighbourScope.params(totalParams, a, b, direction))
                : db.count(Sql.get("classification", "total"), totalParams);
        List<Row> classes = db.rows(Sql.get("classification", "classes"), a, b, direction);
        segment.put("eligible_packets", total);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Row x : classes) {
            counts.put(x.str("kind"), x.get("count"));
        }
        segment.put("classes", counts);
        long lossCount = 0;
        for (Row x : classes) {
            if (List.of("recovered_loss", "impactful_loss", "unrecovered_loss", "handshake_blocked", "confirmed_device_drop")
                    .contains(x.str("kind"))) {
                lossCount += x.lng("count");
            }
        }
        String reason = (String) segment.get("reason");
        if (pa.vendor().equals("checkpoint") && pb.device().equals(pa.device())
                && classes.stream().anyMatch(c -> "unknown".equals(c.get("kind")))) {
            reason = classificationReason;
            segment.put("reason", reason);
        }
        segment.put("loss_percent", total != 0 && (reason == null || reason.isEmpty())
                ? (Object) PyMath.trueDivide(100 * lossCount, total) : null);
        recordClassFindingsAndBytes(ctx, segment, segmentId, a, b, direction, classes, reason);
    }

    // ------------------------------------------------------------------ flow summary and quality

    @SuppressWarnings("unchecked")
    static void buildFlowSummaryAndQuality(EngineContext ctx) {
        Db db = ctx.db;
        db.exec(Sql.get("segments", "flow_summary"));
        if (ctx.byteActive) {
            db.exec(Sql.get("segments", "flow_summary_bytes"));
        }
        List<Map<String, Object>> quality = new ArrayList<>();
        for (Row r : db.rows(Sql.get("segments", "quality"))) {
            quality.add(r);
        }
        ctx.inventories.clear();
        ctx.captureNames.clear();
        for (Object[] r : db.tuples(Sql.get("segments", "captures"))) {
            ctx.inventories.put((String) r[0], (Map<String, Object>) Evidence.loads((String) r[2]));
            ctx.captureNames.put((String) r[0], (String) r[1]);
        }
        Map<String, Row> indexCounts = new LinkedHashMap<>();
        for (Row r : db.rows(Sql.get("segments", "index_counts"))) {
            indexCounts.put(r.str("point"), r);
        }
        Set<Object> present = new LinkedHashSet<>();
        for (Map<String, Object> q : quality) {
            present.add(q.get("point"));
        }
        for (String p : indexCounts.keySet()) {
            if (!present.contains(p)) {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("point", p);
                q.put("packets", 0L);
                q.put("excluded", 0L);
                q.put("truncated", 0L);
                q.put("possible_offload", 0L);
                q.put("unknown_direction", 0L);
                quality.add(q);
            }
        }
        for (Map<String, Object> q : quality) {
            q.put("index_scope", indexCounts.get((String) q.get("point")));
            String cid = ctx.points.get((String) q.get("point")).captureId();
            Map<String, Object> inv = ctx.inventories.get(cid);
            q.put("file", ctx.captureNames.containsKey(cid) ? ctx.captureNames.get(cid) : q.get("point"));
            q.put("ifdrop", inv.get("ifdrop"));
            q.put("osdrop", inv.get("osdrop"));
            q.put("prefilter_excluded", inv.containsKey("prefilter_excluded") ? inv.get("prefilter_excluded") : 0L);
            q.put("prefilter_total", inv.get("prefilter_total"));
            q.put("prefilter_skipped", Evidence.truthy(inv.get("prefilter_skipped")));
            q.put("evidence", Evidence.evidence(db,
                    "o.point=? AND (NOT o.eligible OR o.caplen<o.wirelen OR o.length>1500 OR o.direction='unknown')",
                    List.of(q.get("point")), 5));
        }
        ctx.quality = quality;
    }
}
