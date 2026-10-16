package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Reference {@code rtp.py}: RTP header accounting and RFC 3550 jitter on scoped streams, and media findings. */
public final class Rtp {
    static final String NOTE = "Sequence gaps are observations, not automatically network loss. Streams are scoped by "
            + "canonical directional tuple and SSRC, never SSRC/port alone. RFC 3550 jitter uses arrival order and a "
            + "declared/SDP/static payload clock; absent or changing clocks stay unknown. Hop attribution requires "
            + "independently supported matching, clocks and coverage.";

    private Rtp() {
    }

    /** {@code clock_json = {cid: m.json() for cid, m in models.items()}}. */
    static Map<String, Map<String, Object>> clockJson(EngineContext ctx) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, ClockModel> e : ctx.models.entrySet()) {
            out.put(e.getKey(), e.getValue().json());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static void stage(EngineContext ctx) {
        Object[] built = build(ctx.db, ctx.topology, ctx.segments, clockJson(ctx));
        ctx.sections.put("rtp", built[0]);
        List<Map<String, Object>> findings = (List<Map<String, Object>>) built[1];
        ctx.sections.put("rtp_findings", findings);
        ctx.findings.addAll(findings);
    }

    /** {@code media_finding(db, kind, segment, ts, summary, metrics, refs, supported=True)}. */
    static Map<String, Object> mediaFinding(String kind, Map<String, Object> segment, Object ts, String summary,
            Map<String, Object> metrics, List<Map<String, Object>> refs) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", "media:" + kind + ":" + segment.get("id") + ":" + Evidence.pyStr(metrics.getOrDefault("flow", ""))
                + ":" + Evidence.pyStr(metrics.getOrDefault("call_id", "")) + ":" + Evidence.pyStr(ts));
        f.put("type", kind);
        f.put("hop", segment.get("id"));
        f.put("device", segment.get("device"));
        f.put("direction", segment.get("direction"));
        f.put("time_range", new ArrayList<>(Arrays.asList(ts, ts)));
        f.put("severity", "low");
        f.put("confidence", "supported");
        f.put("summary", summary);
        f.put("headline", summary);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", 1L);
        m.putAll(metrics);
        f.put("metrics", m);
        f.put("evidence", refs);
        f.put("tooltip", NOTE);
        f.put("cause", "unknown");
        return f;
    }

    static boolean unclocked(Map<String, Map<String, Object>> clocks, Point p) {
        Map<String, Object> c = clocks.get(p.captureId());
        return c == null || c.get("uncertainty") == null;
    }

    /** {@code build_rtp(db, topology, segments, clocks)}: returns {report, findings}. */
    public static Object[] build(Db db, Topology topology, List<Map<String, Object>> segments,
            Map<String, Map<String, Object>> clocks) {
        db.exec(Sql.get("rtp", "rtp_sdp_rates"));
        db.exec(Sql.get("rtp", "rtp_rates"));
        db.exec(Sql.get("rtp", "rtp_input"), topology.rtpClockRate, topology.start, topology.start, topology.end,
                topology.end);
        Map<String, Object> result = new LinkedHashMap<>();
        if (db.one(Sql.get("rtp", "any_input")) == null) {
            db.exec(Sql.get("rtp", "drop_packets"));
            db.exec(Sql.get("rtp", "drop_streams"));
            result.put("streams", new ArrayList<>());
            result.put("one_way", new ArrayList<>());
            result.put("total", 0L);
            result.put("note", NOTE);
            result.put("scope", "Full saved analysis window");
            return new Object[] {result, new ArrayList<Map<String, Object>>()};
        }
        for (String name : List.of("rtp_order", "rtp_spans", "rtp_local", "rtp_blocks", "rtp_block_state", "rtp_packets",
                "rtp_streams")) {
            db.exec(Sql.get("rtp", name));
        }
        List<Row> streams = db.rows(Sql.get("rtp", "streams"));
        List<List<Object>> pairs = new ArrayList<>();
        for (Row s : streams) {
            pairs.add(Arrays.asList(s.get("point"), s.get("first_frame")));
            pairs.add(Arrays.asList(s.get("point"), s.get("last_frame")));
        }
        Map<List<Object>, Map<String, Object>> refsByFrame = UdpIndex.frameEvidence(db, pairs);
        for (Row stream : streams) {
            stream.put("tooltip", NOTE);
            boolean discontinuities = Evidence.truthy(stream.get("discontinuities"));
            String reason = !discontinuities ? "Observed header sequence range; late packets and duplicates are counted separately"
                    : "Sequence discontinuity/restart is unknown; no loss estimate";
            stream.put("reason", reason);
            if (discontinuities) {
                stream.put("gaps", null);
            }
            if (stream.get("rate") == null) {
                stream.put("reason", reason + "; clock rate unknown (no matching SDP, static payload rate or override)");
            }
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (Object f : new LinkedHashSet<>(Arrays.asList(stream.get("first_frame"), stream.get("last_frame")))) {
                Map<String, Object> ref = refsByFrame.get(Arrays.asList(stream.get("point"), f));
                if (ref != null) {
                    evidence.add(ref);
                }
            }
            stream.put("evidence", evidence);
        }
        Map<String, Point> points = topology.pointMap();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> segment : segments) {
            String a = (String) segment.get("point_a");
            String b = (String) segment.get("point_b");
            String d = (String) segment.get("direction");
            if (Evidence.truthy(segment.get("reason")) || unclocked(clocks, points.get(a)) || unclocked(clocks, points.get(b))) {
                continue;
            }
            for (Row loss : db.rows(Sql.get("rtp", "losses"), a, b, d)) {
                List<Map<String, Object>> refs = UdpIndex.protocolEvidence(db, "o.packet_key=?",
                        Arrays.asList(loss.get("packet_key")), 16);
                findings.add(mediaFinding("rtp_loss", segment, loss.get("time"),
                        "RTP datagram loss between " + points.get(a).label() + " and " + points.get(b).label() + "; "
                                + loss.get("missing") + " supported missing packets for this directional stream",
                        loss, refs));
            }
            db.exec(Sql.get("rtp", "rtp_transit"), a, b, d);
            Object uncertainty = segment.get("offset_uncertainty_ms");
            // topology.rtp_jitter_ms + 2 * (segment.get("offset_uncertainty_ms") or 0), int/float as the reference.
            PyNum sum = PyArith.add(Segments.num(topology.rtpJitterMs),
                    PyArith.mul(new PyInt(2), Evidence.truthy(uncertainty) ? Segments.num(uncertainty) : new PyInt(0)));
            Object threshold = sum.isInt() ? (Object) ((PyInt) sum).value() : (Object) sum.toDouble();
            for (Row signal : db.rows(Sql.get("rtp", "signals"), threshold, threshold, threshold)) {
                List<Map<String, Object>> refs = UdpIndex.protocolEvidence(db, "(o.point=? AND o.frame=?) OR(o.point=? AND o.frame=?)",
                        Arrays.asList(a, signal.get("frame_a"), b, signal.get("frame_b")), 2);
                Object device = segment.get("device");
                String where = Evidence.truthy(device) ? (String) device
                        : "link between " + points.get(a).label() + " and " + points.get(b).label();
                Map<String, Object> metrics = new LinkedHashMap<>(signal);
                metrics.put("threshold_ms", threshold);
                findings.add(mediaFinding("rtp_jitter", segment, signal.get("time"), "RTP transit variation first added at "
                        + where + "; downstream jitter is an observation, not server blame", metrics, refs));
            }
        }
        List<Row> oneWay = db.rows(Sql.get("rtp", "one_way"));
        for (Row item : oneWay) {
            item.put("status", "unknown");
            item.put("reason", "Only one media direction at this point; opposite-leg or SDP expectation not established");
            String opposite = "forward".equals(item.get("seen_direction")) ? "reverse" : "forward";
            List<Map<String, Object>> losses = new ArrayList<>();
            for (Map<String, Object> f : findings) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) f.get("metrics");
                if (f.get("type").equals("rtp_loss") && opposite.equals(f.get("direction"))
                        && java.util.Objects.equals(m.get("flow"), item.get("flow"))) {
                    losses.add(f);
                }
            }
            item.put("stopping_hop", losses.isEmpty() ? null : losses.get(0).get("hop"));
            List<Map<String, Object>> evidence = new ArrayList<>(UdpIndex.protocolEvidence(db, "o.point=? AND o.flow=?",
                    Arrays.asList(item.get("point"), item.get("flow")), 2));
            item.put("evidence", evidence);
            if (!losses.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> more = (List<Map<String, Object>>) losses.get(0).get("evidence");
                evidence.addAll(more);
            }
        }
        result.put("streams", streams);
        result.put("one_way", oneWay);
        result.put("total", db.count(Sql.get("rtp", "total")));
        result.put("note", NOTE);
        result.put("scope", "Selected window; jitter starts at the first sample; up to 200 stream examples");
        return new Object[] {result, findings};
    }
}
