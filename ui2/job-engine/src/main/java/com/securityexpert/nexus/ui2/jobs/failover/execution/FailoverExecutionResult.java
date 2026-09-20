package com.securityexpert.nexus.ui2.jobs.failover.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable audit record capturing the outcome of a controlled failover execution.
 */
public record FailoverExecutionResult(
    String executionId,
    String clusterRef,
    String maskedClusterName,
    String vendor,
    FailoverActionKind actionKind,
    FailoverExecutionState state,
    Instant requestedAt,
    Instant boundaryCrossedAt,
    Instant completedAt,
    String targetMemberId,
    String targetMemberMaskedName,
    String requesterId,
    String approverId,
    TwoSidedObservation preObservation,
    FailoverCommandResult commandResult,
    TwoSidedObservation postObservation,
    boolean quarantineActive,
    String summary
) {
    public FailoverExecutionResult {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(maskedClusterName, "maskedClusterName must not be null");
        Objects.requireNonNull(vendor, "vendor must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
    }
}
