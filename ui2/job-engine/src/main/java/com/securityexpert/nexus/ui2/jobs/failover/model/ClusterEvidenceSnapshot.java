package com.securityexpert.nexus.ui2.jobs.failover.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Corroborated evidence snapshot representing independent observations
 * from both peers of an HA cluster collected in the same inspection window.
 * {@code snapshotId} is an opaque per-collection-pass correlation token (never a
 * vendor identity) used to prove that two reads refer to the exact same evidence
 * collection pass rather than merely the same cluster.
 */
public record ClusterEvidenceSnapshot(
    String snapshotId,
    String clusterId,
    String maskedClusterName,
    String vendor,
    String haMode,
    String virtualSystemId,
    ClusterMemberEvidence memberA,
    ClusterMemberEvidence memberB,
    Instant snapshotTimestamp
) {
    public ClusterEvidenceSnapshot {
        Objects.requireNonNull(clusterId, "clusterId must not be null");
        Objects.requireNonNull(maskedClusterName, "maskedClusterName must not be null");
        Objects.requireNonNull(vendor, "vendor must not be null");
        Objects.requireNonNull(haMode, "haMode must not be null");
        if (snapshotId == null || snapshotId.isBlank()) {
            snapshotId = UUID.randomUUID().toString();
        }
        if (snapshotTimestamp == null) {
            snapshotTimestamp = Instant.now();
        }
    }

    /**
     * Backward-compatible constructor for call sites that do not need to correlate
     * this snapshot against a separately captured preflight report; a fresh, unique
     * snapshotId is generated so such snapshots never spuriously satisfy a single-
     * snapshot identity check.
     */
    public ClusterEvidenceSnapshot(
        String clusterId,
        String maskedClusterName,
        String vendor,
        String haMode,
        String virtualSystemId,
        ClusterMemberEvidence memberA,
        ClusterMemberEvidence memberB,
        Instant snapshotTimestamp
    ) {
        this(UUID.randomUUID().toString(), clusterId, maskedClusterName, vendor, haMode,
            virtualSystemId, memberA, memberB, snapshotTimestamp);
    }

    public Optional<ClusterMemberEvidence> activeMember() {
        if (memberA != null && "ACTIVE".equalsIgnoreCase(memberA.selfState())) {
            return Optional.of(memberA);
        }
        if (memberB != null && "ACTIVE".equalsIgnoreCase(memberB.selfState())) {
            return Optional.of(memberB);
        }
        return Optional.empty();
    }

    public Optional<ClusterMemberEvidence> standbyMember() {
        if (memberA != null && ("STANDBY".equalsIgnoreCase(memberA.selfState()) || "PASSIVE".equalsIgnoreCase(memberA.selfState()))) {
            return Optional.of(memberA);
        }
        if (memberB != null && ("STANDBY".equalsIgnoreCase(memberB.selfState()) || "PASSIVE".equalsIgnoreCase(memberB.selfState()))) {
            return Optional.of(memberB);
        }
        return Optional.empty();
    }

    public boolean bothMembersDirectlyObserved() {
        return memberA != null && memberA.directObservationSuccessful()
            && memberB != null && memberB.directObservationSuccessful();
    }
}
