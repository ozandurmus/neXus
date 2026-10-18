package com.securityexpert.nexus.ui2.service.device.diagnostic;

import java.time.Instant;
import java.util.logging.Logger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger of the system-log collection (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 3.1): once a minute it
 * admits the devices that have reached their {@code hash(device_id) mod 40} offset inside the current 00/06/12/18
 * window, through {@link SystemLogCollectionService#admitDue}.
 *
 * <p><b>Off in the code, on in the deployment.</b> The bean exists only when
 * {@code ui2.system-log.scheduled-collection.enabled=true} ({@code UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_ENABLED}); the
 * code default is {@code false}. PO decision 2026-10-18 (after the pilot and the small test, section 15 item 7):
 * {@code deploy/ui2/50-service-deployment.yaml} sets it to {@code true}, because this bean lives in the service process
 * only. Manual {@code syslog-collect-start} works either way. The shared {@code ui2.scheduling.enabled} switch applies
 * on top of it (a preview candidate runs with scheduling off).</p>
 */
@Component
@ConditionalOnProperty(name = "ui2.system-log.scheduled-collection.enabled", havingValue = "true", matchIfMissing = false)
public class SystemLogCollectionScheduler {
    private static final Logger LOG = Logger.getLogger(SystemLogCollectionScheduler.class.getName());
    public static final String ACTOR = "system:system-log-collection";

    private final SystemLogCollectionService service;

    public SystemLogCollectionScheduler(SystemLogCollectionService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 * * * * *", zone = com.securityexpert.nexus.ui2.platform.JobWindowPolicy.ZONE)
    public void tick() {
        try {
            var outcome = service.admitDue(Instant.now(), ACTOR);
            if (outcome.admitted() > 0 || outcome.refused() > 0)
                LOG.info("[SYSTEM_LOG_COLLECT] due=" + outcome.targets() + " admitted=" + outcome.admitted()
                        + " deduplicated=" + outcome.deduplicated() + " refused=" + outcome.refused());
        } catch (RuntimeException e) {
            LOG.warning("[SYSTEM_LOG_COLLECT] tick failed: " + e.getClass().getSimpleName());
        }
    }
}
