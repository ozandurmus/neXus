package com.securityexpert.nexus.ui2.jobs.window;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledBackupWindowPolicyTest {
    @Test void exemptionNamesExactlyTheScheduledTypesAndActors() {
        var scheduled = new HashSet<>(ScheduledBackupWindowPolicy.FLEET_JOB_TYPES);
        scheduled.add(ScheduledBackupWindowPolicy.MDS_EXPORT);
        var backups = new HashSet<>(BackupCapabilityIds.ALL);
        backups.remove(BackupCapabilityIds.CP_GAIA_SNAPSHOT);
        assertEquals(backups, scheduled);
        for (var type : ScheduledBackupWindowPolicy.FLEET_JOB_TYPES) {
            assertTrue(ScheduledBackupWindowPolicy.isScheduledBackup(type, ScheduledBackupWindowPolicy.ACTOR));
            assertFalse(ScheduledBackupWindowPolicy.isScheduledBackup(type, "synthetic-operator"));
            assertFalse(ScheduledBackupWindowPolicy.isScheduledBackup(type, ScheduledBackupWindowPolicy.MDS_ACTOR));
        }
        assertTrue(ScheduledBackupWindowPolicy.isScheduledBackup(BackupCapabilityIds.CP_MDS_EXPORT,
                ScheduledBackupWindowPolicy.MDS_ACTOR));
        for (var type : Set.of(BackupCapabilityIds.CP_GAIA_SNAPSHOT, "cp_inventory_collect", "unknown"))
            assertFalse(ScheduledBackupWindowPolicy.isScheduledBackup(type, ScheduledBackupWindowPolicy.ACTOR));
    }

    @Test void everyMinutePreservesPollingAndManualWindowsAndSeparatesScheduledBackups() {
        var midnight = LocalDateTime.parse("2026-10-09T00:00:00").atZone(JobWindowPolicy.ZONE_ID).toInstant();
        for (int minute = 0; minute < 24 * 60; minute++) {
            var now = midnight.plusSeconds(minute * 60L);
            var policy = new JobWindowPolicy(Clock.fixed(now, JobWindowPolicy.ZONE_ID), 60);
            boolean polling = minute % 360 < 60;
            boolean backup = minute >= 120 && minute < 180;
            assertEquals(polling, policy.isOpen());
            assertEquals(backup, ScheduledBackupWindowPolicy.isOpen(now));
            for (var type : ScheduledBackupWindowPolicy.FLEET_JOB_TYPES) {
                assertAdmission(policy, type, ScheduledBackupWindowPolicy.ACTOR, backup);
                assertAdmission(policy, type, "synthetic-operator", polling);
            }
            assertAdmission(policy, BackupCapabilityIds.CP_MDS_EXPORT, ScheduledBackupWindowPolicy.MDS_ACTOR, backup);
            assertAdmission(policy, "cp_inventory_collect", ScheduledBackupWindowPolicy.ACTOR, polling);
            assertAdmission(policy, BackupCapabilityIds.CP_GAIA_SNAPSHOT, "synthetic-operator", polling);
        }
    }

    @Test void fixedBackupWindowIgnoresPollingDurationAndIncludesTheLastSecond() {
        for (String local : Set.of("2026-10-09T02:00:00", "2026-10-09T02:59:59.999")) {
            var now = LocalDateTime.parse(local).atZone(JobWindowPolicy.ZONE_ID).toInstant();
            assertAdmission(new JobWindowPolicy(Clock.fixed(now, JobWindowPolicy.ZONE_ID), 15),
                    "cp_gateway_backup", ScheduledBackupWindowPolicy.ACTOR, true);
        }
        var end = LocalDateTime.parse("2026-10-09T03:00:00").atZone(JobWindowPolicy.ZONE_ID).toInstant();
        // Even a six-hour polling window cannot admit scheduled backups at 03:00.
        assertAdmission(new JobWindowPolicy(Clock.fixed(end, JobWindowPolicy.ZONE_ID), 360),
                "cp_gateway_backup", ScheduledBackupWindowPolicy.ACTOR, false);
    }

    private static void assertAdmission(JobWindowPolicy policy, String type, String actor, boolean allowed) {
        if (allowed) assertDoesNotThrow(() -> policy.requireDeviceJobOpen(type, type, actor));
        else assertThrows(JobWindowPolicy.OutsideWindow.class, () -> policy.requireDeviceJobOpen(type, type, actor));
    }
}
