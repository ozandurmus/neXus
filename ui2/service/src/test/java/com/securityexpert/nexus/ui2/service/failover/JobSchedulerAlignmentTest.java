package com.securityexpert.nexus.ui2.service.failover;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.management.DiscoveryRefreshScheduler;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;
import com.securityexpert.nexus.ui2.service.device.inventory.NightlyInventoryScheduler;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationRefreshScheduler;
import static org.junit.jupiter.api.Assertions.*;

class JobSchedulerAlignmentTest {
    @Test void everyAutomaticBatchProducerUsesTheSameZoneAndSlots() throws Exception {
        for (var entry : java.util.Map.of(
                CpReadinessScheduler.class, "beginPass", CpFailoverService.class, "startDue",
                DiscoveryRefreshScheduler.class, "nightly", NightlyInventoryScheduler.class, "nightly").entrySet()) {
            var scheduled = entry.getKey().getMethod(entry.getValue()).getAnnotation(Scheduled.class);
            assertNotNull(scheduled);
            assertEquals(JobWindowPolicy.CRON, scheduled.cron(), entry.getKey().getName());
            assertEquals(JobWindowPolicy.ZONE, scheduled.zone());
        }
    }

    @Test void scheduledBackupsUseTheirOwnDailyWindow() throws Exception {
        var scheduled = DeviceCompositionConfiguration.BackupScheduleTrigger.class
                .getMethod("tick").getAnnotation(Scheduled.class);
        assertEquals(ScheduledBackupWindowPolicy.CRON, scheduled.cron());
        assertEquals(JobWindowPolicy.ZONE, scheduled.zone());
    }

    /** Configuration is read once a day, inside the 00:00 slot of the shared device-job windows. */
    @Test void configurationRefreshRunsDailyInTheMidnightSlot() throws Exception {
        var scheduled = ConfigurationRefreshScheduler.class.getMethod("daily").getAnnotation(Scheduled.class);
        assertEquals("0 0 0 * * *", scheduled.cron());
        assertEquals(JobWindowPolicy.ZONE, scheduled.zone());
        var cron = org.springframework.scheduling.support.CronExpression.parse(scheduled.cron());
        var next = cron.next(java.time.ZonedDateTime.of(2026, 10, 15, 13, 0, 0, 0, java.time.ZoneId.of(JobWindowPolicy.ZONE)));
        assertEquals(0, next.getHour());
        assertTrue(JobWindowPolicy.CRON.contains("0,6,12,18"), "midnight is one of the shared slots");
    }
}
