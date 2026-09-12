package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * Contract §5.3/CR-7: a member's reference to its cluster object, carrying
 * a stable identifier alongside a display name. MC-1: the identifier is
 * the join key; the display name is never compared and never used to
 * break a tie — it is carried here only so it can be shown (NP-4), never
 * read by {@link ClusterLinkResolver}.
 */
public record ClusterReference(Optional<OpaqueId> identifier, Optional<String> displayName) {
}
