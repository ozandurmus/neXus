package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.util.Objects;

import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;

/**
 * Holds the reverse map of the held topology snapshot only (D-3 condition 2): built on the first aiview query against a
 * snapshot, replaced when the snapshot changes, kept in memory only. One build at a time; a concurrent query for the same
 * snapshot waits for that build instead of starting another.
 */
public final class PathPseudonymResolver {

    private final PathPseudonymMap.Masking masking;
    private volatile PathPseudonymMap current;

    public PathPseudonymResolver(PathPseudonymMap.Masking masking) {
        this.masking = Objects.requireNonNull(masking, "masking");
    }

    public PathPseudonymMap mapFor(TopologyGraph graph) {
        Objects.requireNonNull(graph, "graph");
        PathPseudonymMap held = current;
        if (held != null && held.isFor(graph)) {
            return held;
        }
        synchronized (this) {
            held = current;
            if (held == null || !held.isFor(graph)) {
                held = PathPseudonymMap.build(graph, masking);
                current = held;
            }
            return held;
        }
    }
}
