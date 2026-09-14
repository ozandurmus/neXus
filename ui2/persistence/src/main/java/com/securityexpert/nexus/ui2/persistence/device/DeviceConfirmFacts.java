package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Objects;
import java.util.Optional;

/**
 * The confirm job's own write payload (EC-J3, V12's columns): the observed
 * facts, the recorded identity baseline this confirm establishes, any
 * identity-mismatch marker (EC-6a), and the peer-follow outcome (PF-1..
 * PF-5) -- everything {@link DeviceRepository#recordConfirmSuccess} writes
 * to {@code devices} in one atomic, fenced update alongside the {@code
 * DRAFT -> ENROLLED} transition (B1_04B contract §3). Plain optionals of
 * strings only -- this type carries no {@code worker.confirm} type (DIR-7/
 * DIR-2: {@code persistence} never depends on {@code worker}), so a caller
 * translates its own richer parsed shapes into this one at the boundary.
 */
public record DeviceConfirmFacts(
        Optional<String> observedHostname,
        Optional<String> observedModel,
        Optional<String> observedSoftwareVersion,
        Optional<String> observedHaRole,
        Optional<String> recordedIdentityPrimary,
        Optional<String> recordedIdentitySecondary,
        String identityMismatchState,
        Optional<String> identityMismatchPresentedPrimary,
        Optional<String> identityMismatchPresentedSecondary,
        Optional<String> clusterMemberRef,
        Optional<String> virtualSystemRef,
        String peerFollowOutcome,
        Optional<String> peerFollowReason) {

    public static final String IDENTITY_MISMATCH_NONE = "NONE";
    public static final String IDENTITY_MISMATCH_OPEN = "OPEN";
    public static final String PEER_FOLLOW_NONE = "NONE";
    public static final String PEER_FOLLOW_CORROBORATED = "CORROBORATED";
    public static final String PEER_FOLLOW_NOT_CONFIRMED = "NOT_CONFIRMED";

    public DeviceConfirmFacts {
        Objects.requireNonNull(observedHostname, "observedHostname");
        Objects.requireNonNull(observedModel, "observedModel");
        Objects.requireNonNull(observedSoftwareVersion, "observedSoftwareVersion");
        Objects.requireNonNull(observedHaRole, "observedHaRole");
        Objects.requireNonNull(recordedIdentityPrimary, "recordedIdentityPrimary");
        Objects.requireNonNull(recordedIdentitySecondary, "recordedIdentitySecondary");
        Objects.requireNonNull(identityMismatchState, "identityMismatchState");
        Objects.requireNonNull(identityMismatchPresentedPrimary, "identityMismatchPresentedPrimary");
        Objects.requireNonNull(identityMismatchPresentedSecondary, "identityMismatchPresentedSecondary");
        Objects.requireNonNull(clusterMemberRef, "clusterMemberRef");
        Objects.requireNonNull(virtualSystemRef, "virtualSystemRef");
        Objects.requireNonNull(peerFollowOutcome, "peerFollowOutcome");
        Objects.requireNonNull(peerFollowReason, "peerFollowReason");
    }
}
