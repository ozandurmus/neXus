package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.time.Instant;
import java.util.List;

/**
 * The reads and ledgered writes of the system-log retention pruning (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 5.3):
 * raw chunks and sar files after the raw retention, HCP archives after their own retention, the per-device cap with
 * oldest-first early deletion recorded as a visible {@code RETENTION_SHORTENED_BY_CAP} gap, and the 180-day ledger
 * rows. A pruned artefact keeps its manifest and index rows (the ledger); the bytes go, the index row gets
 * {@code pruned_at}, and {@code artefact_retention_ledger} gets a {@code removed} row, as backup pruning does.
 */
public interface SystemLogRetentionRepository {

    /** One stored artefact a pass may remove: a chunk ({@code table = system_log_chunk}) or a whole file. */
    record Candidate(String table, String rowId, String deviceId, String sourceKey, String artefactId,
            String recoveryVolumePath, long ciphertextBytes, long plaintextBytes, Instant collectedAt) {
        public static final String CHUNK = "system_log_chunk";
        public static final String FILE = "system_log_file";
    }

    /** Live stored bytes of one device that count against its cap. */
    record DeviceUsage(String deviceId, long ciphertextBytes) {
    }

    /** Live chunks collected more than {@code days} days ago, oldest first, at most {@code limit}. */
    List<Candidate> expiredChunks(int days, int limit);

    /** Live whole files of {@code kind} collected more than {@code days} days ago, oldest first, at most {@code limit}. */
    List<Candidate> expiredFiles(String kind, int days, int limit);

    /** Devices whose live raw chunks plus sar files exceed {@code capBytes} (section 5.3: sar is within the 2 GiB). */
    List<DeviceUsage> devicesOverCap(long capBytes);

    /** The device's live chunks and sar files, oldest first, at most {@code limit}. */
    List<Candidate> oldestCapped(String deviceId, int limit);

    /**
     * Audited: the {@code removed} ledger row and the index row's {@code pruned_at} (and, for a chunk, its
     * {@code prune_reason}: {@code RETENTION} or {@code RETENTION_SHORTENED_BY_CAP}). False when the row was already
     * pruned.
     */
    boolean markPruned(Candidate candidate, String reason);

    /** Audited: the visible record of a cap prune (section 5.3, PO Q4), one per (device, source) and pass. */
    void recordCapGap(String passId, String deviceId, String sourceKey, long prunedPlaintextBytes, String detailJson);

    /** Removes gap, run and alert rows and removed crash entries older than {@code days} days; returns the row count. */
    int pruneLedgerRows(int days);
}
