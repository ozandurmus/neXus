package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Optional;

/**
 * PF-1's HA/cluster role and peer-naming read, parsed. {@code
 * peerSelfIdentifier} is the token the peer would need to present as its
 * own {@link PresentedIdentity#primary()} for PF-2's corroboration to
 * succeed; {@code peerManagementAddress} is present only when the read
 * itself carries one (PF-4: Palo Alto's HA state does, Check Point's
 * {@code cphaprob} output may not) -- its absence is exactly PF-3's "peer
 * named, not confirmed" trigger, never a reason to guess an address.
 */
public record HaPeerClaim(boolean isMember, Optional<String> peerSelfIdentifier, Optional<String> peerManagementAddress) {

    public HaPeerClaim {
        java.util.Objects.requireNonNull(peerSelfIdentifier, "peerSelfIdentifier");
        java.util.Objects.requireNonNull(peerManagementAddress, "peerManagementAddress");
    }

    public static HaPeerClaim standalone() {
        return new HaPeerClaim(false, Optional.empty(), Optional.empty());
    }
}
