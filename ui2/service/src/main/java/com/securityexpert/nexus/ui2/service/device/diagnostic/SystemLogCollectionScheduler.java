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
 * <p><b>Off by default.</b> The bean exists only when {@code ui2.system-log.scheduled-collection.enabled=true}
 * ({@code UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_ENABLED}). Fleet enablement waits for the one-device pilot (section 15
 * item 7); until then collection runs only from the manual {@code syslog-collect-start}. The shared
 * {@code ui2.scheduling.enabled} switch applies on top of it.</p>
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
