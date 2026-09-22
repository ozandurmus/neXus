package com.securityexpert.nexus.ui2.service.compliance;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fills the compliance evaluation cache once the service is up, in the background, so the first person to
 * open Compliance or the Overview after a restart does not wait for every device to be evaluated
 * (measured 2026-09-22: ~5 s on the first open, ~0.25 s after).
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
}
