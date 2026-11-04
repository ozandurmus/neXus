package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * V186 persistence (D-1): the snapshot header, its conflicts and the overrides. Never a node, segment or attachment.
 */
public interface TopologyRepository {

    /** The persisted header of one build. */
    record SnapshotHeader(String snapshotId, Instant builtAt, String builtBy, TopologyGraph.Fingerprints fingerprints,
            TopologyGraph.Counts counts, int conflictsStored, long buildMillis) {
    }

    /**
     * Writes the header and up to {@code conflictLimit} conflicts in one transaction, then prunes snapshots older than
     * the newest {@code keep} (their conflicts go with them).
     *
     * @return the number of conflicts stored
     */
    int recordSnapshot(SnapshotHeader header, List<TopologyGraph.Conflict> conflicts, int conflictLimit, int keep);

    Optional<SnapshotHeader> latestSnapshot();

    /** Every override, revoked and orphaned ones included (an unmatched override is reported, never dropped). */
    List<TopologyLinkOverride> listOverrides();

    Optional<TopologyLinkOverride> findOverride(String overrideId);

    /** Inserts an override, audited under {@code actorFingerprint} / {@code actionId}. */
    void insertOverride(TopologyLinkOverride override, String actorFingerprint, String actionId);

    /** Marks an override revoked; false when it does not exist or is already revoked. Never deletes. */
    boolean revokeOverride(String overrideId, String actorFingerprint, String actionId, Instant at);
}
