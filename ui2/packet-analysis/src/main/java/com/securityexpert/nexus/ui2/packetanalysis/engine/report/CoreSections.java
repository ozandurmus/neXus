package com.securityexpert.nexus.ui2.packetanalysis.engine.report;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;

/**
 * Typed model of the core report sections (reference {@code analysis/report.py} output; the FindingsExport v11
 * top level types only {@code findings}, the rest of the reference report is free-form). Component order is the
 * reference key order; names are the reference keys (snake case). {@code Long} is a reference int,
 * {@code Double} a reference float, {@code Number} a value the reference emits as either (int 0 vs float).
 * {@link Maybe} components are keys the reference adds only on some paths. Detector sections whose shape depends
 * on the path stay untyped maps/lists.
 */
public final class CoreSections {
    private CoreSections() {
    }

    /** A key present only on some reference paths. */
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
    @Retention(RetentionPolicy.RUNTIME)
    @JacksonAnnotationsInside
    @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = Maybe.AbsentFilter.class)
    public @interface Optional {
    }

    public record SequenceTranslation(Long observedSeq, Long observedAck, Long canonicalSeq, Long canonicalAck, Long seqOffset,
            Long ackOffset, String reason) {
    }

    /** One evidence reference (reference {@code evidence.evidence}). */
    public record EvidenceRef(String point, String file, String captureId, Long frame, String displayFilter,
            Number observedTime, Double correctedTime, @Optional Maybe<SequenceTranslation> sequenceTranslation,
            @Optional Maybe<Object> vendor, String contentFilter, String flowFilter, String filterNote, Object tunnel,
            Object fragment, @Optional Maybe<List<Object>> byteRanges, @Optional Maybe<String> rangeNote,
            @Optional Maybe<Map<String, Object>> protocolIdentity) {
    }

    public record Window(Double start, Double end, Double commonStart, Double commonEnd) {
    }

    public record Clock(Number offset, Number drift, Number epoch, Number uncertainty, String confidence, String reason,
            Long samples, List<EvidenceRef> evidence, String domain, String sourceDomain, Number timestampJitter,
            Number driftPpm) {
    }

    public record Coverage(String point, Double start, Double end, Long packets) {
    }

    public record IndexScope(String point, Long inputFrames, Long inputFlows, Long retainedFrames, Long retainedFlows,
            Long excludedFrames, Long excludedFlows) {
    }

    public record Quality(String point, Long packets, Long excluded, Long truncated, Long possibleOffload,
            Long unknownDirection, IndexScope indexScope, String file, Object ifdrop, Object osdrop, Object prefilterExcluded,
            Object prefilterTotal, Boolean prefilterSkipped, List<EvidenceRef> evidence) {
    }

    public record AnalysisScope(String point, Long inputFrames, Long retainedFrames, Long excludedSinglePointFrames,
            Long excludedSinglePointFlows) {
    }

    public record TunnelPoint(String point, Long selected, Long unselected, String layer, Long truncatedInner) {
    }

    public record OffloadPoint(String point, Long largeFrames, String note) {
    }

    public record OrderScore(String point, Double medianTtl, Double medianTime, Long samples) {
    }

    public record AutoOrder(List<String> points, List<OrderScore> scores, String reason) {
    }

    public record NatSuggestion(String tupleA, String tupleB, Long samples, Double minDelta, Double maxDelta, Long frameA,
            Long frameB, String pointA, String pointB, String status, List<EvidenceRef> evidence) {
    }

    public record SequenceModel(String ingress, String egress, String point, String oldFlow, Long ingressStream, Long stream,
            Long forwardOffset, Long reverseOffset, Long boundaryForwardOffset, Long boundaryReverseOffset, Long samples,
            String status, String reason, Long frameA, Long frameB, String flow, List<EvidenceRef> evidence) {
    }

    public record EndpointQuality(Long total, Long eligible, Map<String, Object> excluded, Double eligibleRatio,
            @Optional Maybe<String> timestampQualityScope) {
    }

    public record TimeLabels(String local, String utc) {
    }

    public record HeadlineMetrics(Double start, Double end, Long lost, Long eligibleDataPackets, Double lossPercent,
            Long quicklyRecovered, Long measuredStalls, Double maxStallMs, Long otherImpact, String upstreamStatus) {
    }

    /** One hop and direction (reference {@code segments._build_single_segment} + classification + headlines). */
    public record Segment(Double eligibleRatio, Map<String, Object> excludedCounts, Map<String, EndpointQuality> endpointQuality,
            String id, String pointA, String pointB, String label, String location, String device, String direction,
            String reason, String latencyConfidence, Number offsetUncertaintyMs, Long matched, Double minMs, Double p50Ms,
            Double p05Ms, Long negativeOutliers, Double p95Ms, Double maxMs, Boolean systematicNegative,
            Double latencyUncertaintyMs, List<String> qualityNotes, Long eligiblePackets, Map<String, Long> classes,
            Double lossPercent, @Optional Maybe<String> matchingMode, @Optional Maybe<Long> eligibleBytes,
            @Optional Maybe<Long> lostBytes, @Optional Maybe<Double> byteLossPercent, List<String> findingIds,
            @Optional Maybe<String> severity, @Optional Maybe<HeadlineMetrics> headlineMetrics,
            @Optional Maybe<TimeLabels> timeLabels, @Optional Maybe<String> clockCaveat, @Optional Maybe<String> headline) {
    }

    public record FindingMetrics(String kind, Long count, Long missingBytes, Double firstSeen, Double lastSeen,
            Double maxRecoveryMs, Double maxStallMs, Long dataCount, Long deliveredRetries,
            @Optional Maybe<Long> downstreamPacketCount) {
    }

    /**
     * Any finding of the report's {@code findings} list; each detector's finding shape is one permitted record.
     * {@link Report#findings} is bound untyped (detectors add keys whose order depends on the path), so these records
     * document the core and media finding shapes and are not used for binding.
     */
    public sealed interface AnyFinding permits Finding, ProtocolSections.MediaFinding {
    }

    /** A core finding (types recovered_loss .. unknown; confirmed_device_drop is the device-drop lane). */
    public record Finding(String id, String type, String severity, String hop, String direction, List<Double> timeRange,
            String confidence, FindingMetrics metrics, String cause, String summary, List<EvidenceRef> evidence,
            String evidenceNote, String headline, TimeLabels timeLabels, @Optional Maybe<String> downstreamSummary,
            @Optional Maybe<Long> downstreamCount) implements AnyFinding {
    }

    /** The whole report, in the reference key order (detector sections untyped). */
    public record Report(Long schemaVersion, Map<String, Object> prefilterExcluded, List<AnalysisScope> analysisScope,
            List<TunnelPoint> tunnelPoints, List<Object> fragmentPoints, Map<String, Object> microbursts,
            Map<String, Object> sessions, Map<String, Object> sessionEvents, Map<String, Object> security,
            ProtocolSections.Udp udp, ProtocolSections.Rtp rtp, ProtocolSections.Sip sip, Map<String, Object> hostPath,
            Map<String, Object> cycles, Map<String, Object> incident, Map<String, Object> delivery,
            List<Object> applicationStatus, Map<String, Object> proxies, List<Object> tls, List<Object> integrityNotes,
            ProtocolSections.F5 f5, List<ProtocolSections.DeviceEvent> vendorDeviceEvents, Long vendorDeviceEventCount,
            List<Object> confirmedDeviceDrops, Long confirmedDeviceDropCount, List<SequenceModel> sequenceTranslations,
            List<OffloadPoint> offloadPoints, Map<String, Object> timeseries, Map<String, Object> onsets, String engineVersion,
            String generatedAt, String verdict, String scope, Window window, Map<String, Clock> clocks, List<Coverage> coverage,
            List<Quality> quality, AutoOrder autoOrderSuggestion, List<NatSuggestion> natSuggestions,
            List<Map<String, Object>> segments, List<Map<String, Object>> findings, Long findingCount, Long flowCount,
            List<String> limitations, Map<String, String> unknownSections, List<String> engineNotes) {
    }
}
