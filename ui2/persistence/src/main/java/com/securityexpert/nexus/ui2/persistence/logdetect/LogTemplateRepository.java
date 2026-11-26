package com.securityexpert.nexus.ui2.persistence.logdetect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.PendingChunk;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Pointer;

/**
 * The storage port of the log detection engine, phase P2 (V273; LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 4,
 * 6.5 and 8.3): template catalog versions, the online template counts and the layer-B findings. Every method moves
 * template ids, closed-vocabulary template forms, counters, device-local times and pointers. No method accepts or
 * returns a log line, masked or not.
 */
public interface LogTemplateRepository {

    // ------------------------------------------------------------ catalog (design section 6.5)

    /** One catalog version; {@code manifestJson} and {@code diffJson} hold closed keys and values written by the service. */
    record CatalogRow(String catalogVersion, String state, String previousVersion, String manifestJson, String digest,
            int templateCount, String diffJson, Instant createdAt, Instant frozenAt) {
        public boolean frozen() {
            return "FROZEN".equals(state);
        }
    }

    /** One template of a catalog version (design section 4.2). */
    record TemplateRow(String catalogVersion, String templateId, String sourceKey, String templateText, String matchForm,
            int tokenCount, String markerId, double severityWeight, String templateClass, String provenance, long lineCount,
            int prevalenceDevices, int cohortDevices, LocalDate fleetFirstSeen) {
    }

    /** A device's usable training days for one source in a catalog version. */
    record DeviceWindowRow(String catalogVersion, String deviceId, String sourceKey, int usableDays, LocalDate firstDay,
            LocalDate lastDay) {
    }

    /** A device's baseline of one template in a catalog version (segment {@code ALL} until design 6.4 lands). */
    record DeviceStatRow(String catalogVersion, String deviceId, String sourceKey, String templateId, String segment,
            LocalDate firstSeen, LocalDate lastSeen, long lineCount, int presenceDays, int usableDays, double presenceShare,
            double periodicity, double baselineMedian, double baselineMad, int bucketsUsed) {
    }

    /** A stored chunk of the training window (not pruned), in the build's deterministic order. */
    record TrainingChunk(String chunkId, String deviceId, String sourceKey, LocalDate logDay) {
    }

    /**
     * Stored, not pruned chunks of {@code sources} whose day lies in {@code [from, to]}, ordered by source, day, device
     * and chunk id (the order the offline build feeds the miner, so a rebuild is deterministic), at most {@code limit}.
     */
    List<TrainingChunk> trainingChunks(Collection<String> sources, LocalDate from, LocalDate to, int limit);

    /** Device-local days with a {@code system_log_gap} record, per {@code device|source}, in {@code [from, to]}. */
    Map<String, Set<LocalDate>> gapDays(Collection<String> sources, LocalDate from, LocalDate to);

    /** {@code cat-N} with N one above the highest stored version. */
    String nextCatalogVersion();

    /** Stores a DRAFT version with its templates and baselines in one transaction; the catalog row is audited. */
    void saveDraft(String actorFingerprint, CatalogRow catalog, List<TemplateRow> templates, List<DeviceWindowRow> windows,
            List<DeviceStatRow> stats);

    Optional<CatalogRow> catalog(String catalogVersion);

    /** Every version, newest first, at most 100. */
    List<CatalogRow> catalogs();

    /** The newest FROZEN version: the one matched online. */
    Optional<CatalogRow> activeCatalog();

    List<TemplateRow> templates(String catalogVersion);

    /**
     * Audited freeze of a DRAFT version: state FROZEN, frozen by and at, the previous FROZEN version and the diff against
     * it, in one statement guarded by {@code state = 'DRAFT'}. Empty when the version is missing or not a DRAFT.
     */
    Optional<CatalogRow> freeze(String actorFingerprint, String catalogVersion, String previousVersion, String diffJson);

    List<DeviceStatRow> deviceStats(String catalogVersion, String deviceId, String sourceKey);

    Optional<DeviceWindowRow> deviceWindow(String catalogVersion, String deviceId, String sourceKey);

    /** Devices of the catalog version that hold the template in their training stats. */
    Map<String, Integer> catalogPrevalence(String catalogVersion, String sourceKey, Collection<String> templateIds);

    // ------------------------------------------------------------ online matching (design section 4.1 step 5)

    /** What the P2 indexer did with one chunk under one catalog version. */
    record TemplateIndexState(String chunkId, String catalogVersion, String maskingRuleSet, String normaliserVersion,
            String deviceId, String sourceKey, String jobId, LocalDate logDay, String status, long lines, long matched,
            long provisional, long unparsed) {
    }

    /** One P2 evidence row; {@code countId} is deterministic, so a re-index writes the same rows. */
    record CountRow(String countId, String chunkId, String catalogVersion, String deviceId, String sourceKey,
            String templateId, boolean provisional, LocalDateTime bucketStart, double severityWeight, String markerId,
            int lineCount, LocalDateTime firstLineTime, LocalDateTime lastLineTime, List<Pointer> pointers) {
        public CountRow {
            pointers = List.copyOf(pointers);
        }
    }

    /** A provisional template id with its vocabulary form and the first day it was counted. */
    record ProvisionalTemplate(String templateId, String sourceKey, String templateText, LocalDate firstSeenDay) {
    }

    /** Chunks not yet processed under these versions, newest log day first (then collection order); pruned chunks and windows not yet closed are not offered. */
    List<PendingChunk> pendingChunks(String catalogVersion, String maskingRuleSet, String normaliserVersion,
            Collection<String> sources, int limit);

    /** Whether {@code (deviceId, sourceKey)} still has a closed, non-pruned chunk in {@code [from, toExclusive)} not yet processed under these versions. */
    default boolean hasUnprocessedHistory(String catalogVersion, String maskingRuleSet, String normaliserVersion,
            String deviceId, String sourceKey, LocalDate from, LocalDate toExclusive) {
        return false;
    }

    /** Distinct device-local days on or after {@code fromDay} with P2 counts whose {@code [day - 28, day)} history has no unprocessed closed chunks left. */
    default List<LocalDate> readyCountDaysAfter(String catalogVersion, String maskingRuleSet, String normaliserVersion,
            String deviceId, String sourceKey, LocalDate fromDay) {
        return List.of();
    }

    /** In one transaction: removes the chunk's earlier rows of this catalog version, writes {@code rows}, the provisional forms and the state. */
    void replaceChunkCounts(TemplateIndexState state, List<CountRow> rows, List<ProvisionalTemplate> provisional);

    /** Records a refused or failed chunk (no rows). */
    void recordIndexState(TemplateIndexState state);

    /** Every count row of the device and source with {@code from <= bucket day < toExclusive} under the catalog version. */
    List<CountRow> counts(String catalogVersion, String deviceId, String sourceKey, LocalDate from, LocalDate toExclusive);

    /** Distinct templates counted on the device for the source before {@code day} (any earlier day still stored). */
    Set<String> templatesBefore(String catalogVersion, String deviceId, String sourceKey, LocalDate day);

    /** Distinct device-local days with an INDEXED P2 chunk of the device and source, {@code from <= day < toExclusive}. */
    List<LocalDate> indexedDays(String catalogVersion, String deviceId, String sourceKey, LocalDate from, LocalDate toExclusive);

    /** Per device: distinct INDEXED P2 days of the source in {@code [from, toExclusive)}. */
    Map<String, Integer> indexedDaysPerDevice(String catalogVersion, String sourceKey, LocalDate from, LocalDate toExclusive);

    /** Per template: the devices that have a count row of it in {@code [from, toInclusive]}. */
    Map<String, Set<String>> templateDevices(String catalogVersion, String sourceKey, Collection<String> templateIds,
            LocalDate from, LocalDate toInclusive);

    /** Devices with a count row of the source on the device-local day, in id order. */
    List<String> devicesWithCounts(String catalogVersion, String sourceKey, LocalDate day);

    /** Vocabulary forms of provisional ids. */
    Map<String, String> provisionalTexts(Collection<String> templateIds);

    // ------------------------------------------------------------ layer-B findings (design section 7.2)

    /**
     * Inserts a layer-B finding ({@code phase = 'P2'}, {@code layer = 'B'}) or updates the evidence and score of an
     * existing one. Never writes the review columns of an existing row (as {@link LogDetectionRepository#upsertFinding}).
     */
    void upsertLayerBFinding(FindingRow row);

    /**
     * Removes the layer-B findings of a device, source and day that a new evaluation no longer raises, as long as no one
     * reviewed them ({@code review_state = 'OPEN'}): a derived, recomputable row (a reviewed one stays with its review).
     * Returns how many rows were removed.
     */
    int deleteStaleLayerBFindings(String deviceId, String sourceKey, LocalDate day, Collection<String> keepFindingIds);

    /** Audited: stores the evidence over an acknowledged finding, sets it OPEN and records a {@code REOPEN} action. */
    void reopenLayerBFinding(String actorFingerprint, FindingRow row, String note);
}
