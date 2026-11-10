package com.securityexpert.nexus.ui2.service.device.configuration;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import java.util.logging.Logger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.securityexpert.nexus.ui2.service.schedule.FamilySwitch;
import com.securityexpert.nexus.ui2.service.schedule.ScheduledJobFamily;

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
    private final FamilySwitch switches;

    public ConfigurationRefreshScheduler(ConfigurationCollectService collectService, FamilySwitch switches) {
        this.collectService = collectService;
        this.switches = switches;
    }

    @Scheduled(cron = CRON, zone = JobWindowPolicy.ZONE)
    public void daily() {
        if (!switches.allows(ScheduledJobFamily.CONFIGURATION_REFRESH, "DAILY_CONFIGURATION")) return;
        try {
            ConfigurationCollectService.BulkOutcome o = collectService.requestCollectAll(ACTOR);
            LOG.info("[DAILY_CONFIGURATION] enrolled=" + o.enrolledDevices() + " admitted=" + o.admitted() + " refused=" + o.refused());
        } catch (RuntimeException e) {
            LOG.warning("[DAILY_CONFIGURATION] failed: " + e.getMessage());
        }
    }
}
