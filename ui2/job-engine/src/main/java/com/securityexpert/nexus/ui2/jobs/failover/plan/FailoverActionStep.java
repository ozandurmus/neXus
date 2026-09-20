package com.securityexpert.nexus.ui2.jobs.failover.plan;

import java.util.Objects;

/**
 * An individual planned action step within a failover execution plan.
 * In accordance with repository Identity Law (F-9), targetMemberId is an opaque stored endpoint
 * identity, while targetMemberMaskedName is strictly for display/presentation rendering.
 */
public record FailoverActionStep(
    int stepNumber,
    String targetMemberId,
    String targetMemberMaskedName,
    String actionKind,
    String command,
    String description,
    String riskLevel
) {
    public FailoverActionStep {
        Objects.requireNonNull(targetMemberId, "targetMemberId must not be null");
        Objects.requireNonNull(targetMemberMaskedName, "targetMemberMaskedName must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    }

    /**
     * Backward-compatibility accessor matching the legacy targetMember concept.
     */
    public String targetMember() {
        return targetMemberMaskedName;
    }
}
