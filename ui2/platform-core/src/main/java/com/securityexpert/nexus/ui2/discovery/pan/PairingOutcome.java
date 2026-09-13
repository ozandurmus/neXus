package com.securityexpert.nexus.ui2.discovery.pan;

/**
 * Contract §7 HA-1 to HA-5: the high-availability pairing outcome for one
 * candidate. {@link #Paired} forms only under HA-1's reciprocity rule;
 * every other case is {@link #NotEvaluable} with a closed {@link Reason} —
 * never a guess, never a silent drop (HA-5).
 */
public sealed interface PairingOutcome {

    /** HA-1: this candidate's peer-serial names another candidate, and that candidate names it back. */
    record Paired(String peerStableIdentifier) implements PairingOutcome {
    }

    /** HA-2/HA-3/HA-4, and the base case of carrying no peer-serial claim at all. */
    record NotEvaluable(Reason reason) implements PairingOutcome {
    }

    enum Reason {
        /** No peer-serial claim is carried at all. */
        NO_PEER_SERIAL,
        /** HA-2: the peer-serial claim does not match the serial of any candidate in the same response. */
        PEER_NOT_FOUND,
        /** HA-3: the peer-serial claim names the candidate itself. */
        SELF_REFERENTIAL,
        /** HA-4: the claim is one-sided — the named peer's own peer-serial does not name the claimant back. */
        ONE_SIDED_CLAIM
    }
}
