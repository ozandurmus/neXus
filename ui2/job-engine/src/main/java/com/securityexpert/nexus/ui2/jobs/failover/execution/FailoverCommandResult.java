package com.securityexpert.nexus.ui2.jobs.failover.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of submitting an at-most-once command across the mutation boundary.
 */
public record FailoverCommandResult(
    boolean successful,
    int exitCode,
    String commandSummary,
    Instant executedAt,
    String errorReason
) {
    public FailoverCommandResult {
        Objects.requireNonNull(commandSummary, "commandSummary must not be null");
        Objects.requireNonNull(executedAt, "executedAt must not be null");
    }

    public static FailoverCommandResult success(String commandSummary) {
        return new FailoverCommandResult(true, 0, commandSummary, Instant.now(), null);
    }

    public static FailoverCommandResult failure(int exitCode, String commandSummary, String errorReason) {
        return new FailoverCommandResult(false, exitCode, commandSummary, Instant.now(), errorReason);
    }
}
