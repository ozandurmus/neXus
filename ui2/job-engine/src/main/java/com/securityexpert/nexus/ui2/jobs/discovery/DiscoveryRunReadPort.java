package com.securityexpert.nexus.ui2.jobs.discovery;

import java.util.Optional;

/**
 * The read port {@link com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService#submitForRun}
 * consults (14F DR-1: "{@code JobAdmissionService} admits the discovery
 * capability against a run row -- a new target kind alongside devices").
 * Mirrors {@link com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort}'s
 * shape for the device path.
 */
public interface DiscoveryRunReadPort {

    Optional<DiscoveryRunSnapshot> findRun(String runId);
}
