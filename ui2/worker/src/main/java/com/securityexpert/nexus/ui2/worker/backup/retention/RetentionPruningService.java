package com.securityexpert.nexus.ui2.worker.backup.retention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Retention and Pruning Engine for the neXus Backup Engine (PO decision 2026-10-18; C7 contract Amendment A-2).
 *
 * <ul>
 *   <li>Retention is automatic and runs hourly from the worker: a standard backup is pruned once its
 *       {@code created_at} is older than the policy's {@code backup_retention_days} (V44 {@code backup_policy}).</li>
 *   <li>Weekly snapshots beyond the policy's depth are pruned.</li>
 *   <li>A configuration archive is pruned once older than the policy's {@code configuration_retention_days}
 *       (V164, default 30).</li>
 *   <li>A device's last archive (of the class being pruned) and its baseline are always kept.</li>
 *   <li>Backup size anomaly (V180, PO decision 2026-10-30): while a device has an unresolved size anomaly its last
 *       good backup and its newest anomalous copy are kept whatever their age, and its older anomalous copies are
 *       pruned even inside the age window (counted in {@code backupsPruned}).</li>
 *   <li>A removal is a {@code removed} row in {@code artefact_retention_ledger}; the {@code backup_artefact} row stays.
 *       {@code expires_at} plays no part.</li>
 * </ul>
 * The days always come from the policy row; this class has no built-in horizon.
 */
public final class RetentionPruningService {

    public record PruningPolicy(
            int backupRetentionDays,
            int snapshotRetentionDepth,
            int configurationRetentionDays,
            long maxStorageBudgetBytes
    ) {
        /** Matches the V164 column default; used when a caller supplies no configuration horizon. */
        public static final int DEFAULT_CONFIGURATION_RETENTION_DAYS = 30;
        // PO requirement: 400 GiB total vault budget.
        public static final long DEFAULT_STORAGE_BUDGET_BYTES = 400L * 1024L * 1024L * 1024L; // 400 GiB

        public PruningPolicy(int backupRetentionDays, int snapshotRetentionDepth, int configurationRetentionDays) {
            this(backupRetentionDays, snapshotRetentionDepth, configurationRetentionDays, DEFAULT_STORAGE_BUDGET_BYTES);
        }

        public PruningPolicy(int backupRetentionDays, int snapshotRetentionDepth) {
            this(backupRetentionDays, snapshotRetentionDepth, DEFAULT_CONFIGURATION_RETENTION_DAYS,
                    DEFAULT_STORAGE_BUDGET_BYTES);
        }
    }

    public record PruningSummary(
            int backupsPruned,
            int snapshotsPruned,
            int configurationsPruned,
            long bytesReclaimed,
            List<String> prunedArtefactIds
    ) {}

    public interface RetentionStorePort {
        List<ArtefactRecord> findExpiredStandardBackups(int days);
        List<ArtefactRecord> findExpiredSnapshots(int depth);

        /**
         * V180: a device's unresolved size-anomalous copies other than its newest, whatever their age; the default
         * (none) keeps stores that predate V180 working.
         */
        default List<ArtefactRecord> findSupersededSizeAnomalies() {
            return List.of();
        }

        /** Configuration archives older than {@code days}; the default (none) keeps stores that predate V164 working. */
        default List<ArtefactRecord> findExpiredConfigurationArchives(int days) {
            return List.of();
        }
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

        /**
         * Gate ensuring a device's latest live configuration archive is never pruned. The default answer is "yes, it is
         * the latest": a store that cannot tell keeps the archive.
         */
        default boolean isLatestConfigurationArchive(String artefactId) {
            return true;
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
        int configurationsPruned = 0;
        long bytesReclaimed = 0;
        List<String> prunedIds = new ArrayList<>();

        // 0. Older copies of an unresolved size anomaly: identical bad copies have no recovery value (PO 2026-10-30).
        // Run before the age rule, which reads its list afterwards and so never offers a copy removed here.
        for (ArtefactRecord rec : storePort.findSupersededSizeAnomalies()) {
            if (storePort.isSoleRemainingBackupForDevice(rec.deviceId(), rec.artefactId())) {
                continue;
            }
            if (storePort.deleteArtefact(rec)) {
                bytesReclaimed += rec.ciphertextBytes();
                storePort.recordTombstone(UUID.randomUUID().toString(), rec.artefactId(), rec.retentionTier());
                backupsPruned++;
                prunedIds.add(rec.artefactId());
            }
        }

        // 1. Standard daily backups older than retention window (never a pinned last good or the newest anomaly)
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

        // 3. Configuration archives older than the policy's configuration horizon; the latest per device stays.
        // Asked per row (not once up front): after one archive is removed the next one may have become the latest.
        for (ArtefactRecord rec : storePort.findExpiredConfigurationArchives(policy.configurationRetentionDays())) {
            if (storePort.isLatestConfigurationArchive(rec.artefactId())) {
                continue;
            }

            boolean removed = storePort.deleteArtefact(rec);
            if (removed) {
                bytesReclaimed += rec.ciphertextBytes();
                storePort.recordTombstone(UUID.randomUUID().toString(), rec.artefactId(), rec.retentionTier());
                configurationsPruned++;
                prunedIds.add(rec.artefactId());
            }
        }

        return new PruningSummary(backupsPruned, snapshotsPruned, configurationsPruned, bytesReclaimed, prunedIds);
    }
}
