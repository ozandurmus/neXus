package com.securityexpert.nexus.ui2.jobs.failover.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * Direct, identity-verified observation of a single cluster member.
 */
public record MemberObservation(
    String memberId,
    String observedRole,
    boolean reachable,
    boolean criticalDevicesHealthy,
    Instant observedAt,
    boolean observationSuccessful,
    String observationDetail
) {
    public MemberObservation {
        Objects.requireNonNull(memberId, "memberId must not be null");
        Objects.requireNonNull(observedRole, "observedRole must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    public static MemberObservation of(String memberId, String role, boolean reachable, boolean criticalOk, String detail) {
        return new MemberObservation(memberId, role, reachable, criticalOk, Instant.now(), reachable && criticalOk, detail);
    }

    public static MemberObservation failed(String memberId, String failureDetail) {
        return new MemberObservation(memberId, "UNKNOWN", false, false, Instant.now(), false, failureDetail);
    }
}
