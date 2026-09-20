package com.securityexpert.nexus.ui2.jobs.failover.execution;

/**
 * SPI for vendor-specific failover execution and observation.
 * In accordance with repository laws:
 * 1. Takes closed action kind and opaque endpoint reference (no generic command string).
 * 2. Owns vendor-specific command literals.
 * 3. Provides independent two-sided post-verification.
 */
public interface FailoverDeviceExecutor {

    /**
     * Executes the at-most-once mutation action against the designated target member.
     */
    FailoverCommandResult executeAction(String targetMemberId, FailoverActionKind actionKind);

    /**
     * Independently reads both cluster members directly from the devices.
     */
    TwoSidedObservation observePostcondition(String clusterId, String memberAId, String memberBId);

    /**
     * Returns the vendor identifier supported by this executor (e.g. CHECK_POINT, PALO_ALTO).
     */
    String supportedVendor();
}
