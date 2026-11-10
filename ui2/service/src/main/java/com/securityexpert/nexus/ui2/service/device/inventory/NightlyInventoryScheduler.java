package com.securityexpert.nexus.ui2.service.device.inventory;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import java.util.logging.Logger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.securityexpert.nexus.ui2.service.schedule.FamilySwitch;
import com.securityexpert.nexus.ui2.service.schedule.ScheduledJobFamily;

/** Collects the enrolled fleet at the shared six-hour slots. */
@Component
public class NightlyInventoryScheduler {

    private static final Logger LOG = Logger.getLogger(NightlyInventoryScheduler.class.getName());
    public static final String ACTOR = "system:nightly-inventory";

    private final InventoryCollectService collectService;
    private final FamilySwitch switches;

    public NightlyInventoryScheduler(InventoryCollectService collectService, FamilySwitch switches) {
        this.collectService = collectService;
        this.switches = switches;
    }

    @Scheduled(cron = JobWindowPolicy.CRON, zone = JobWindowPolicy.ZONE)
    public void nightly() {
        if (!switches.allows(ScheduledJobFamily.NIGHTLY_INVENTORY, "NIGHTLY_INVENTORY")) return;
        try {
            InventoryCollectService.BulkOutcome o = collectService.requestCollectAll(ACTOR);
            LOG.info("[NIGHTLY_INVENTORY] enrolled=" + o.enrolledDevices() + " admitted=" + o.admitted() + " refused=" + o.refused());
        } catch (RuntimeException e) {
            LOG.warning("[NIGHTLY_INVENTORY] failed: " + e.getMessage());
        }
    }
}
