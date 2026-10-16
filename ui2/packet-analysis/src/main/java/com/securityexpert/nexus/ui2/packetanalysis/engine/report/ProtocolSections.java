package com.securityexpert.nexus.ui2.packetanalysis.engine.report;

import com.securityexpert.nexus.ui2.packetanalysis.engine.report.CoreSections.EvidenceRef;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.CoreSections.Optional;
import java.util.List;
import java.util.Map;

/**
 * Typed model of the UDP / RTP / SIP / vendor report sections (reference {@code udp.build_udp}, {@code rtp.build_rtp},
 * {@code sip.build_sip}, {@code vendors.f5_report}, {@code device_drops.device_report} vendor events) and of the
 * media findings. Component order is the reference key order; {@code Long} is a reference int, {@code Double} a
 * reference float, {@code Object} a value the reference emits with more than one JSON type.
 */
public final class ProtocolSections {
    private ProtocolSections() {
    }

    public record UdpSummary(String point, String protocol, String endpoint, Long requests, Long answered, Long unanswered,
            Long unknown, Long noResponseExpected, Double p50Ms, Double p95Ms, Double maxMs) {
    }

    public record UdpItem(String point, Long frame, String flow, String protocol, String role, String token, String status,
            Double latencyMs, String hop, String packetKey, Long responseFrame, String lostResponseKey, Long candidates,
            Boolean ambiguous, Boolean complete, Boolean covered, String decodeReason, Boolean incompleteResponse,
            Boolean captureDrops, String reason, String tooltip, List<EvidenceRef> evidence) {
    }

    public record Udp(List<UdpSummary> summary, List<UdpItem> items, Long total, Number timeoutMs, String note, String scope) {
    }

    public record RtpStream(String point, String flow, String direction, Long ssrc, Double start, Double finish, Long packets,
            Long duplicates, Long reordered, Long gaps, Long discontinuities, Long clockVariants, Long rate,
            Double jitterP95Ms, Double jitterMaxMs, Long firstFrame, Long lastFrame, String tooltip, String reason,
            List<EvidenceRef> evidence) {
    }

    public record RtpOneWay(String point, String flow, Long directions, String seenDirection, Long packets, String status,
            String reason, String stoppingHop, List<EvidenceRef> evidence) {
    }

    public record Rtp(List<RtpStream> streams, List<RtpOneWay> oneWay, Long total, String note, String scope) {
    }

    /** One SIP field change: SDP address/Contact/Via text, or the SDP media port (int). */
    public record SipChange(Object before, Object after) {
    }

    public record SipComparison(String callId, String flow, String hop, String device, String direction,
            Map<String, SipChange> changes, String status, String reason, List<EvidenceRef> evidence, String tooltip,
            @Optional Maybe<String> mediaStatus, @Optional Maybe<String> mediaReason) {
    }

    public record SipOneWay(String callId, String hop, String reason, List<EvidenceRef> evidence, String tooltip) {
    }

    public record Sip(List<SipComparison> comparisons, Long total, List<SipOneWay> oneWay, String note, String scope) {
    }

    public record F5Pair(String captureId, Long stream, String flowid, String peerid, String tmm, String role, String reason,
            @Optional Maybe<String> clientFlow, @Optional Maybe<String> serverFlow) {
    }

    public record F5Request(String device, String request, String clientFlow, String serverFlow, String flowid,
            String peerid, Double requestDwellMs, Long clockUncertaintyMs, String reason, List<EvidenceRef> evidence,
            String note) {
    }

    public record F5Reset(String device, String flow, Double time, String reason, List<EvidenceRef> evidence) {
    }

    public record F5(List<F5Pair> pairs, List<F5Request> requests, List<F5Reset> resets,
            @Optional Maybe<Long> connectionCount, @Optional Maybe<String> note) {
    }

    /** One {@code device_proofs} row of {@code vendor_device_events}. */
    public record DeviceEvent(String proofId, String point, String device, String stage, String adapter, String captureId,
            Long frame, String flow, String packetKey, String pathKey, Double time, String timeSource, String status,
            String reason, String eventId, List<EvidenceRef> evidence) {
    }

    /** {@code rtp.media_finding} (rtp_loss, rtp_jitter, sip_rewritten, sdp_media_mismatch, one_way_audio). */
    public record MediaFinding(String id, String type, String hop, String device, String direction, List<Double> timeRange,
            String severity, String confidence, String summary, String headline, Map<String, Object> metrics,
            List<EvidenceRef> evidence, String tooltip, String cause) implements CoreSections.AnyFinding {
    }
}
