package com.securityexpert.nexus.ui2.jobs.failover.plan;

import java.util.Objects;

/**
 * An individual planned action step within a failover execution plan.
 */
public record FailoverActionStep(
    int stepNumber,
    String targetMember,
    String command,
    String description,
    String riskLevel
) {
    public FailoverActionStep {
        Objects.requireNonNull(targetMember, "targetMember must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    }
}
