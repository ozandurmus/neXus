package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.logdetect.JooqLogDetectionRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.JooqLogTemplateRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository;
import com.securityexpert.nexus.ui2.service.privacy.HmacKeyProvider;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService;

/**
 * Wires the log detection engine, phases P1 and P2 (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 8.1 option (a)): the
 * log-analysis job runs in ui2-service, bounded per pass, and does nothing while {@code log_detection_settings.enabled}
 * is false (the default). Phase P2 (layer B) is wired beside it and does nothing unless {@code p2_enabled} is also true
 * (default false) and a catalog version is FROZEN. The read side is always wired so a switched-off engine answers with
 * its state.
 */
@Configuration(proxyBeanMethods = false)
public class LogDetectionConfiguration {
    /** Chunks one pass may index (design 8.1: bounded concurrency and a per-window time budget are mandatory). */
    static final int PASS_MAX_CHUNKS = 40;
    static final Duration PASS_BUDGET = Duration.ofMinutes(2);

    @Bean
    public LogDetectionRepository logDetectionRepository(TransactionBoundary transactions) {
        return new JooqLogDetectionRepository(transactions);
    }

    @Bean
    public HardwareMarkers hardwareMarkers(HmacKeyProvider keys) {
        return new HardwareMarkers(new LogKeyToken(keys.getSecretKey()));
    }

    @Bean
    public LogTemplateRepository logTemplateRepository(TransactionBoundary transactions) {
        return new JooqLogTemplateRepository(transactions);
    }

    @Bean
    public LogTemplateNormaliser logTemplateNormaliser() {
        return new LogTemplateNormaliser();
    }

    @Bean
    public LogDetectionP2Service logDetectionP2Service(LogDetectionRepository findings, LogTemplateRepository templates,
            SystemLogReadService reader, HardwareMarkers markers, LogTemplateNormaliser normaliser, HmacKeyProvider keys) {
        return new LogDetectionP2Service(findings, templates, reader, markers, normaliser, new LogTemplateIds(keys.getSecretKey()),
                new LogKeyToken(keys.getSecretKey()));
    }

    @Bean
    public LogCatalogService logCatalogService(LogTemplateRepository templates, SystemLogReadService reader,
            HardwareMarkers markers, LogTemplateNormaliser normaliser, HmacKeyProvider keys) {
        return new LogCatalogService(templates, reader, markers, normaliser, new LogTemplateIds(keys.getSecretKey()),
                new LogKeyToken(keys.getSecretKey()));
    }

    /** The catalog admin; the offline build runs on one daemon thread, one build at a time. */
    @Bean
    public LogCatalogAdmin logCatalogAdmin(LogCatalogService catalogs, LogTemplateRepository templates) {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "log-catalog-build");
            t.setDaemon(true);
            return t;
        });
        return new LogCatalogAdmin(catalogs, templates, executor, Clock.systemUTC());
    }

    @Bean
    public LogDetectionService logDetectionService(LogDetectionRepository repository, SystemLogReadService reader,
            HardwareMarkers markers) {
        return new LogDetectionService(repository, reader, markers);
    }

    @Bean
    public LogFindingReadService logFindingReadService(LogDetectionRepository repository) {
        return new LogFindingReadService(repository, Clock.systemUTC());
    }

    @Bean
    public LogDetectionRunner logDetectionRunner(LogDetectionService service, LogDetectionP2Service p2,
            @Value("${ui2.scheduling.enabled:false}") boolean schedulingEnabled) {
        return new LogDetectionRunner(service, p2, schedulingEnabled);
    }

    /**
     * One bounded pass every five minutes when scheduling is on: P1, then P2. Each pass checks its own switch; the P2
     * pass runs only when both switches are on, so with P2 off (the default) this tick is exactly the P1 tick.
     */
    public static final class LogDetectionRunner {
        private final LogDetectionService service;
        private final LogDetectionP2Service p2;
        private final boolean schedulingEnabled;

        LogDetectionRunner(LogDetectionService service, LogDetectionP2Service p2, boolean schedulingEnabled) {
            this.service = service;
            this.p2 = p2;
            this.schedulingEnabled = schedulingEnabled;
        }

        @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT5M")
        public void tick() {
            if (!schedulingEnabled) return;
            try {
                service.runOnce(PASS_MAX_CHUNKS, PASS_BUDGET);
            } catch (RuntimeException e) {
                System.getLogger(LogDetectionRunner.class.getName()).log(System.Logger.Level.WARNING,
                        "log detection pass failed cause={0}", e.getClass().getSimpleName());
            }
            try {
                p2.runOnce(PASS_MAX_CHUNKS, PASS_BUDGET);
            } catch (RuntimeException e) {
                System.getLogger(LogDetectionRunner.class.getName()).log(System.Logger.Level.WARNING,
                        "log detection P2 pass failed cause={0}", e.getClass().getSimpleName());
            }
        }
    }
}
