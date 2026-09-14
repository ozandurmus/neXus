package com.securityexpert.nexus.ui2.persistence.discovery;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A read view of one {@code discovery_run} row (14F DR-1). The run row is
 * the {@code C2} job's target -- not a device (DR-4: "the management server
 * itself is not a device row"). {@link #toString()} omits {@link
 * #managementAddress()}: it is {@code CLASS 2} and must never appear in a
 * log line (14F PR-1 by cross-reference to the import contract's own rule).
 */
public record DiscoveryRun(
        String runId,
        String vendor,
        String managementAddress,
        String credentialReferenceId,
        String requestedByActorFingerprint,
        DiscoveryRunState state,
        Optional<String> jobId,
        Optional<Instant> startedAt,
        Optional<Instant> finishedAt,
        Optional<Map<String, Integer>> outcomeSummary) {

    @Override
    public String toString() {
        return "DiscoveryRun[runId=" + runId + ", vendor=" + vendor + ", managementAddress=<redacted>, "
                + "state=" + state + ", jobId=" + jobId + "]";
    }
}
