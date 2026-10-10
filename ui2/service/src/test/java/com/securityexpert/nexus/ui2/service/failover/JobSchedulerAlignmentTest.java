package com.securityexpert.nexus.ui2.service.failover;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.management.DiscoveryRefreshScheduler;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;
import com.securityexpert.nexus.ui2.service.device.inventory.NightlyInventoryScheduler;
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
}
