package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.Optional;

/**
 * Contract §5.3. MC-1: the join key is the cluster reference's stable
 * identifier alone — the display name carried alongside it is never read
 * by this class. MC-3: this resolver only ever looks at the member side;
 * it has no notion of a cluster's member list.
 */
public final class ClusterLinkResolver {

    private ClusterLinkResolver() {
    }

    /** MC-1/MC-2. */
    public static ClusterLink resolve(Optional<ClusterReference> clusterReference) {
        return clusterReference
                .flatMap(ClusterReference::identifier)
                .<ClusterLink>map(ClusterLink.Linked::new)
                .orElseGet(ClusterLink.NotEvaluable::new);
    }
}
