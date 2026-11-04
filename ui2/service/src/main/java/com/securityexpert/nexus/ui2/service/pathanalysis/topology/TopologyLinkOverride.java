package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One {@code topology_link_override} row (V186; design §3, A§1.2): a human-declared link, never discovered. It names
 * its two ends by device, context and interface name -- stable across inventory runs, unlike per-run interface ids.
 * {@code note} is free text hidden from aiview (D-8); it never leaves the service for a replay viewer.
 *
 * @param aDeviceId empty once the device was deleted (the row is then orphan-marked, {@code orphanedAt} set)
 */
public record TopologyLinkOverride(String overrideId, String action, String linkKind, Optional<String> aDeviceId,
        String aContext, String aInterfaceName, Optional<String> bDeviceId, String bContext, String bInterfaceName,
        Optional<String> assumedSubnet, String reasonCode, Optional<String> note, String createdBy, Instant createdAt,
        Optional<Instant> revokedAt, Optional<String> revokedBy, Optional<Instant> orphanedAt) {

    /** Join the two ends (an uncollected L2 / WAN / tunnel, or a duplicate private prefix known to be one network). */
    public static final String ACTION_FORCE_LINK = "FORCE_LINK";
    /** Keep the two ends apart although they share a prefix (an isolated lab or a separate routing domain). */
    public static final String ACTION_SUPPRESS_LINK = "SUPPRESS_LINK";
    public static final Set<String> ACTIONS = Set.of(ACTION_FORCE_LINK, ACTION_SUPPRESS_LINK);

    public static final Set<String> LINK_KINDS = Set.of("STATIC_L3", "IPSEC_TUNNEL", "L2_TRUNK", "MPLS_WAN");

    public static final Set<String> REASON_CODES = Set.of("UNMANAGED_INTERMEDIATE_DEVICE", "TUNNEL",
            "DUPLICATE_PRIVATE_PREFIX_SAME_NETWORK", "DUPLICATE_PRIVATE_PREFIX_DIFFERENT_NETWORK",
            "SEPARATE_ROUTING_DOMAIN", "VERIFIED_CABLING", "OTHER");

    public static final int NOTE_MAX = 500;

    public TopologyLinkOverride {
        Objects.requireNonNull(overrideId, "overrideId");
        if (!ACTIONS.contains(action) || !LINK_KINDS.contains(linkKind) || !REASON_CODES.contains(reasonCode)) {
            throw new IllegalArgumentException("closed vocabulary");
        }
        aDeviceId = aDeviceId == null ? Optional.empty() : aDeviceId;
        bDeviceId = bDeviceId == null ? Optional.empty() : bDeviceId;
        Objects.requireNonNull(aContext, "aContext");
        Objects.requireNonNull(aInterfaceName, "aInterfaceName");
        Objects.requireNonNull(bContext, "bContext");
        Objects.requireNonNull(bInterfaceName, "bInterfaceName");
        assumedSubnet = assumedSubnet == null ? Optional.empty() : assumedSubnet;
        note = note == null ? Optional.empty() : note;
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        revokedAt = revokedAt == null ? Optional.empty() : revokedAt;
        revokedBy = revokedBy == null ? Optional.empty() : revokedBy;
        orphanedAt = orphanedAt == null ? Optional.empty() : orphanedAt;
    }

    public boolean active() {
        return revokedAt.isEmpty();
    }

    public boolean orphaned() {
        return orphanedAt.isPresent() || aDeviceId.isEmpty() || bDeviceId.isEmpty();
    }
}
