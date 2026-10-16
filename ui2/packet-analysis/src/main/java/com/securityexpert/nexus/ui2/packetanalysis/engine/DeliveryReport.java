package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static com.securityexpert.nexus.ui2.packetanalysis.engine.PyOps.dict;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reference {@code delivery.delivery_report} and {@code hold_release.hold_release}: physical-copy evidence
 * separates transit from delivery by a later TCP retry; clock-independent compression of copy spacing.
 */
final class DeliveryReport {
    private DeliveryReport() {
    }

    static void deliveryReport(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Topology.Point> points = topology.pointMap();
        db.exec(Sql.get("delivery", "delivery_positions"));
        List<Object[]> positions = new ArrayList<>();
        for (String d : List.of("forward", "reverse")) {
            List<String> path = topology.path(d);
            for (int i = 0; i < path.size(); i++) {
                positions.add(new Object[] {path.get(i), d, (long) i});
            }
        }
        db.execMany(Sql.get("delivery", "delivery_positions_insert"), positions);
        db.exec(Sql.get("delivery", "delivery_events"));
        db.exec(Sql.get("delivery", "delivery_series"), te.bucketSeconds, te.bucketSeconds, ctx.start, ctx.start, ctx.end, ctx.end);
        db.exec(Sql.get("delivery", "adjacent_match_rates"));
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Row row : db.rows(Sql.get("delivery", "substitutions"))) {
            Map<String, Object> segment = null;
            for (Map<String, Object> s : ctx.segments) {
                if (Objects.equals(s.get("point_a"), row.get("point_a")) && Objects.equals(s.get("point_b"), row.get("point_b"))
                        && Objects.equals(s.get("direction"), row.get("direction"))) {
                    segment = s;
                    break;
                }
            }
            if (segment == null) {
                throw new IllegalStateException("StopIteration: delivery segment");
            }
            Topology.Point a = points.get(row.str("point_a"));
            Topology.Point b = points.get(row.str("point_b"));
            String kind = a.hostCapture() ? "host_transmit_path_loss" : b.hostCapture() ? "host_receive_path_loss" : "impactful_loss";
            List<Map<String, Object>> refs = Evidence.evidence(db, "(o.point=? AND o.frame=?) OR(o.point=? AND o.frame=?)",
                    Arrays.asList(a.id(), row.get("frame_a"), b.id(), row.get("frame_b")), 2, false);
            String text = PyOps.str(row.get("count")) + " " + kind.replace('_', ' ') + " between " + a.label() + " and " + b.label()
                    + "; original IP ID absent, a different-ID retry delivered (max " + PyOps.fmt(row.get("max_recovery_ms"), ".1f")
                    + " ms).";
            Map<String, Object> f = HostPath.mediaFinding(kind, segment, row.get("start"), text, row, refs, true);
            f.put("severity", "high");
            f.put("time_range", PyOps.list(row.get("start"), row.get("stop")));
            f.put("cause", "retry_delivery");
            f.put("tooltip", "Physical IPv4 ID preservation is learned per flow and boundary. A seq-only retry match is excluded from transit latency. Constant IDs/IPv6 cannot provide this identity proof; ambiguous copies are unknown.");
            findings.add(f);
        }
        te.delivery = dict("series", db.rows(Sql.get("delivery", "series_rows")), "note",
                "Delay means measured transit above max(1 ms, twice clock uncertainty), not a proven cause. Counts exclude substituted/ambiguous copies. Missing/delayed rates are per observed physical pair; erased or constant identity is not proof of original delivery.");
        ctx.findings.addAll(findings);
    }

    @SuppressWarnings("unchecked")
    static void holdRelease(EngineContext ctx) {
        Db db = ctx.db;
        db.exec(Sql.get("hold_release", "copy_trains"));
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> s : ctx.segments) {
            for (Row r : db.rows(Sql.get("hold_release", "trains"), s.get("point_a"), s.get("point_b"), s.get("direction"))) {
                List<Object> wireTimes = (List<Object>) r.get("wire_times");
                List<Object> hostTimes = (List<Object>) r.get("host_times");
                List<Object> wire = spacing(wireTimes);
                List<Object> received = spacing(hostTimes);
                boolean ok = PyOps.le(100L, wire.get(0)) && PyOps.le(wire.get(0), 400L);
                for (int i = 0; ok && i + 1 < wire.size(); i++) {
                    double ratio = PyOps.div(wire.get(i + 1), wire.get(i));
                    ok = 1.4 <= ratio && ratio <= 2.6;
                }
                if (!ok) {
                    continue;
                }
                List<Map<String, Object>> refs = Evidence.evidence(db,
                        "(o.point=? AND o.frame IN (SELECT unnest(?::BIGINT[]))) OR (o.point=? AND o.frame IN (SELECT unnest(?::BIGINT[])))",
                        Arrays.asList(s.get("point_a"), r.get("wire_frames"), s.get("point_b"), r.get("host_frames")), 16, false);
                List<String> w = new ArrayList<>();
                for (Object v : wire) {
                    w.add(PyOps.fmt(v, ".1f"));
                }
                List<String> h = new ArrayList<>();
                for (Object v : received) {
                    h.add(PyOps.fmt(v, ".1f"));
                }
                String text = "Hold-and-release signature between " + ((String) s.get("label")).replace(" → ", " and ") + ": copy spacing "
                        + String.join(", ", w) + " ms becomes " + String.join(", ", h) + " ms.";
                Map<String, Object> f = HostPath.mediaFinding("hold_release", s, r.get("first_time"), text,
                        dict("flow", r.get("flow"), "seq", r.get("seq"), "wire_spacing_ms", wire, "receive_spacing_ms", received), refs, true);
                f.put("tooltip", "Spacing uses each capture’s own clock. RTO-like copies arrive compressed at the next point. This is a hold/release signature, not proof of a driver/device cause; capture batching can mimic it.");
                findings.add(f);
            }
        }
        ctx.findings.addAll(findings);
    }

    private static List<Object> spacing(List<Object> times) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i + 1 < times.size(); i++) {
            out.add(PyOps.mul(PyOps.sub(times.get(i + 1), times.get(i)), 1000L));
        }
        return out;
    }
}
