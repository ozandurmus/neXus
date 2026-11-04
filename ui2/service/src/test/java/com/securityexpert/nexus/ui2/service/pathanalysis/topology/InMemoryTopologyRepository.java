package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Test double of the V186 repository; records what a rebuild or an override write persisted. */
public final class InMemoryTopologyRepository implements TopologyRepository {

    public final List<SnapshotHeader> snapshots = new ArrayList<>();
    public final List<TopologyGraph.Conflict> conflicts = new ArrayList<>();
    public final List<TopologyLinkOverride> overrides = new ArrayList<>();
    public final List<String> auditedActions = new ArrayList<>();

    @Override
    public synchronized int recordSnapshot(SnapshotHeader header, List<TopologyGraph.Conflict> found, int limit, int keep) {
        int stored = Math.min(limit, found.size());
        snapshots.add(0, header); // newest first, as "order by built_at desc"
        conflicts.addAll(found.subList(0, stored));
        while (snapshots.size() > keep) {
            snapshots.remove(snapshots.size() - 1);
        }
        return stored;
    }

    @Override
    public synchronized Optional<SnapshotHeader> latestSnapshot() {
        return snapshots.stream().findFirst();
    }

    @Override
    public synchronized List<TopologyLinkOverride> listOverrides() {
        return List.copyOf(overrides);
    }

    @Override
    public synchronized Optional<TopologyLinkOverride> findOverride(String overrideId) {
        return overrides.stream().filter(o -> o.overrideId().equals(overrideId)).findFirst();
    }

    @Override
    public synchronized void insertOverride(TopologyLinkOverride override, String actorFingerprint, String actionId) {
        auditedActions.add(actionId);
        overrides.add(override);
    }

    @Override
    public synchronized boolean revokeOverride(String overrideId, String actorFingerprint, String actionId, Instant at) {
        for (int i = 0; i < overrides.size(); i++) {
            TopologyLinkOverride o = overrides.get(i);
            if (o.overrideId().equals(overrideId) && o.active()) {
                auditedActions.add(actionId);
                overrides.set(i, new TopologyLinkOverride(o.overrideId(), o.action(), o.linkKind(), o.aDeviceId(),
                        o.aContext(), o.aInterfaceName(), o.bDeviceId(), o.bContext(), o.bInterfaceName(),
                        o.assumedSubnet(), o.reasonCode(), o.note(), o.createdBy(), o.createdAt(), Optional.of(at),
                        Optional.of(actorFingerprint), o.orphanedAt()));
                return true;
            }
        }
        return false;
    }
}
