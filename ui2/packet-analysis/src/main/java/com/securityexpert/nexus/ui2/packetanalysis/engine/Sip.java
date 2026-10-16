package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reference {@code sip.py}: compare tshark SIP/SDP messages across hops and bind media by advertised endpoints. */
public final class Sip {
    static final String NOTE = "SIP message identity includes canonical signaling flow, Call-ID, CSeq, status and "
            + "direction. Only unique bounded pairs are compared. Declared ALG rewriting is expected, not a bug. Media "
            + "association requires a unique SDP source endpoint; ports or SSRC alone cannot identify a call. SDP/media "
            + "mismatch is corroboration, not proof of a policy cause.";
    static final List<String> FIELDS = List.of("sdp.connection_info.address", "sdp.media.port", "sip.Contact", "sip.Via");
    static final List<String> KEYS = List.of("address", "port", "contact", "via");

    private Sip() {
    }

    @SuppressWarnings("unchecked")
    static void stage(EngineContext ctx) {
        List<Map<String, Object>> rtpFindings = (List<Map<String, Object>>) ctx.sections.getOrDefault("rtp_findings",
                new ArrayList<>());
        Object[] built = build(ctx.db, ctx.topology, ctx.segments, Rtp.clockJson(ctx), rtpFindings);
        ctx.sections.put("sip", built[0]);
        ctx.findings.addAll((List<Map<String, Object>>) built[1]);
    }

    private static String label(Map<String, Point> points, String id) {
        return points.get(id).label();
    }

    /** {@code build_sip(db, topology, segments, clocks, rtp_findings)}: returns {report, findings}. */
    @SuppressWarnings("unchecked")
    public static Object[] build(Db db, Topology topology, List<Map<String, Object>> segments,
            Map<String, Map<String, Object>> clocks, List<Map<String, Object>> rtpFindings) {
        db.exec(Sql.get("sip", "sip_messages"));
        Map<String, Object> result = new LinkedHashMap<>();
        if (db.one(Sql.get("sip", "any_message")) == null) {
            db.exec(Sql.get("sip", "drop_call_packets"));
            result.put("comparisons", new ArrayList<>());
            result.put("total", 0L);
            result.put("one_way", new ArrayList<>());
            result.put("note", NOTE);
            result.put("scope", "Full saved analysis window");
            return new Object[] {result, new ArrayList<Map<String, Object>>()};
        }
        Map<String, Point> points = topology.pointMap();
        List<Map<String, Object>> comparisons = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> segment : segments) {
            String a = (String) segment.get("point_a");
            String b = (String) segment.get("point_b");
            List<Row> messages = db.rows(Sql.get("sip", "messages"), a, b, segment.get("direction"),
                    topology.matchWindowMs / 1000, a, segment.get("direction"), topology.start, topology.start,
                    topology.end, topology.end);
            List<List<Object>> pairs = new ArrayList<>();
            for (Row m : messages) {
                pairs.add(Arrays.asList(a, m.get("frame")));
                pairs.add(Arrays.asList(b, m.get("frame_b")));
            }
            Map<List<Object>, Map<String, Object>> refsByFrame = UdpIndex.frameEvidence(db, pairs);
            boolean reliable = !Evidence.truthy(segment.get("reason")) && !Rtp.unclocked(clocks, points.get(a))
                    && !Rtp.unclocked(clocks, points.get(b));
            for (Row message : messages) {
                boolean supported = reliable && Objects.equals(message.get("candidates"), 1L)
                        && !Evidence.truthy(message.get("ambiguous"));
                Map<String, Object> changes = new LinkedHashMap<>();
                for (int i = 0; i < FIELDS.size(); i++) {
                    String key = KEYS.get(i);
                    if (supported && !Objects.equals(message.get(key), message.get(key + "_b"))) {
                        Map<String, Object> change = new LinkedHashMap<>();
                        change.put("before", message.get(key));
                        change.put("after", message.get(key + "_b"));
                        changes.put(FIELDS.get(i), change);
                    }
                }
                boolean expected = Evidence.truthy(segment.get("device"))
                        && (points.get(a).payloadTransform().equals("alg") || points.get(b).payloadTransform().equals("alg"));
                List<Map<String, Object>> refs = new ArrayList<>();
                for (List<Object> key : List.of(Arrays.asList(a, message.get("frame")),
                        Arrays.asList(b, supported ? message.get("frame_b") : null))) {
                    Map<String, Object> ref = refsByFrame.get(key);
                    if (ref != null) {
                        refs.add(ref);
                    }
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("call_id", message.get("call_id"));
                item.put("flow", message.get("flow"));
                item.put("hop", segment.get("id"));
                item.put("device", segment.get("device"));
                item.put("direction", segment.get("direction"));
                item.put("changes", changes);
                item.put("status", !changes.isEmpty() && expected ? "expected rewriting"
                        : !changes.isEmpty() ? "undeclared rewriting" : supported ? "unchanged" : "unknown");
                item.put("reason", supported ? "Unique message and reliable segment"
                        : "Ambiguous/missing message, incomplete coverage, translation or clock evidence");
                item.put("evidence", refs);
                item.put("tooltip", NOTE);
                comparisons.add(item);
                if (changes.isEmpty()) {
                    continue;
                }
                if (!expected) {
                    Object device = segment.get("device");
                    String name = Evidence.truthy(device) ? (String) device
                            : "link between " + label(points, a) + " and " + label(points, b);
                    Map<String, Object> metrics = new LinkedHashMap<>();
                    metrics.put("call_id", message.get("call_id"));
                    metrics.put("flow", message.get("flow"));
                    metrics.put("changes", changes);
                    Map<String, Object> f = Rtp.mediaFinding("sip_rewritten", segment, message.get("time"),
                            "SIP rewritten by " + name + " (not declared)", metrics, refs);
                    f.put("tooltip", NOTE);
                    findings.add(f);
                }
                if (changes.keySet().stream().noneMatch(field -> field.startsWith("sdp."))) {
                    continue;
                }
                String peer = "forward".equals(message.get("direction")) ? last(topology.forward)
                        : !topology.reverse.isEmpty() ? last(topology.reverse) : null;
                Object[] pending = {message, segment, peer};
                item.put("_mismatch", pending);
            }
        }
        db.exec(Sql.get("sip", "sip_endpoints"), topology.forward.get(0),
                topology.reverse.isEmpty() ? null : topology.reverse.get(0));
        db.exec(Sql.get("sip", "rtp_call_packets"));
        for (Map<String, Object> item : comparisons) {
            Object[] pending = (Object[]) item.remove("_mismatch");
            if (pending == null) {
                continue;
            }
            Row message = (Row) pending[0];
            Map<String, Object> segment = (Map<String, Object>) pending[1];
            String peer = (String) pending[2];
            List<Row> media = db.rows(Sql.get("sip", "media"), message.get("call_id"), message.get("flow"),
                    message.get("direction"), peer);
            if (media.size() != 1) {
                item.put("media_status", "unknown");
                item.put("media_reason", "No unique actual media source at receiving endpoint");
                continue;
            }
            Row m = media.get(0);
            boolean mismatch = !(Objects.equals(message.get("address_b"), m.get("src"))
                    && Objects.equals(message.get("port_b"), m.get("sport")));
            item.put("media_status", mismatch ? "mismatch" : "matches actual RTP source");
            if (mismatch) {
                Object device = segment.get("device");
                String name = Evidence.truthy(device) ? (String) device
                        : "link between " + label(points, (String) segment.get("point_a")) + " and "
                                + label(points, (String) segment.get("point_b"));
                List<Map<String, Object>> refs = new ArrayList<>((List<Map<String, Object>>) item.get("evidence"));
                refs.addAll(UdpIndex.protocolEvidence(db, "o.point=? AND o.frame=?", Arrays.asList(peer, m.get("frame")), 1));
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("call_id", message.get("call_id"));
                metrics.put("flow", message.get("flow"));
                metrics.put("advertised_address", message.get("address_b"));
                metrics.put("advertised_port", message.get("port_b"));
                metrics.put("actual_address", m.get("src"));
                metrics.put("actual_port", m.get("sport"));
                Map<String, Object> f = Rtp.mediaFinding("sdp_media_mismatch", segment, message.get("time"),
                        "SDP / media mismatch at " + name, metrics, refs);
                f.put("tooltip", NOTE);
                findings.add(f);
            }
        }
        List<Map<String, Object>> oneWay = new ArrayList<>();
        for (Row call : db.rows(Sql.get("sip", "calls"))) {
            List<Row> observed = db.rows(Sql.get("sip", "observed"), call.get("call_id"), call.get("signaling_flow"));
            for (Object[] entry : new Object[][] {{"forward", topology.forward}, {"reverse", topology.reverse}}) {
                String direction = (String) entry[0];
                List<String> path = (List<String>) entry[1];
                if (path.isEmpty()) {
                    continue;
                }
                String first = path.get(0);
                String lastPoint = last(path);
                if (observed.stream().noneMatch(r -> first.equals(r.get("point")) && direction.equals(r.get("direction")))) {
                    continue;
                }
                if (observed.stream().anyMatch(r -> lastPoint.equals(r.get("point")) && direction.equals(r.get("direction")))) {
                    continue;
                }
                String opposite = direction.equals("forward") ? "reverse" : "forward";
                if (observed.stream().noneMatch(r -> lastPoint.equals(r.get("point")) && opposite.equals(r.get("direction")))) {
                    continue;
                }
                Set<Object> sourceFlows = new LinkedHashSet<>();
                for (Row r : observed) {
                    if (direction.equals(r.get("direction"))) {
                        sourceFlows.add(r.get("flow"));
                    }
                }
                List<Map<String, Object>> losses = new ArrayList<>();
                for (Map<String, Object> f : rtpFindings) {
                    Map<String, Object> fm = (Map<String, Object>) f.get("metrics");
                    if ("rtp_loss".equals(f.get("type")) && direction.equals(f.get("direction"))
                            && sourceFlows.contains(fm.get("flow"))) {
                        losses.add(f);
                    }
                }
                if (losses.isEmpty()) {
                    continue;
                }
                Map<String, Object> loss = losses.get(0);
                Map<String, Object> segment = segments.stream().filter(s -> Objects.equals(s.get("id"), loss.get("hop")))
                        .findFirst().orElseThrow();
                if (Evidence.truthy(segment.get("reason"))
                        || Rtp.unclocked(clocks, points.get((String) segment.get("point_a")))
                        || Rtp.unclocked(clocks, points.get((String) segment.get("point_b")))) {
                    continue;
                }
                boolean mismatch = findings.stream().anyMatch(f -> "sdp_media_mismatch".equals(f.get("type"))
                        && Objects.equals(((Map<String, Object>) f.get("metrics")).get("call_id"), call.get("call_id"))
                        && Objects.equals(((Map<String, Object>) f.get("metrics")).get("flow"), call.get("signaling_flow")));
                String reason = mismatch ? "Wrong SDP advertisement corroborated by actual media tuple"
                        : "Return media stops at a supported disappearance hop; device policy cause unknown";
                List<Map<String, Object>> refs = new ArrayList<>((List<Map<String, Object>>) loss.get("evidence"));
                refs.addAll(UdpIndex.protocolEvidence(db,
                        "o.point=? AND o.frame IN (SELECT frame FROM sip_endpoints WHERE call_id=? AND signaling_flow=?)",
                        Arrays.asList(first, call.get("call_id"), call.get("signaling_flow")), 2));
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("call_id", call.get("call_id"));
                metrics.put("flow", ((Map<String, Object>) loss.get("metrics")).get("flow"));
                metrics.put("reason", reason);
                Map<String, Object> f = Rtp.mediaFinding("one_way_audio", segment,
                        ((List<Object>) loss.get("time_range")).get(0), "One-way audio: " + direction
                                + " media stops between " + label(points, (String) segment.get("point_a")) + " and "
                                + label(points, (String) segment.get("point_b")), metrics, refs);
                f.put("tooltip", NOTE);
                findings.add(f);
                Map<String, Object> ow = new LinkedHashMap<>();
                ow.put("call_id", call.get("call_id"));
                ow.put("hop", loss.get("hop"));
                ow.put("reason", reason);
                ow.put("evidence", refs);
                ow.put("tooltip", NOTE);
                oneWay.add(ow);
            }
        }
        result.put("comparisons", new ArrayList<>(comparisons.subList(0, Math.min(200, comparisons.size()))));
        result.put("total", (long) comparisons.size());
        result.put("one_way", oneWay);
        result.put("note", NOTE);
        result.put("scope", "Selected-window comparisons; earlier SDP provides context; up to 200 comparisons; "
                + "single SDP media section only");
        return new Object[] {result, findings};
    }

    private static String last(List<String> path) {
        return path.get(path.size() - 1);
    }
}
