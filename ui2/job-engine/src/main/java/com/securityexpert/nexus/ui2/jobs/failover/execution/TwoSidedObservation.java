package com.securityexpert.nexus.ui2.jobs.failover.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * Two-sided independent observation of both cluster members.
 * Enforces repository Evidence Law: a member's report about its peer is never sufficient;
 * both members must be observed independently.
 */
public record TwoSidedObservation(
    MemberObservation memberAObservation,
    MemberObservation memberBObservation,
    Instant completedAt
) {
    public TwoSidedObservation {
        Objects.requireNonNull(memberAObservation, "memberAObservation must not be null");
        Objects.requireNonNull(memberBObservation, "memberBObservation must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    public static TwoSidedObservation of(MemberObservation a, MemberObservation b) {
        return new TwoSidedObservation(a, b, Instant.now());
    }

    public boolean bothObservationsSuccessful() {
        return memberAObservation.observationSuccessful() && memberBObservation.observationSuccessful();
    }
}
