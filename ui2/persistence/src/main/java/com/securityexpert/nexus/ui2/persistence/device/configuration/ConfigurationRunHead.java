package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One primary configuration run without its text, index or overrides: what the Backups screen's deviation read
 * needs (docs/design/BACKUP_MAJOR_DEVIATIONS_2026_10_23.md). The run's {@code changeState} and, for a changed run,
 * its deviation summary already describe the comparison with the newest earlier run of the same read kind.
 */
public record ConfigurationRunHead(String runId, String deviceId, String vendor, String readKind, Instant collectedAt,
        String changeState, Optional<ConfigurationDeviationSummary> deviationSummary) {

    public ConfigurationRunHead {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(readKind, "readKind");
        Objects.requireNonNull(collectedAt, "collectedAt");
        Objects.requireNonNull(changeState, "changeState");
        Objects.requireNonNull(deviationSummary, "deviationSummary");
    }
}
