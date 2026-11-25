package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.logdetect.JooqLogDetectionRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository;
import com.securityexpert.nexus.ui2.service.privacy.HmacKeyProvider;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService;

/**
 * Wires the log detection engine, phase P1 (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 8.1 option (a)): the
 * log-analysis job runs in ui2-service, bounded per pass, and does nothing while {@code log_detection_settings.enabled}
 * is false (the default). The read side is always wired so a switched-off engine answers with its state.
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
    public LogDetectionService logDetectionService(LogDetectionRepository repository, SystemLogReadService reader,
            HardwareMarkers markers) {
        return new LogDetectionService(repository, reader, markers);
    }

    @Bean
    public LogFindingReadService logFindingReadService(LogDetectionRepository repository) {
        return new LogFindingReadService(repository, Clock.systemUTC());
    }

    @Bean
    public LogDetectionRunner logDetectionRunner(LogDetectionService service,
            @Value("${ui2.scheduling.enabled:false}") boolean schedulingEnabled) {
        return new LogDetectionRunner(service, schedulingEnabled);
    }

    /** One bounded pass every five minutes when scheduling is on; the pass itself checks the P1 switch. */
    public static final class LogDetectionRunner {
        private final LogDetectionService service;
        private final boolean schedulingEnabled;

        LogDetectionRunner(LogDetectionService service, boolean schedulingEnabled) {
            this.service = service;
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
        }
    }
}
