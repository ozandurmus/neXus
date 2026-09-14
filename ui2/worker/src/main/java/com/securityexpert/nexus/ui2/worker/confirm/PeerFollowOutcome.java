package com.securityexpert.nexus.ui2.worker.confirm;

/**
 * PF-1..PF-5's result. {@code Corroborated} is the only variant that forms
 * a unit ({@code cluster_member_ref}) -- PF-2: "a member's report about
 * its peer is one-sided until the peer independently confirms it in the
 * same pass." Every other variant is PF-3's "peer named, not confirmed."
 */
public sealed interface PeerFollowOutcome {

    record None() implements PeerFollowOutcome {
    }

    /** @param unitId the opaque {@code cluster_member_ref} both rows now share. */
    record Corroborated(String unitId, ConfirmResult.Completed peerResult) implements PeerFollowOutcome {
    }

    record NotConfirmed(Reason reason) implements PeerFollowOutcome {
    }

    enum Reason {
        /** PF-3: the HA/peer read named a peer but carried no management address (PF-4: Check Point's common case). */
        ADDRESS_MISSING,
        /** PF-3: the peer could not be reached with the same credential. */
        PEER_UNREACHABLE,
        /** PF-2: the peer's own read did not name the first device back. */
        ONE_SIDED_CLAIM
    }

    static PeerFollowOutcome none() {
        return new None();
    }

    static PeerFollowOutcome notConfirmed(Reason reason) {
        return new NotConfirmed(reason);
    }

    static PeerFollowOutcome corroborated(String unitId, ConfirmResult.Completed peerResult) {
        return new Corroborated(unitId, peerResult);
    }
}
