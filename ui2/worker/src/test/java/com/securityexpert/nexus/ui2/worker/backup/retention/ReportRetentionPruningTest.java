package com.securityexpert.nexus.ui2.worker.backup.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Reports R1 contract "Storage": the retention janitor removes expired report artefacts like the other classes -- file
 * first, then the removed ledger row; a file that could not be deleted keeps its row for the next pass.
 */
class ReportRetentionPruningTest {

    @Test
    void expiredReportsArePrunedAndTombstonedOnlyWhenTheFileIsGone() {
        List<String> tombstones = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        var expired = List.of(
                new RetentionPruningService.ArtefactRecord("report-1", "platform:reports", "nexus/platform_reports/a.enc", 100, "report_30d", "standard"),
                new RetentionPruningService.ArtefactRecord("report-2", "platform:reports", "nexus/platform_reports/b.enc", 50, "report_30d", "standard"));
        var port = new RetentionPruningService.RetentionStorePort() {
            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredStandardBackups(int days) {
                return List.of();
            }

            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredSnapshots(int depth) {
                return List.of();
            }

            @Override
            public List<RetentionPruningService.ArtefactRecord> findExpiredReports() {
                return expired;
            }

            @Override
            public void recordTombstone(String ledgerId, String artefactId, String retentionTier) {
                tombstones.add(artefactId + ":" + retentionTier);
            }

            @Override
            public boolean deleteArtefact(RetentionPruningService.ArtefactRecord record) {
                deleted.add(record.artefactId());
                return !record.artefactId().equals("report-2"); // report-2's delete fails this pass
            }

            @Override
            public boolean isSoleRemainingBackupForDevice(String deviceId, String artefactId) {
                return true; // a device rule must not keep a report: there is none for reports
            }
        };
        var summary = new RetentionPruningService(port).prune(new RetentionPruningService.PruningPolicy(30, 4));
        assertEquals(1, summary.reportsPruned());
        assertEquals(100, summary.bytesReclaimed());
        assertEquals(List.of("report-1:report_30d"), tombstones);
        assertEquals(List.of("report-1", "report-2"), deleted);
        assertTrue(summary.prunedArtefactIds().contains("report-1"));
        assertEquals(0, summary.backupsPruned());
    }
}
