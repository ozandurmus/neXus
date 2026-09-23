package com.securityexpert.nexus.ui2.service.compliance;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fills the compliance evaluation cache once the service is up, in the background, so the first person to
 * open Compliance or the Overview after a restart does not wait for every device to be evaluated
 * (measured 2026-09-22: ~5 s on the first open, ~0.25 s after), and keeps it filled every five minutes: only
 * devices whose configuration (or the compliance rule set) changed are re-evaluated, so a screen open never waits.
 */
@Component
public class ComplianceWarmup {

    private static final System.Logger LOG = System.getLogger(ComplianceWarmup.class.getName());

    private final ComplianceService complianceService;

    public ComplianceWarmup(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        Thread thread = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                complianceService.getControls();
                LOG.log(System.Logger.Level.INFO, "[COMPLIANCE_WARMUP] evaluation cache filled in {0} ms", System.currentTimeMillis() - start);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "[COMPLIANCE_WARMUP] skipped: {0}", e.getMessage());
            }
        }, "compliance-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void keepWarm() {
        long start = System.currentTimeMillis();
        try {
            complianceService.getControls();
            long ms = System.currentTimeMillis() - start;
            if (ms > 1_000) {
                LOG.log(System.Logger.Level.INFO, "[COMPLIANCE_WARMUP] refreshed in {0} ms", ms);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[COMPLIANCE_WARMUP] refresh skipped: {0}", e.getMessage());
        }
    }
}
