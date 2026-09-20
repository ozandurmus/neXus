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
    String errorReason,
    DeliveryCertainty certainty
) {
    /**
     * Typed certainty of whether a mutating command was actually delivered to and
     * acted on by the device. Distinguishes a device-confirmed rejection (safe to
     * treat as no-op) from a transport-ambiguous outcome (must fail closed).
     */
    public enum DeliveryCertainty {
        DEFINITELY_NOT_SUBMITTED,
        DEFINITELY_REJECTED,
        SUBMITTED_SUCCESS,
        DELIVERY_UNKNOWN
    }

    public FailoverCommandResult {
        Objects.requireNonNull(commandSummary, "commandSummary must not be null");
        Objects.requireNonNull(executedAt, "executedAt must not be null");
        Objects.requireNonNull(certainty, "certainty must not be null");

        // CF-P0.16: success and delivery certainty are two different claims, and only
        // one combination of them is coherent in each direction. A successful result
        // carrying DELIVERY_UNKNOWN would assert that a command both worked and may
        // never have arrived, and downstream that combination slipped past the
        // fail-closed path; an unsuccessful result carrying SUBMITTED_SUCCESS would be
        // read as an affirmative device rejection when nothing affirmed it. Neither is
        // representable: a caller that cannot say which one it means must say
        // DELIVERY_UNKNOWN on an unsuccessful result and let the boundary fail closed.
        if (successful && certainty != DeliveryCertainty.SUBMITTED_SUCCESS) {
            throw new IllegalArgumentException(
                "a successful command result must carry SUBMITTED_SUCCESS, not " + certainty);
        }
        if (!successful && certainty == DeliveryCertainty.SUBMITTED_SUCCESS) {
            throw new IllegalArgumentException(
                "an unsuccessful command result must not carry SUBMITTED_SUCCESS");
        }
    }

    public static FailoverCommandResult success(String commandSummary) {
        return new FailoverCommandResult(true, 0, commandSummary, Instant.now(), null, DeliveryCertainty.SUBMITTED_SUCCESS);
    }

    public static FailoverCommandResult failure(int exitCode, String commandSummary, String errorReason, DeliveryCertainty certainty) {
        Objects.requireNonNull(certainty, "certainty must not be null");
        return new FailoverCommandResult(false, exitCode, commandSummary, Instant.now(), errorReason, certainty);
    }
}
