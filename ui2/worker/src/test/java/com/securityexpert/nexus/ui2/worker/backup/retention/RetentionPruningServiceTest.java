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
        RetentionPruningService.PruningSummary summary = service.prune(new RetentionPruningService.PruningPolicy(7, 4));

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
        RetentionPruningService.PruningSummary summary = service.prune(new RetentionPruningService.PruningPolicy(7, 4));

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
        RetentionPruningService.PruningSummary summary = service.prune(new RetentionPruningService.PruningPolicy(7, 4));

        // Last-backup protection: Sole backup must be preserved
        assertEquals(0, summary.backupsPruned());
        assertTrue(summary.prunedArtefactIds().isEmpty());
        assertTrue(recordedTombstones.isEmpty());
    }

    /** A store that records the horizon it was asked about and answers from fixed lists. */
    private static final class ConfigurationStore implements RetentionPruningService.RetentionStorePort {
        final List<Integer> backupDaysAsked = new ArrayList<>();
        final List<Integer> configurationDaysAsked = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final List<String> tombstones = new ArrayList<>();
        final java.util.Set<String> latest = new java.util.HashSet<>();
        final java.util.Set<String> undeletable = new java.util.HashSet<>();
        final List<RetentionPruningService.ArtefactRecord> expiredConfigurations = new ArrayList<>();

        @Override public List<RetentionPruningService.ArtefactRecord> findExpiredStandardBackups(int days) {
            backupDaysAsked.add(days);
            return List.of();
        }

        @Override public List<RetentionPruningService.ArtefactRecord> findExpiredSnapshots(int depth) {
            return List.of();
        }

        @Override public List<RetentionPruningService.ArtefactRecord> findExpiredConfigurationArchives(int days) {
            configurationDaysAsked.add(days);
            return expiredConfigurations;
        }

        @Override public boolean isLatestConfigurationArchive(String artefactId) {
            return latest.contains(artefactId);
        }

        @Override public boolean deleteArtefact(RetentionPruningService.ArtefactRecord record) {
            if (undeletable.contains(record.artefactId())) {
                return false;
            }
            deleted.add(record.artefactId());
            return true;
        }

        @Override public void recordTombstone(String ledgerId, String artefactId, String retentionTier) {
            tombstones.add(artefactId);
        }
    }

    private static RetentionPruningService.ArtefactRecord configuration(String id, String device) {
        return new RetentionPruningService.ArtefactRecord(id, device, id + ".enc", 10L, "standard", "standard");
    }

    @Test
    void configurationAndBackupHorizonsComeFromThePolicyIndependently() {
        ConfigurationStore store = new ConfigurationStore();

        new RetentionPruningService(store).prune(new RetentionPruningService.PruningPolicy(7, 4, 30));

        assertEquals(List.of(7), store.backupDaysAsked);
        assertEquals(List.of(30), store.configurationDaysAsked);
    }

    @Test
    void aPolicyWithoutAConfigurationHorizonDefaultsToThirtyDays() {
        ConfigurationStore store = new ConfigurationStore();

        new RetentionPruningService(store).prune(new RetentionPruningService.PruningPolicy(7, 4));

        assertEquals(List.of(30), store.configurationDaysAsked);
    }

    @Test
    void expiredConfigurationArchivesAreRemovedAndLedgeredButTheDevicesLatestIsKept() {
        ConfigurationStore store = new ConfigurationStore();
        store.expiredConfigurations.add(configuration("cfg-old-1", "dev-1"));
        store.expiredConfigurations.add(configuration("cfg-old-2", "dev-1"));
        store.expiredConfigurations.add(configuration("cfg-last", "dev-1"));
        store.expiredConfigurations.add(configuration("cfg-other", "dev-2"));
        store.latest.add("cfg-last");

        RetentionPruningService.PruningSummary summary =
                new RetentionPruningService(store).prune(new RetentionPruningService.PruningPolicy(7, 4, 30));

        assertEquals(3, summary.configurationsPruned());
        assertEquals(0, summary.backupsPruned());
        assertEquals(List.of("cfg-old-1", "cfg-old-2", "cfg-other"), store.deleted);
        assertEquals(store.deleted, store.tombstones, "every removal is ledgered, and only removals are");
        assertTrue(!store.deleted.contains("cfg-last"), "the device's last configuration archive is never removed");
        assertEquals(30L, summary.bytesReclaimed());
    }

    @Test
    void anUnknownLatestAnswerKeepsTheConfigurationArchive() {
        // The port default is "yes, it is the latest": a store that cannot tell must keep the archive.
        RetentionPruningService.RetentionStorePort silent = new RetentionPruningService.RetentionStorePort() {
            @Override public List<RetentionPruningService.ArtefactRecord> findExpiredStandardBackups(int days) { return List.of(); }
            @Override public List<RetentionPruningService.ArtefactRecord> findExpiredSnapshots(int depth) { return List.of(); }
            @Override public List<RetentionPruningService.ArtefactRecord> findExpiredConfigurationArchives(int days) {
                return List.of(configuration("cfg-1", "dev-1"));
            }
            @Override public boolean deleteArtefact(RetentionPruningService.ArtefactRecord record) {
                throw new AssertionError("must not delete");
            }
            @Override public void recordTombstone(String ledgerId, String artefactId, String retentionTier) {
                throw new AssertionError("must not record");
            }
        };

        assertEquals(0, new RetentionPruningService(silent).prune(new RetentionPruningService.PruningPolicy(7, 4, 30))
                .configurationsPruned());
    }

    @Test
    void aConfigurationArchiveThatCouldNotBeDeletedIsNeitherCountedNorLedgered() {
        ConfigurationStore store = new ConfigurationStore();
        store.expiredConfigurations.add(configuration("cfg-locked", "dev-1"));
        store.undeletable.add("cfg-locked");

        RetentionPruningService.PruningSummary summary =
                new RetentionPruningService(store).prune(new RetentionPruningService.PruningPolicy(7, 4, 30));

        assertEquals(0, summary.configurationsPruned());
        assertTrue(store.tombstones.isEmpty());
    }

    /** V180 / PO 2026-10-30: older copies of an open size anomaly go first, even inside the age window. */
    @Test
    void supersededSizeAnomalyCopiesArePrunedBeforeTheAgeRuleAndCountAsBackups() {
        List<String> order = new ArrayList<>();
        RetentionPruningService.RetentionStorePort stubStore = new RetentionPruningService.RetentionStorePort() {
            @Override
            public List<RetentionPruningService.ArtefactRecord> findSupersededSizeAnomalies() {
                order.add("superseded-read");
                return List.of(
                        new RetentionPruningService.ArtefactRecord("bad-1", "dev-1", "bad-1.enc", 29L, "standard", "standard"),
                        new RetentionPruningService.ArtefactRecord("bad-2", "dev-1", "bad-2.enc", 29L, "standard", "standard"));
            }

            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredStandardBackups(int days) {
                order.add("age-read");
                return List.of();
            }

            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredSnapshots(int depth) {
                return List.of();
            }

            @Override
            public boolean deleteArtefact(RetentionPruningService.ArtefactRecord record) {
                return true;
            }

            @Override
            public void recordTombstone(String ledgerId, String artefactId, String retentionTier) {
                order.add("removed:" + artefactId);
            }
        };

        RetentionPruningService.PruningSummary summary = new RetentionPruningService(stubStore)
                .prune(new RetentionPruningService.PruningPolicy(7, 4));

        assertEquals(List.of("superseded-read", "removed:bad-1", "removed:bad-2", "age-read"), order,
                "the age rule reads after the superseded copies are gone, so none is offered twice");
        assertEquals(2, summary.backupsPruned());
        assertEquals(58L, summary.bytesReclaimed());
    }

    @Test
    void aSupersededCopyThatIsTheDevicesSoleBackupIsKept() {
        List<String> removed = new ArrayList<>();
        RetentionPruningService.RetentionStorePort stubStore = new RetentionPruningService.RetentionStorePort() {
            @Override
            public List<RetentionPruningService.ArtefactRecord> findSupersededSizeAnomalies() {
                return List.of(new RetentionPruningService.ArtefactRecord("bad-1", "dev-1", "bad-1.enc", 29L, "standard", "standard"));
            }

            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredStandardBackups(int days) {
                return List.of();
            }

            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredSnapshots(int depth) {
                return List.of();
            }

            @Override
            public boolean isSoleRemainingBackupForDevice(String deviceId, String artefactId) {
                return true;
            }

            @Override
            public void recordTombstone(String ledgerId, String artefactId, String retentionTier) {
                removed.add(artefactId);
            }
        };

        assertEquals(0, new RetentionPruningService(stubStore).prune(new RetentionPruningService.PruningPolicy(7, 4)).backupsPruned());
        assertTrue(removed.isEmpty());
    }
}
