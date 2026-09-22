package com.securityexpert.nexus.ui2.worker.backup.retention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries;
import com.securityexpert.nexus.ui2.platform.WorkerActor;

/**
 * {@link RetentionPruningService.RetentionStorePort} over
 * {@link JooqRetentionQueries} and the artefact volume (V44 wires retention to
 * the policy for the first time; until then the executor was composed with
 * {@code null} and nothing was ever pruned). Never prunes a device's sole
 * remaining backup or its baseline.
 */
public final class JooqRetentionStorePort implements RetentionPruningService.RetentionStorePort {

    private static final Logger LOG = Logger.getLogger(JooqRetentionStorePort.class.getName());

    private final JooqRetentionQueries queries;
    private final Path storeRoot;

    public JooqRetentionStorePort(JooqRetentionQueries queries, Path storeRoot) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot").toAbsolutePath().normalize();
    }

    @Override
    public List<RetentionPruningService.ArtefactRecord> findExpiredStandardBackups(int days) {
        return queries.expiredStandardBackups(days).stream().map(JooqRetentionStorePort::toRecord).toList();
    }

    @Override
    public List<RetentionPruningService.ArtefactRecord> findExpiredSnapshots(int depth) {
        return queries.snapshotsBeyondDepth(depth).stream().map(JooqRetentionStorePort::toRecord).toList();
    }

    @Override
    public void recordTombstone(String ledgerId, String artefactId, String retentionTier) {
        queries.recordRemoved(ledgerId, artefactId, retentionTier, WorkerActor.RESERVED_ACTOR_FINGERPRINT, "backup_artefact_pruned");
    }

    @Override
    public boolean deleteArtefact(RetentionPruningService.ArtefactRecord record) {
        if (record.recoveryVolumePath() == null || record.recoveryVolumePath().isBlank()) {
            return false;
        }
        Path file = storeRoot.resolve(record.recoveryVolumePath()).normalize();
        if (!file.startsWith(storeRoot)) {
            LOG.warning("[RETENTION] refusing to delete a path outside the store root");
            return false;
        }
        try {
            // A file already gone (an orphan cleaned by hand) still counts as removed: the tombstone must land.
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            LOG.warning("[RETENTION] delete failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isSoleRemainingBackupForDevice(String deviceId, String artefactId) {
        return queries.otherLiveBackupsOfDevice(deviceId, artefactId) == 0;
    }

    @Override
    public long getVaultConsumedBytes() {
        return queries.liveCiphertextBytes();
    }

    private static RetentionPruningService.ArtefactRecord toRecord(JooqRetentionQueries.Candidate c) {
        return new RetentionPruningService.ArtefactRecord(c.artefactId(), c.deviceId(), c.recoveryVolumePath(),
                c.ciphertextBytes(), c.retentionTier(), c.backupType());
    }
}
