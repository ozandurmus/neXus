package com.securityexpert.nexus.ui2.worker.backup.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RetentionPruningServiceTest {

    @Test
    void prunesExpiredBackupsAndSnapshotsAndWritesTruthfulTombstones() {
        List<String> recordedTombstones = new ArrayList<>();

        RetentionPruningService.RetentionStorePort stubStore = new RetentionPruningService.RetentionStorePort() {
            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredStandardBackups(int days) {
                return List.of(
                        new RetentionPruningService.ArtefactRecord("art-1", "dev-1", "/vault/art-1.bin", 1024L, "standard", "standard"),
                        new RetentionPruningService.ArtefactRecord("art-2", "dev-2", "/vault/art-2.bin", 2048L, "standard", "standard")
                );
            }

            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredSnapshots(int depth) {
                return List.of(
                        new RetentionPruningService.ArtefactRecord("snap-3", "dev-1", "/vault/snap-3.bin", 5000L, "standard", "snapshot")
                );
            }

            @Override
            public boolean deleteArtefact(RetentionPruningService.ArtefactRecord record) {
                // Simulate successful store-owned deletion
                return true;
            }

            @Override
            public void recordTombstone(String ledgerId, String artefactId, String retentionTier) {
                recordedTombstones.add(artefactId);
            }
        };

        RetentionPruningService service = new RetentionPruningService(stubStore);
        RetentionPruningService.PruningSummary summary = service.prune(RetentionPruningService.PruningPolicy.DEFAULT);

        assertEquals(2, summary.backupsPruned());
        assertEquals(1, summary.snapshotsPruned());
        assertEquals(3, summary.prunedArtefactIds().size());
        assertEquals(List.of("art-1", "art-2", "snap-3"), recordedTombstones);
    }

    @Test
    void failedDeletionDoesNotWriteTombstoneOrCountAsPruned() {
        List<String> recordedTombstones = new ArrayList<>();

        RetentionPruningService.RetentionStorePort stubStore = new RetentionPruningService.RetentionStorePort() {
            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredStandardBackups(int days) {
                return List.of(
                        new RetentionPruningService.ArtefactRecord("art-fail", "dev-1", "/vault/locked.bin", 1024L, "standard", "standard")
                );
            }

            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredSnapshots(int depth) {
                return List.of();
            }

            @Override
            public boolean deleteArtefact(RetentionPruningService.ArtefactRecord record) {
                // Deletion failed (e.g. permission or lock error)
                return false;
            }

            @Override
            public void recordTombstone(String ledgerId, String artefactId, String retentionTier) {
                recordedTombstones.add(artefactId);
            }
        };

        RetentionPruningService service = new RetentionPruningService(stubStore);
        RetentionPruningService.PruningSummary summary = service.prune(RetentionPruningService.PruningPolicy.DEFAULT);

        // Truthful tombstone invariant: Nothing counted as pruned, no tombstone written for unremoved file
        assertEquals(0, summary.backupsPruned());
        assertEquals(0, summary.prunedArtefactIds().size());
        assertTrue(recordedTombstones.isEmpty());
    }

    @Test
    void preservesSoleRemainingBackupForDeviceEvenIfExpired() {
        List<String> recordedTombstones = new ArrayList<>();

        RetentionPruningService.RetentionStorePort stubStore = new RetentionPruningService.RetentionStorePort() {
            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredStandardBackups(int days) {
                return List.of(
                        new RetentionPruningService.ArtefactRecord("art-sole", "dev-orphan", "/vault/sole.bin", 4096L, "standard", "standard")
                );
            }

            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredSnapshots(int depth) {
                return List.of();
            }

            @Override
            public boolean isSoleRemainingBackupForDevice(String deviceId, String artefactId) {
                return "art-sole".equals(artefactId);
            }

            @Override
            public boolean deleteArtefact(RetentionPruningService.ArtefactRecord record) {
                return true;
            }

            @Override
            public void recordTombstone(String ledgerId, String artefactId, String retentionTier) {
                recordedTombstones.add(artefactId);
            }
        };

        RetentionPruningService service = new RetentionPruningService(stubStore);
        RetentionPruningService.PruningSummary summary = service.prune(RetentionPruningService.PruningPolicy.DEFAULT);

        // Last-backup protection: Sole backup must be preserved
        assertEquals(0, summary.backupsPruned());
        assertTrue(summary.prunedArtefactIds().isEmpty());
        assertTrue(recordedTombstones.isEmpty());
    }
}
