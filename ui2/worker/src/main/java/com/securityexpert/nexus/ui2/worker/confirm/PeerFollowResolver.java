package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Objects;

import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * PF-1..PF-5: one additional device contact, at most, triggered only when
 * the first device's own HA/peer read names a peer and carries its
 * address, and a unit forms only when that peer's own read names the
 * first device back (PF-2). This class never calls {@link
 * ConfirmCapabilityExecutor#confirm} more than once per {@link #resolve}
 * invocation -- PF-5's "one additional device contact... never a scan" is
 * enforced by there being no loop here at all, not by a counter.
 */
public final class PeerFollowResolver {

    private final ConfirmCapabilityExecutor executor;

    public PeerFollowResolver(ConfirmCapabilityExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * @param firstDevice the first device's own completed confirm.
     * @param peerRequestFactory builds the one request PF-1 sends to the named
     *     peer's own management address, with the same credential (PF-1, PF-3
     *     "never... another credential"); never invoked when the address is
     *     absent, so no caller of this method ever has to guard against being
     *     asked to dial an empty target.
     */
    public PeerFollowOutcome resolve(ConfirmResult.Completed firstDevice, ConfirmRequestFactory peerRequestFactory) {
        HaPeerClaim claim = firstDevice.haPeerClaim();
        if (!claim.isMember()) {
            return PeerFollowOutcome.none();
        }
        if (claim.peerManagementAddress().isEmpty()) {
            return PeerFollowOutcome.notConfirmed(PeerFollowOutcome.Reason.ADDRESS_MISSING);
        }

        ConfirmRequest peerRequest = peerRequestFactory.forManagementAddress(claim.peerManagementAddress().get());
        ConfirmResult peerResult = executor.confirm(peerRequest);
        if (!(peerResult instanceof ConfirmResult.Completed peerCompleted)) {
            return PeerFollowOutcome.notConfirmed(PeerFollowOutcome.Reason.PEER_UNREACHABLE);
        }

        boolean peerNamesFirstDeviceBack = peerCompleted.haPeerClaim().peerSelfIdentifier()
                .flatMap(peerNamedIdentifier -> firstDevice.selfReferenceForPeer()
                        .map(peerNamedIdentifier::equals))
                .orElse(false);
        if (!peerNamesFirstDeviceBack) {
            return PeerFollowOutcome.notConfirmed(PeerFollowOutcome.Reason.ONE_SIDED_CLAIM);
        }
        String self = firstDevice.selfReferenceForPeer().orElse("");
        String peer = peerCompleted.selfReferenceForPeer().orElse("");
        String unitId = (!self.isBlank() && !peer.isBlank())
                ? (self.compareTo(peer) <= 0 ? self + "|" + peer : peer + "|" + self)
                : OpaqueId.random().value();
        return PeerFollowOutcome.corroborated(unitId, peerCompleted);
    }

    /** Builds the one PF-1 request against a peer's own reported management address. */
    @FunctionalInterface
    public interface ConfirmRequestFactory {
        ConfirmRequest forManagementAddress(String managementAddress);
    }
}
