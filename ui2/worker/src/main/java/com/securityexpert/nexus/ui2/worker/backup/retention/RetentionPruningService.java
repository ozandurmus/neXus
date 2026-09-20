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
            int snapshotRetentionDepth
    ) {
        public static final PruningPolicy DEFAULT = new PruningPolicy(30, 2);
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

    public PruningSummary prune(PruningPolicy policy) {
        int backupsPruned = 0;
        int snapshotsPruned = 0;
        long bytesReclaimed = 0;
        List<String> prunedIds = new ArrayList<>();

        // 1. Standard daily backups older than 30 days
        List<ArtefactRecord> expiredBackups = storePort.findExpiredStandardBackups(policy.backupRetentionDays());
        for (ArtefactRecord rec : expiredBackups) {
            boolean removed = deleteStorageFile(rec.recoveryVolumePath());
            if (removed) {
                bytesReclaimed += rec.ciphertextBytes();
            }
            storePort.recordTombstone(UUID.randomUUID().toString(), rec.artefactId(), rec.retentionTier());
            backupsPruned++;
            prunedIds.add(rec.artefactId());
        }

        // 2. Weekly snapshots exceeding depth 2
        List<ArtefactRecord> expiredSnapshots = storePort.findExpiredSnapshots(policy.snapshotRetentionDepth());
        for (ArtefactRecord rec : expiredSnapshots) {
            boolean removed = deleteStorageFile(rec.recoveryVolumePath());
            if (removed) {
                bytesReclaimed += rec.ciphertextBytes();
            }
            storePort.recordTombstone(UUID.randomUUID().toString(), rec.artefactId(), rec.retentionTier());
            snapshotsPruned++;
            prunedIds.add(rec.artefactId());
        }

        return new PruningSummary(backupsPruned, snapshotsPruned, bytesReclaimed, prunedIds);
    }

    private boolean deleteStorageFile(String volumePath) {
        if (volumePath == null || volumePath.isBlank()) {
            return false;
        }
        try {
            return Files.deleteIfExists(Path.of(volumePath));
        } catch (IOException e) {
            System.err.println("Warning: Could not unlink pruned artefact file: " + volumePath + ": " + e.getMessage());
            return false;
        }
    }
}
