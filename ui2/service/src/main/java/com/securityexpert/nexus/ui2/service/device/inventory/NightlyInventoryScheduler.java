package com.securityexpert.nexus.ui2.service.device.inventory;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import java.util.logging.Logger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Collects the enrolled fleet at the shared six-hour slots. */
@Component
public class NightlyInventoryScheduler {

    private static final Logger LOG = Logger.getLogger(NightlyInventoryScheduler.class.getName());
    public static final String ACTOR = "system:nightly-inventory";

    private final InventoryCollectService collectService;

    public NightlyInventoryScheduler(InventoryCollectService collectService) {
        this.collectService = collectService;
    }

    @Scheduled(cron = JobWindowPolicy.CRON, zone = JobWindowPolicy.ZONE)
    public void nightly() {
        try {
            InventoryCollectService.BulkOutcome o = collectService.requestCollectAll(ACTOR);
            LOG.info("[NIGHTLY_INVENTORY] enrolled=" + o.enrolledDevices() + " admitted=" + o.admitted() + " refused=" + o.refused());
        } catch (RuntimeException e) {
            LOG.warning("[NIGHTLY_INVENTORY] failed: " + e.getMessage());
        }
    }
}
