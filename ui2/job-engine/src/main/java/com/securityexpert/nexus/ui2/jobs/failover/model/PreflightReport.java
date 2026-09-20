package com.securityexpert.nexus.ui2.jobs.failover.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable consolidated report of all evaluated pre-flight checks for a cluster.
 */
public record PreflightReport(
    String clusterId,
    String maskedClusterName,
    String vendor,
    String haMode,
    Instant generatedAt,
    PreflightVerdict overallVerdict,
    List<CheckResult> checks,
    int totalChecks,
    int passCount,
    int failCount,
    int warningCount,
    int blockingFailureCount
) {
    public PreflightReport {
        Objects.requireNonNull(clusterId, "clusterId must not be null");
        Objects.requireNonNull(maskedClusterName, "maskedClusterName must not be null");
        Objects.requireNonNull(vendor, "vendor must not be null");
        Objects.requireNonNull(haMode, "haMode must not be null");
        Objects.requireNonNull(overallVerdict, "overallVerdict must not be null");
        if (checks == null) {
            checks = List.of();
        }
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
    }

    public static PreflightReport fromResults(
        String clusterId,
        String maskedClusterName,
        String vendor,
        String haMode,
        List<CheckResult> results
    ) {
        int pass = 0;
        int fail = 0;
        int warn = 0;
        int blockingFails = 0;

        for (CheckResult r : results) {
            if (r.status() == CheckStatus.PASS) {
                pass++;
            } else if (r.status() == CheckStatus.WARNING) {
                warn++;
            } else {
                fail++;
            }
            if (r.isBlockingFailure()) {
                blockingFails++;
            }
        }

        PreflightVerdict verdict = (blockingFails == 0)
            ? PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED
            : PreflightVerdict.BLOCKING_CONDITIONS_PRESENT;

        return new PreflightReport(
            clusterId,
            maskedClusterName,
            vendor,
            haMode,
            Instant.now(),
            verdict,
            results,
            results.size(),
            pass,
            fail,
            warn,
            blockingFails
        );
    }
}
