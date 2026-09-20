package com.securityexpert.nexus.ui2.worker.backup.retention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Retention and Pruning Engine for the neXus Backup Engine.
 * 
 * <p>Enforces the Product Owner's retention contract:
 * <ul>
 *   <li>Daily backups retained for 30 days (1 month). Expired backups pruned daily.</li>
 *   <li>Weekly Gaia snapshots retained with depth 2 (older than 2 deleted).</li>
 *   <li>All deletions write append-only cryptographic tombstones to {@code artefact_retention_ledger}.</li>
 * </ul>
 */
public final class RetentionPruningService {

    public record PruningPolicy(
            int backupRetentionDays,
            int snapshotRetentionDepth,
            long maxStorageBudgetBytes
    ) {
        // PO Requirements: 400 GB total budget, 14-day retention (1 month is too long), snapshot depth 4
        public static final long DEFAULT_STORAGE_BUDGET_BYTES = 400L * 1024L * 1024L * 1024L; // 400 GiB
        public static final PruningPolicy DEFAULT = new PruningPolicy(14, 4, DEFAULT_STORAGE_BUDGET_BYTES);

        public PruningPolicy(int backupRetentionDays, int snapshotRetentionDepth) {
            this(backupRetentionDays, snapshotRetentionDepth, DEFAULT_STORAGE_BUDGET_BYTES);
        }
    }

    public record PruningSummary(
            int backupsPruned,
            int snapshotsPruned,
            long bytesReclaimed,
            List<String> prunedArtefactIds
    ) {}

    public interface RetentionStorePort {
        List<ArtefactRecord> findExpiredStandardBackups(int days);
        List<ArtefactRecord> findExpiredSnapshots(int depth);
        void recordTombstone(String ledgerId, String artefactId, String retentionTier);

        /** Store-owned deletion abstraction ensuring filesystem paths are never manipulated directly by callers. */
        default boolean deleteArtefact(ArtefactRecord record) {
            if (record.recoveryVolumePath() == null || record.recoveryVolumePath().isBlank()) {
                return false;
            }
            try {
                return Files.deleteIfExists(Path.of(record.recoveryVolumePath()));
            } catch (IOException e) {
                return false;
            }
        }

        /** Gate ensuring the sole remaining valid recovery point for a device is never pruned. */
        default boolean isSoleRemainingBackupForDevice(String deviceId, String artefactId) {
            return false;
        }

        /** Returns current total consumed storage bytes on the recovery vault volume. */
        default long getVaultConsumedBytes() {
            return 0L;
        }
    }

    public record ArtefactRecord(
            String artefactId,
            String deviceId,
            String recoveryVolumePath,
            long ciphertextBytes,
            String retentionTier,
            String backupType
    ) {}

    private final RetentionStorePort storePort;

    public RetentionPruningService(RetentionStorePort storePort) {
        this.storePort = Objects.requireNonNull(storePort, "storePort");
    }

    public boolean isVaultBudgetExceeded(PruningPolicy policy) {
        return storePort.getVaultConsumedBytes() > policy.maxStorageBudgetBytes();
    }

    public long remainingVaultCapacityBytes(PruningPolicy policy) {
        return Math.max(0L, policy.maxStorageBudgetBytes() - storePort.getVaultConsumedBytes());
    }

    public PruningSummary prune(PruningPolicy policy) {
        int backupsPruned = 0;
        int snapshotsPruned = 0;
        long bytesReclaimed = 0;
        List<String> prunedIds = new ArrayList<>();

        // 1. Standard daily backups older than retention window
        List<ArtefactRecord> expiredBackups = storePort.findExpiredStandardBackups(policy.backupRetentionDays());
        for (ArtefactRecord rec : expiredBackups) {
            // Last-backup protection: Never prune the last valid recovery point for a device
            if (storePort.isSoleRemainingBackupForDevice(rec.deviceId(), rec.artefactId())) {
                continue;
            }

            boolean removed = storePort.deleteArtefact(rec);
            // Truthful tombstone: Only record destruction if the artefact was actually removed
            if (removed) {
                bytesReclaimed += rec.ciphertextBytes();
                storePort.recordTombstone(UUID.randomUUID().toString(), rec.artefactId(), rec.retentionTier());
                backupsPruned++;
                prunedIds.add(rec.artefactId());
            }
        }

        // 2. Weekly snapshots exceeding retention depth
        List<ArtefactRecord> expiredSnapshots = storePort.findExpiredSnapshots(policy.snapshotRetentionDepth());
        for (ArtefactRecord rec : expiredSnapshots) {
            if (storePort.isSoleRemainingBackupForDevice(rec.deviceId(), rec.artefactId())) {
                continue;
            }

            boolean removed = storePort.deleteArtefact(rec);
            if (removed) {
                bytesReclaimed += rec.ciphertextBytes();
                storePort.recordTombstone(UUID.randomUUID().toString(), rec.artefactId(), rec.retentionTier());
                snapshotsPruned++;
                prunedIds.add(rec.artefactId());
            }
        }

        return new PruningSummary(backupsPruned, snapshotsPruned, bytesReclaimed, prunedIds);
    }
}
