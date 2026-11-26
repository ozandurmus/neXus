package com.securityexpert.nexus.ui2.persistence.logdetect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The storage port of the log detection engine, phase P1 (V272; LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 8.3).
 * Every method moves counters, device-local times, closed-vocabulary labels and pointers (chunk id, offset, length). No
 * method accepts or returns a log line, masked or not.
 */
public interface LogDetectionRepository {
    /**
     * The P1 switch, the end of the shadow period and the P2 switch (V273, layer B); both switches are off by default.
     * P2 runs only while both are on.
     */
    record Settings(boolean enabled, Instant shadowUntil, Instant updatedAt, String updatedBy, boolean p2Enabled) {
        /** P1 only (P2 off). */
        public Settings(boolean enabled, Instant shadowUntil, Instant updatedAt, String updatedBy) {
            this(enabled, shadowUntil, updatedAt, updatedBy, false);
        }

        public boolean shadow(Instant now) {
            return shadowUntil != null && shadowUntil.isAfter(now);
        }

        /** Whether the P2 job may run: the P2 switch and the P1 switch are both on. */
        public boolean p2Runs() {
            return enabled && p2Enabled;
        }
    }

    /** One pointer into a stored chunk; resolved to text only through the masked read path. */
    record Pointer(String chunkId, long offset, int length) {
    }

    /** One stored chunk the indexer has not processed under the current masking rule set and marker list. */
    record PendingChunk(String chunkId, String deviceId, String sourceKey, String jobId, LocalDate logDay) {
    }

    /** What the indexer did with one chunk. */
    record IndexState(String chunkId, String maskingRuleSet, String markerListVersion, String deviceId, String sourceKey,
            String jobId, LocalDate logDay, String status, long lines, long matched, long unparsed) {
    }

    /** One evidence row. {@code eventId} is a deterministic id, so a re-index of a chunk writes the same rows. */
    record EventRow(String eventId, String chunkId, String deviceId, String sourceKey, String markerId, String keyToken,
            LocalDateTime bucketStart, double severityWeight, int lineCount, LocalDateTime firstLineTime,
            LocalDateTime lastLineTime, List<Pointer> pointers) {
        public EventRow {
            pointers = List.copyOf(pointers);
        }
    }

    /** The evidence of one (device, source, marker, key) over one device-local day. */
    record DayEvidence(String sourceKey, String markerId, String keyToken, double severityWeight, long lineCount,
            int peakBucketCount, LocalDateTime firstLineTime, LocalDateTime lastLineTime, List<Pointer> pointers) {
        public DayEvidence {
            pointers = List.copyOf(pointers);
        }
    }

    /** A device's own history for one marker: usable days before the day, days with the marker, and the per-day counts. */
    record Baseline(int usableDays, int presenceDays, List<Long> dayCounts) {
        public Baseline {
            dayCounts = List.copyOf(dayCounts);
        }
    }

    /** The collection facts of one (device, source, job) that decide its coverage state. */
    record RunFacts(String outcome, List<String> gapKinds, int chunks) {
        public RunFacts {
            gapKinds = List.copyOf(gapKinds);
        }
    }

    /** One coverage row. */
    record CoverageRow(String deviceId, String sourceKey, String jobId, LocalDate windowDay, String state, String reason,
            int historyDays) {
    }

    /**
     * The layer-B part of a finding (V273, phase P2; design sections 4.3, 4.4 and 7.2): the template, the catalog and
     * normaliser versions, the kinds that hold, the score with its factors and the device's baseline. Null on a layer-A
     * finding. {@code z}, {@code baselineMedian} and {@code baselineMad} are null when no baseline was usable.
     */
    record LayerB(String templateId, String templateText, String catalogVersion, String normaliserVersion,
            List<String> kinds, double rarity, double change, double score, Double z, Double baselineMedian,
            Double baselineMad, LocalDate deviceFirstSeen, LocalDate fleetFirstSeen) {
        public LayerB {
            kinds = List.copyOf(kinds);
        }
    }

    /** One finding row (design section 7.2): layer A (phase P1, {@code layerB} null) or layer B (phase P2). */
    record FindingRow(String findingId, String deviceId, String sourceKey, String ruleId, String ruleVersion, String kind,
            String markerId, String keyToken, LocalDate windowDay, LocalDateTime windowStart, LocalDateTime windowEnd,
            String severity, String severityRule, double severityWeight, String provenance, long eventCount,
            int peakBucketCount, int historyDays, int baselineDays, int presenceDays, int cohortDevices,
            int prevalenceDevices, boolean chronic, boolean fleetBackground, List<String> labels, String incidentGroup,
            String coverageState, List<Pointer> pointers, String maskingRuleSet, String markerListVersion,
            String configVersion, String reviewState, String reviewBy, Instant reviewAt, Instant firstRaisedAt,
            Instant updatedAt, LayerB layerB) {
        public FindingRow {
            labels = List.copyOf(labels);
            pointers = List.copyOf(pointers);
        }

        /** A layer-A (phase P1) finding. */
        public FindingRow(String findingId, String deviceId, String sourceKey, String ruleId, String ruleVersion, String kind,
                String markerId, String keyToken, LocalDate windowDay, LocalDateTime windowStart, LocalDateTime windowEnd,
                String severity, String severityRule, double severityWeight, String provenance, long eventCount,
                int peakBucketCount, int historyDays, int baselineDays, int presenceDays, int cohortDevices,
                int prevalenceDevices, boolean chronic, boolean fleetBackground, List<String> labels, String incidentGroup,
                String coverageState, List<Pointer> pointers, String maskingRuleSet, String markerListVersion,
                String configVersion, String reviewState, String reviewBy, Instant reviewAt, Instant firstRaisedAt,
                Instant updatedAt) {
            this(findingId, deviceId, sourceKey, ruleId, ruleVersion, kind, markerId, keyToken, windowDay, windowStart,
                    windowEnd, severity, severityRule, severityWeight, provenance, eventCount, peakBucketCount, historyDays,
                    baselineDays, presenceDays, cohortDevices, prevalenceDevices, chronic, fleetBackground, labels,
                    incidentGroup, coverageState, pointers, maskingRuleSet, markerListVersion, configVersion, reviewState,
                    reviewBy, reviewAt, firstRaisedAt, updatedAt, null);
        }

        /** {@code P1} (layer A) or {@code P2} (layer B). */
        public String phase() {
            return layerB == null ? "P1" : "P2";
        }

        /** The same finding with another incident group. */
        public FindingRow withIncidentGroup(String group) {
            return new FindingRow(findingId, deviceId, sourceKey, ruleId, ruleVersion, kind, markerId, keyToken, windowDay,
                    windowStart, windowEnd, severity, severityRule, severityWeight, provenance, eventCount, peakBucketCount,
                    historyDays, baselineDays, presenceDays, cohortDevices, prevalenceDevices, chronic, fleetBackground,
                    labels, group, coverageState, pointers, maskingRuleSet, markerListVersion, configVersion, reviewState,
                    reviewBy, reviewAt, firstRaisedAt, updatedAt, layerB);
        }

        /** The evidence of this finding with the review columns of {@code old} (the engine never rewrites a review). */
        public FindingRow withReviewOf(FindingRow old) {
            return new FindingRow(findingId, deviceId, sourceKey, ruleId, ruleVersion, kind, markerId, keyToken, windowDay,
                    windowStart, windowEnd, severity, severityRule, severityWeight, provenance, eventCount, peakBucketCount,
                    historyDays, baselineDays, presenceDays, cohortDevices, prevalenceDevices, chronic, fleetBackground,
                    labels, incidentGroup, coverageState, pointers, maskingRuleSet, markerListVersion, configVersion,
                    old.reviewState(), old.reviewBy(), old.reviewAt(), old.firstRaisedAt(), old.updatedAt(), layerB);
        }
    }

    /**
     * Filters of a findings list; null means no filter. {@code offset} pages through a list whose order is total
     * (severity, day, weight, window end, finding id); a page holds at most 1000 rows.
     */
    record FindingQuery(String deviceId, String severity, String reviewState, LocalDate sinceDay, int limit, int offset) {
        public FindingQuery {
            offset = Math.max(0, offset);
        }

        /** The first page. */
        public FindingQuery(String deviceId, String severity, String reviewState, LocalDate sinceDay, int limit) {
            this(deviceId, severity, reviewState, sinceDay, limit, 0);
        }
    }

    Settings settings();

    /** Audited write of the P1 switch (security_admin); the P2 switch keeps its value. */
    default Settings writeSettings(String actorFingerprint, boolean enabled, Instant shadowUntil) {
        return writeSettings(actorFingerprint, enabled, shadowUntil, settings().p2Enabled());
    }

    /** Audited write of both switches (security_admin), one row, one audit path (V272 trigger). */
    Settings writeSettings(String actorFingerprint, boolean enabled, Instant shadowUntil, boolean p2Enabled);

    /** Chunks not yet processed under these versions, oldest first; pruned chunks are not offered. */
    List<PendingChunk> pendingChunks(String maskingRuleSet, String markerListVersion, int limit);

    /** In one transaction: removes the chunk's earlier evidence rows, writes {@code events} and the index state. */
    void replaceChunkIndex(IndexState state, List<EventRow> events);

    /** Records a refused or failed chunk (no evidence rows), replacing an earlier state of the same versions. */
    void recordIndexState(IndexState state);

    /** The device-local days of this device's evidence for a job's chunks. */
    List<LocalDate> daysOfJob(String deviceId, String jobId);

    /** Distinct devices that have evidence rows on the device-local day, in id order. */
    List<String> devicesWithEvidence(LocalDate day);

    List<DayEvidence> dayEvidence(String deviceId, LocalDate day);

    /** Distinct usable days (indexed chunk, no gap record that day) with at most 28 days before {@code day}. */
    Baseline baseline(String deviceId, String sourceKey, String markerId, LocalDate day);

    /** Distinct days with at least one indexed chunk, all time. */
    int historyDays(String deviceId, String sourceKey);

    /** Devices with an evaluable coverage row for the source on the day. */
    int cohortSize(String sourceKey, LocalDate day);

    /** Devices with evidence of the marker on the day. */
    int prevalence(String sourceKey, String markerId, LocalDate day);

    RunFacts runFacts(String deviceId, String sourceKey, String jobId);

    /** Unparsed and total lines of a job's indexed chunks of one source. */
    long[] lineCounts(String deviceId, String sourceKey, String jobId);

    /** Index status of the job's chunks of one source ({@code status -> chunks}). */
    Map<String, Integer> indexStatuses(String deviceId, String sourceKey, String jobId);

    void upsertCoverage(CoverageRow row);

    /**
     * Marks as {@code EVALUATION_FAILED} the coverage rows of the collection jobs whose evidence lies on the device-local
     * {@code day} ({@code deviceId} null: of every device with evidence that day), so a day whose findings could not be
     * evaluated is UNKNOWN to the reader and never "no findings". Returns how many rows were marked; a job that has no
     * coverage row yet is already UNKNOWN and is not given one.
     */
    int markEvaluationFailed(String deviceId, LocalDate day);

    /** Coverage rows in the {@code EVALUATION_FAILED} state, oldest attempt first: the windows the next pass evaluates again. */
    List<CoverageRow> failedEvaluations(int limit);

    Optional<FindingRow> finding(String findingId);

    /**
     * Inserts a finding, or updates the evidence part of an existing one. The review columns ({@code review_state},
     * {@code review_by}, {@code review_at}) of an existing row are never written here: an acknowledgement made between the
     * engine's read and this write must survive it. A new row takes the review state of {@code row} (OPEN). The only
     * engine path that changes an existing review is {@link #reopenFinding}.
     */
    void upsertFinding(FindingRow row);

    /**
     * Audited: stores the evidence of {@code row} over an acknowledged finding, sets its review back to OPEN (review_by and
     * review_at cleared) and records a {@code REOPEN} row of {@code log_finding_action} for it, in one transaction. {@code actorFingerprint} is the system actor of the engine.
     */
    void reopenFinding(String actorFingerprint, FindingRow row, String note);

    List<FindingRow> findings(FindingQuery query);

    List<CoverageRow> coverage(String deviceId, LocalDate sinceDay);

    /** Findings of ATTENTION or CRITICAL severity per device and day since {@code sinceDay}, for the budget view. */
    List<BudgetRow> attentionCounts(LocalDate sinceDay);

    record BudgetRow(String deviceId, LocalDate day, String severity, int findings) {
    }

    /** Audited acknowledgement: records the action and sets the finding's review state in one transaction. */
    Optional<FindingRow> acknowledge(String actorFingerprint, String findingId, String note);

    /** Distinct device ids of the evidence on a day, for the fleet counters of the budget view. */
    int devicesWithCoverage(LocalDate day);
}
