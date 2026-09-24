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

    /**
     * 23:30: one retry for the devices whose 23:00 run failed. Measured 2026-09-24: 13 of 40 Palo Alto firewalls
     * failed "key generation did not return a usable key" when the whole fleet was asked at once; the load is gone
     * half an hour later. Only this scheduler's own failures from tonight are retried, once.
     */
    @Scheduled(cron = "0 30 23 * * *", zone = "Europe/Istanbul")
    public void retryFailed() {
        try {
            int n = 0;
            for (String id : failedTonight.get()) {
                if (collectService.requestCollect(id, ACTOR, java.util.Optional.empty()) instanceof InventoryCollectService.Outcome.Admitted) {
                    n++;
                }
            }
            LOG.info("[NIGHTLY_INVENTORY] retry admitted=" + n);
        } catch (RuntimeException e) {
            LOG.warning("[NIGHTLY_INVENTORY] retry failed: " + e.getMessage());
        }
    }

    private java.util.function.Supplier<java.util.List<String>> failedTonight = java.util.List::of;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionBoundary(com.securityexpert.nexus.ui2.persistence.TransactionBoundary tx) {
        failedTonight = () -> tx.inTransaction(dsl -> dsl.fetch(
                "select distinct target_device_id::text as id from jobs where submitted_by_actor_fingerprint = {0} "
                        + "and state = 'FAILED' and submitted_at > now() - interval '2 hours'", ACTOR))
                .stream().map(r -> r.get("id", String.class)).toList();
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
