package com.securityexpert.nexus.ui2.worker.backup.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RetentionPruningServiceTest {

    @Test
    void prunesExpiredBackupsAndSnapshotsAndWritesTombstones() {
        List<String> recordedTombstones = new ArrayList<>();

        RetentionPruningService.RetentionStorePort stubStore = new RetentionPruningService.RetentionStorePort() {
            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredStandardBackups(int days) {
                return List.of(
                        new RetentionPruningService.ArtefactRecord("art-1", "dev-1", "/tmp/non-existent-1", 1024L, "standard", "standard"),
                        new RetentionPruningService.ArtefactRecord("art-2", "dev-2", "/tmp/non-existent-2", 2048L, "standard", "standard")
                );
            }

            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredSnapshots(int depth) {
                return List.of(
                        new RetentionPruningService.ArtefactRecord("snap-3", "dev-1", "/tmp/non-existent-3", 5000L, "standard", "snapshot")
                );
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
}
