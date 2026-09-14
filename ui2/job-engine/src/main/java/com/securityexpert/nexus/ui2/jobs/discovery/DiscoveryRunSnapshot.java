package com.securityexpert.nexus.ui2.jobs.discovery;

/**
 * The read-only view of one discovery run that admission consults. Carries
 * no sensitive field (no management address, no credential reference) --
 * only what an admission decision needs (mirrors {@code DeviceEnrollmentSnapshot}).
 */
public record DiscoveryRunSnapshot(String runId, String state) {

    /** 14F DR-1: admission requires the run to still be {@code REQUESTED} -- a run already claimed, running, finished or failed is not a fresh admission target. */
    public boolean admissible() {
        return "REQUESTED".equals(state);
    }
}
