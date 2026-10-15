package com.securityexpert.nexus.ui2.service.device.configuration;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import java.util.logging.Logger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads every enrolled device's configuration once a day, in the 00:00 Europe/Istanbul job window
 * (PO 2026-10-15: device collection belongs in the shared windows). Until now configuration was only read
 * on demand, so the Configuration and Compliance planes aged silently (seen live: 18 days). Once a day
 * keeps the 06/12/18 windows light; the jobs are the same read-only jobs the "Read configuration, all"
 * action admits.
 */
@Component
public class ConfigurationRefreshScheduler {

    private static final Logger LOG = Logger.getLogger(ConfigurationRefreshScheduler.class.getName());
    public static final String ACTOR = "system:daily-configuration";
    /** Seconds, minutes, hours: 00:00:00 every day, evaluated in {@link JobWindowPolicy#ZONE}. */
    public static final String CRON = "0 0 0 * * *";

    private final ConfigurationCollectService collectService;

    public ConfigurationRefreshScheduler(ConfigurationCollectService collectService) {
        this.collectService = collectService;
    }

    @Scheduled(cron = CRON, zone = JobWindowPolicy.ZONE)
    public void daily() {
        try {
            ConfigurationCollectService.BulkOutcome o = collectService.requestCollectAll(ACTOR);
            LOG.info("[DAILY_CONFIGURATION] enrolled=" + o.enrolledDevices() + " admitted=" + o.admitted() + " refused=" + o.refused());
        } catch (RuntimeException e) {
            LOG.warning("[DAILY_CONFIGURATION] failed: " + e.getMessage());
        }
    }
}
