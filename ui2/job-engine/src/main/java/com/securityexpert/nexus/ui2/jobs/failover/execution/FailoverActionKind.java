package com.securityexpert.nexus.ui2.jobs.failover.execution;

/**
 * Closed enumeration of authorized failover operational action kinds.
 * In accordance with repository architecture rules, generic command execution is strictly forbidden.
 */
public enum FailoverActionKind {
    /**
     * Gracefully initiates controlled failover by lowering priority or suspending the currently active member.
     * Primitives: Check Point `clusterXL_admin down` (non-persistent), Palo Alto `request high-availability state suspend`.
     */
    CONTROLLED_FAILOVER,

    /**
     * Returns the previously failed-over member to service / functional standby.
     * Primitives: Check Point `clusterXL_admin up`, Palo Alto `request high-availability state functional`.
     */
    RETURN_TO_SERVICE
}
