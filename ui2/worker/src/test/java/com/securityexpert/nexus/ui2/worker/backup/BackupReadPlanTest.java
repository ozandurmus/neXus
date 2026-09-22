package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The closed literal set (14H section 5) -- exactly these literals, in this order. */
class BackupReadPlanTest {

    @Test
    void sevenLiteralsMatch14HSectionFive() {
        assertEquals("clish -c \"show diskspace\"", BackupReadPlan.CP_SHOW_DISKSPACE);
        assertEquals("clish -c \"add backup local\"", BackupReadPlan.CP_ADD_BACKUP_LOCAL);
        assertEquals("clish -c \"show backup status\"", BackupReadPlan.CP_SHOW_BACKUP_STATUS);
        assertEquals("clish -c \"show backups\"", BackupReadPlan.CP_SHOW_BACKUPS);
        assertEquals("sha256sum %s", BackupReadPlan.CP_ARCHIVE_DIGEST_TEMPLATE);
        assertEquals("clish -c \"delete backup %s\"", BackupReadPlan.CP_DELETE_BACKUP_TEMPLATE);
        assertEquals(List.of(BackupReadPlan.CP_SHOW_DISKSPACE, BackupReadPlan.CP_DF_VAR_LOG, BackupReadPlan.CP_ADD_BACKUP_LOCAL,
                BackupReadPlan.CP_SHOW_BACKUP_STATUS, BackupReadPlan.CP_SHOW_BACKUPS,
                BackupReadPlan.CP_ARCHIVE_DIGEST_TEMPLATE, BackupReadPlan.CP_DELETE_BACKUP_TEMPLATE),
                BackupReadPlan.LITERALS);
    }

    @Test
    void archiveDigestAndDeleteCommandsSubstituteTheExactArchiveName() {
        assertEquals("sha256sum backup_2026-09-14.tgz", BackupReadPlan.archiveDigestCommand("backup_2026-09-14.tgz"));
        assertEquals("clish -c \"delete backup backup_2026-09-14.tgz\"",
                BackupReadPlan.deleteBackupCommand("backup_2026-09-14.tgz"));
    }
}
