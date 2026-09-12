package com.securityexpert.nexus.ui2.discovery.cp;

import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * Contract §5.3 member-to-cluster resolution outcomes (MC-1/MC-2).
 */
public sealed interface ClusterLink {

    /** The reference carried a stable identifier; that identifier is the link. */
    record Linked(OpaqueId clusterStableIdentifier) implements ClusterLink {
    }

    /**
     * MC-2: the reference was absent, or carried a display name but no
     * identifier. There is no fallback — never a display name, a shared
     * name prefix, or anything else.
     */
    record NotEvaluable() implements ClusterLink {
    }
}
