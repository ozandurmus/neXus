package com.securityexpert.nexus.ui2.jobs.failover.schedule;

/**
 * Closed, transition-enforced lifecycle state machine for scheduled failover executions.
 * Enforces strict pre-mutation abort states vs post-boundary terminals.
 */
public enum FailoverScheduleStatus {
    // 1. Pre-execution states
    SCHEDULED(false, false),
    CLAIMED_VERIFYING(false, false),
    DISPATCHING(true, false),

    // 2. Post-mutation terminal states
    COMPLETED(true, true),
    FAILED_NO_CHANGE(true, true),
    VENDOR_REJECTED(true, true),
    OUTCOME_UNKNOWN(true, true),

    // 3. Pre-mutation abort terminals (zero mutating commands sent)
    CANCELLED(false, true),
    ABORTED_TAMPERED(false, true),
    ABORTED_REVOKED(false, true),
    ABORTED_WINDOW_EXPIRED(false, true),
    ABORTED_CLOCK_UNTRUSTED(false, true),
    ABORTED_AUTHORIZATION_LAPSED(false, true),
    ABORTED_COOLDOWN_ACTIVE(false, true),
    ABORTED_PRE_MUTATION(false, true),
    ABORTED_DRIFT_DETECTED(false, true),
    // CF-P0.5: a signing key that cannot be resolved (durable key store
    // unavailable) is a distinct, non-tamper terminal -- it must never be
    // reported as ABORTED_TAMPERED, which asserts that verification ran
    // and failed.
    ABORTED_KEY_UNAVAILABLE(false, true);

    private final boolean mutationBoundaryCrossed;
    private final boolean terminal;

    FailoverScheduleStatus(boolean mutationBoundaryCrossed, boolean terminal) {
        this.mutationBoundaryCrossed = mutationBoundaryCrossed;
        this.terminal = terminal;
    }

    public boolean isMutationBoundaryCrossed() {
        return mutationBoundaryCrossed;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean isCancellable() {
        return this == SCHEDULED;
    }

    public boolean isRunnable() {
        return this == SCHEDULED;
    }
}
