package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.logging.Logger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Collects inventory for the whole enrolled fleet every evening at 23:00 Europe/Istanbul (PO, 2026-09-23): after the
 * weekday evening policy installs, so each gateway's "policy installed" time (read in the same inventory session,
 * POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md) is current for the next morning and for the PO's own nightly mail query.
 * The same read-only inventory jobs an operator's Bulk Collect admits; nothing new is issued to a device.
 */
@Component
public class NightlyInventoryScheduler {

    private static final Logger LOG = Logger.getLogger(NightlyInventoryScheduler.class.getName());
    public static final String ACTOR = "system:nightly-inventory";

    private final InventoryCollectService collectService;

    public NightlyInventoryScheduler(InventoryCollectService collectService) {
        this.collectService = collectService;
    }

    @Scheduled(cron = "0 0 23 * * *", zone = "Europe/Istanbul")
    public void nightly() {
        try {
            InventoryCollectService.BulkOutcome o = collectService.requestCollectAll(ACTOR);
            LOG.info("[NIGHTLY_INVENTORY] enrolled=" + o.enrolledDevices() + " admitted=" + o.admitted() + " refused=" + o.refused());
        } catch (RuntimeException e) {
            LOG.warning("[NIGHTLY_INVENTORY] failed: " + e.getMessage());
        }
    }
}
