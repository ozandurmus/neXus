package com.securityexpert.nexus.ui2.jobs.failover.execution;

/**
 * Deterministic lifecycle states for Phase C failover execution.
 */
public enum FailoverExecutionState {
    /** Execution requested; validating 4-eyes lease and pilot fence */
    PENDING_VALIDATION,

    /** Re-verifying preconditions directly against both cluster members (JIT check) */
    PRECONDITION_VERIFYING,

    /** Precondition failed or either member observation failed; aborted fail-closed prior to mutation boundary */
    ABORTED_PRE_MUTATION,

    /** Lease consumed; command submitted across the mutation boundary (at-most-once) */
    MUTATION_COMMITTED,

    /** Independent dual-member post-verification in progress */
    POST_VERIFICATION,

    /** Transition successfully verified across both independent direct-device observations */
    SUCCEEDED,

    /** Both members observed, zero state transition occurred; holds cluster from immediate retry (F-10) */
    FAILED_NO_CHANGE,

    /** Vendor returned definite rejection / error envelope; zero state transition occurred (F-11) */
    VENDOR_REJECTED,

    /** Ambiguous outcome, dropped connection, or timeout; sticky quarantine engaged */
    OUTCOME_UNKNOWN
}
