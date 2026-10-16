package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The topology fields the core engine reads (reference {@code topology.models.Topology}), with the same
 * defaults and the normalizations the reference validators apply (stage ordering, F5 full proxy, Palo Alto
 * side, NAT tuple text). The topology lane owns the complete model; this is the core engine's view of it.
 */
public final class Topology {
    public record Point(
            String id,
            String label,
            String device,
            String kind,
            String side,
            String captureId,
            String clockDomain,
            boolean hostCapture,
            boolean stageCapture,
            Long iface,
            String vendor,
            String vendorStage,
            String vendorInterface,
            boolean inspectionComplete,
            String matchingLayer,
            String sourceCidr,
            String translation,
            String payloadTransform) {
        Point withSide(String s) {
            return new Point(id, label, device, kind, s, captureId, clockDomain, hostCapture, stageCapture, iface,
                    vendor, vendorStage, vendorInterface, inspectionComplete, matchingLayer, sourceCidr, translation,
                    payloadTransform);
        }

        Point withStage(boolean stage) {
            return new Point(id, label, device, kind, side, captureId, clockDomain, hostCapture, stage, iface,
                    vendor, vendorStage, vendorInterface, inspectionComplete, matchingLayer, sourceCidr, translation,
                    payloadTransform);
        }

        Point withTranslation(String t) {
            return new Point(id, label, device, kind, side, captureId, clockDomain, hostCapture, stageCapture, iface,
                    vendor, vendorStage, vendorInterface, inspectionComplete, matchingLayer, sourceCidr, t,
                    payloadTransform);
        }

        Point withSourceCidr(String cidr) {
            return new Point(id, label, device, kind, side, captureId, clockDomain, hostCapture, stageCapture, iface,
                    vendor, vendorStage, vendorInterface, inspectionComplete, matchingLayer, cidr, translation,
                    payloadTransform);
        }
    }

    public record NatMapping(String pointA, String pointB, String tupleA, String tupleB) {
    }

    public record ClockOverride(double offsetMs, double driftPpm) {
    }

    public final List<Point> points = new ArrayList<>();
    public List<String> forward = new ArrayList<>();
    public List<String> reverse = new ArrayList<>();
    public final List<String> clientCidrs = new ArrayList<>();
    public final List<NatMapping> natMappings = new ArrayList<>();
    public final Map<String, ClockOverride> clockOverrides = new LinkedHashMap<>();
    public double minEligibleRatio = 0.9;
    public double duplicateUs = 20;
    /** The reference value as it serializes: the unvalidated int default 20, or the validated float. */
    private Object duplicateUsJson = 20L;
    public double stallMs = 200;
    /** The reference value as it serializes: the unvalidated int default 200, or the validated float. */
    public Number stallMsJson = 200L;
    public double matchWindowMs = 2000;
    public Double start;
    public Double end;
    public String reportTimezone;
    /** UDP/RTP/SIP/DNS lane: {@code rtp_port_ranges}, {@code rtp_clock_rate}, {@code rtp_jitter_ms}, {@code udp_timeout_ms}, {@code dns_timeout_ms}. */
    public final List<long[]> rtpPortRanges = new ArrayList<>();
    public Long rtpClockRate;
    /** Float fields with an int default: the reference keeps the unvalidated default int (2000) and a given value as float. */
    public Number rtpJitterMs = 2L;
    public Number udpTimeoutMs = 2000L;
    public Number dnsTimeoutMs = 2000L;
    /** The topology document as given; detector lanes read their own settings from it (reference defaults). */
    public JsonNode raw;

    public static Topology read(Path path) throws IOException {
        return parse(new ObjectMapper().readTree(Files.readString(path)));
    }

    public static Topology parse(JsonNode n) {
        Topology t = new Topology();
        t.raw = n;
        for (JsonNode p : n.path("points")) {
            Point point = new Point(
                    p.path("id").asText(),
                    p.path("label").asText(),
                    p.path("device").asText(),
                    text(p, "kind", "Generic"),
                    text(p, "side", "both"),
                    p.path("capture_id").asText(),
                    text(p, "clock_domain", null),
                    p.path("host_capture").asBoolean(false),
                    p.path("stage_capture").asBoolean(false),
                    p.hasNonNull("interface") ? p.get("interface").asLong() : null,
                    text(p, "vendor", "none"),
                    text(p, "vendor_stage", null),
                    text(p, "vendor_interface", null),
                    p.path("inspection_complete").asBoolean(false),
                    text(p, "matching_layer", null),
                    text(p, "source_cidr", null),
                    text(p, "translation", "none"),
                    text(p, "payload_transform", "none"));
            if (point.vendor().equals("paloalto")
                    && ("receive".equals(point.vendorStage()) || "transmit".equals(point.vendorStage()))) {
                point = point.withSide("receive".equals(point.vendorStage()) ? "ingress" : "egress");
            }
            if (point.vendor().equals("f5")) {
                point = point.withTranslation("full_proxy");
            }
            t.points.add(point);
        }
        for (JsonNode x : n.path("forward")) {
            t.forward.add(x.asText());
        }
        for (JsonNode x : n.path("reverse")) {
            t.reverse.add(x.asText());
        }
        if (n.has("client_cidrs")) {
            for (JsonNode x : n.get("client_cidrs")) {
                t.clientCidrs.add(x.asText());
            }
        } else {
            t.clientCidrs.add("10.0.0.0/8");
        }
        for (JsonNode m : n.path("nat_mappings")) {
            t.natMappings.add(new NatMapping(m.path("point_a").asText(), m.path("point_b").asText(),
                    normalizeTuple(m.path("tuple_a").asText()), normalizeTuple(m.path("tuple_b").asText())));
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = n.path("clock_overrides").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            t.clockOverrides.put(e.getKey(), new ClockOverride(e.getValue().path("offset_ms").asDouble(),
                    e.getValue().path("drift_ppm").asDouble(0)));
        }
        t.minEligibleRatio = n.path("min_eligible_ratio").asDouble(0.9);
        t.duplicateUs = n.path("duplicate_us").asDouble(20);
        if (n.hasNonNull("duplicate_us")) {
            t.duplicateUsJson = t.duplicateUs;
        }
        t.stallMs = n.path("stall_ms").asDouble(200);
        if (n.hasNonNull("stall_ms")) {
            t.stallMsJson = t.stallMs;
        }
        t.matchWindowMs = n.path("match_window_ms").asDouble(2000);
        t.start = n.hasNonNull("start") ? n.get("start").asDouble() : null;
        t.end = n.hasNonNull("end") ? n.get("end").asDouble() : null;
        t.reportTimezone = text(n, "report_timezone", null);
        for (JsonNode r : n.path("rtp_port_ranges")) {
            t.rtpPortRanges.add(new long[] {r.get(0).asLong(), r.get(1).asLong()});
        }
        t.rtpClockRate = n.hasNonNull("rtp_clock_rate") ? n.get("rtp_clock_rate").asLong() : null;
        t.rtpJitterMs = n.hasNonNull("rtp_jitter_ms") ? (Number) n.get("rtp_jitter_ms").asDouble() : (Number) 2L;
        t.udpTimeoutMs = n.hasNonNull("udp_timeout_ms") ? (Number) n.get("udp_timeout_ms").asDouble() : (Number) 2000L;
        t.dnsTimeoutMs = n.hasNonNull("dns_timeout_ms") ? (Number) n.get("dns_timeout_ms").asDouble() : (Number) 2000L;
        t.applyStageOrder();
        return t;
    }

    private static String text(JsonNode n, String key, String fallback) {
        return n.hasNonNull(key) ? n.get(key).asText() : fallback;
    }

    /** Reference validator: tuple text is {@code json.dumps(parts, separators=(",", ":"))}. */
    static String normalizeTuple(String value) {
        try {
            JsonNode parts = new ObjectMapper().readTree(value);
            List<Object> out = new ArrayList<>();
            for (JsonNode p : parts) {
                out.add(p.isNull() ? null : p.isIntegralNumber() ? (Object) p.asLong() : (Object) p.asText());
            }
            return PyJsonWriter.compact(out);
        } catch (IOException e) {
            throw new IllegalArgumentException("NAT tuple must be a JSON list", e);
        }
    }

    private void applyStageOrder() {
        Set<String> stageDevices = new LinkedHashSet<>();
        for (Point p : points) {
            if (p.stageCapture()) {
                stageDevices.add(p.device());
            }
        }
        if (stageDevices.isEmpty()) {
            return;
        }
        for (int i = 0; i < points.size(); i++) {
            if (stageDevices.contains(points.get(i).device())) {
                points.set(i, points.get(i).withStage(true));
            }
        }
        forward = stageOrder(forward, stageDevices);
        reverse = stageOrder(reverse.isEmpty() ? reversed(forward) : reverse, stageDevices);
    }

    private List<String> stageOrder(List<String> path, Set<String> stageDevices) {
        Map<String, Point> byId = pointMap();
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < path.size()) {
            String device = byId.get(path.get(i)).device();
            List<String> block = new ArrayList<>();
            while (i < path.size() && byId.get(path.get(i)).device().equals(device)) {
                block.add(path.get(i++));
            }
            if (stageDevices.contains(device)) {
                List<String> ingress = new ArrayList<>();
                List<String> other = new ArrayList<>();
                for (String p : block) {
                    (byId.get(p).side().equals("ingress") ? ingress : other).add(p);
                }
                block = new ArrayList<>(ingress);
                block.addAll(other);
            }
            result.addAll(block);
        }
        return result;
    }

    /** {@code topology.duplicate_us} as the reference report writes it (int default, float when given). */
    public Object duplicateUsValue() {
        return duplicateUsJson;
    }

    public Map<String, Point> pointMap() {
        Map<String, Point> out = new LinkedHashMap<>();
        for (Point p : points) {
            out.put(p.id(), p);
        }
        return out;
    }

    public static List<String> reversed(List<String> path) {
        List<String> out = new ArrayList<>(path);
        java.util.Collections.reverse(out);
        return out;
    }

    /** {@code topology.path(direction)}. */
    public List<String> path(String direction) {
        return direction.equals("forward") ? forward : reverse.isEmpty() ? reversed(forward) : reverse;
    }

    public boolean precedes(String a, String b, String direction) {
        List<String> path = path(direction);
        return path.contains(a) && path.contains(b) && path.indexOf(a) < path.indexOf(b);
    }

    public Topology withForward(List<String> f) {
        forward = new ArrayList<>(f);
        if (raw instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
            obj.set("forward", new ObjectMapper().valueToTree(forward));
        }
        return this;
    }
}
