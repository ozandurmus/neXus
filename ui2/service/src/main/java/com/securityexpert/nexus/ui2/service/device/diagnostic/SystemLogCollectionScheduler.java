package com.securityexpert.nexus.ui2.service.device.diagnostic;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
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
 * on top of it unless {@code ui2.system-log.scheduled-collection.independent=true}
 * ({@code UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_INDEPENDENT}, code default {@code false}, PO 2026-11-05), in which case
 * this bean ticks every minute on its own single-thread {@link ScheduledExecutorService} while still requiring
 * {@code ui2.system-log.scheduled-collection.enabled=true}.</p>
 */
@Component
@ConditionalOnProperty(name = "ui2.system-log.scheduled-collection.enabled", havingValue = "true", matchIfMissing = false)
public class SystemLogCollectionScheduler implements SmartLifecycle, DisposableBean {
    private static final Logger LOG = Logger.getLogger(SystemLogCollectionScheduler.class.getName());
    public static final String ACTOR = "system:system-log-collection";

    private final SystemLogCollectionService service;
    private final boolean enabled;
    private final boolean independent;
    private ScheduledExecutorService executor;

    public SystemLogCollectionScheduler(SystemLogCollectionService service) {
        this(service, true, false);
    }

    @Autowired
    public SystemLogCollectionScheduler(
            SystemLogCollectionService service,
            @Value("${ui2.system-log.scheduled-collection.enabled:false}") boolean enabled,
            @Value("${ui2.system-log.scheduled-collection.independent:false}") boolean independent) {
        this.service = service;
        this.enabled = enabled;
        this.independent = independent;
    }

    @Override
    public synchronized void start() {
        if (!enabled || !independent || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "system-log-collection-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::tick, 0L, 1L, TimeUnit.MINUTES);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    @Scheduled(cron = "0 * * * * *", zone = com.securityexpert.nexus.ui2.platform.JobWindowPolicy.ZONE)
    public void tick() {
        try {
            var outcome = service.admitDue(Instant.now(), ACTOR);
            if (outcome != null && (outcome.admitted() > 0 || outcome.refused() > 0))
                LOG.info("[SYSTEM_LOG_COLLECT] due=" + outcome.targets() + " admitted=" + outcome.admitted()
                        + " deduplicated=" + outcome.deduplicated() + " refused=" + outcome.refused());
        } catch (RuntimeException e) {
            LOG.warning("[SYSTEM_LOG_COLLECT] tick failed: " + e.getClass().getSimpleName());
        }
    }
}
