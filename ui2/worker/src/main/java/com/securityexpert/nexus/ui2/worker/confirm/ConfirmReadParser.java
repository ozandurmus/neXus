package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Optional;

/**
 * Turns the two gate-entry reads' raw output into the confirm's domain
 * shapes. Command forms are {@code UNVERIFIED} (gate document status
 * line); so is every parsing rule below -- each implementation is the one
 * site {@code DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md} says a
 * correction lands in, without reopening the gate approval. Never throws
 * on unrecognized output: a non-match is {@code Optional.empty()}
 * (UNKNOWN), per EC-11 gate entry item 8.
 */
public interface ConfirmReadParser {

    /** The primary/secondary recorded-identity pair (EC-5), from the identity read's own output. */
    PresentedIdentity presentedIdentity(String identityReadOutput, Optional<String> connectPresentedIdentity);

    /** DA-2's facts: hostname, model, software version, HA role -- from the identity read's own output. */
    ObservedFacts observedFacts(String identityReadOutput);

    /** PF-1's HA/cluster role and peer naming, from the HA/peer read's own output. */
    HaPeerClaim haPeerClaim(String haPeerReadOutput);

    /** DA-2's HA-role fact (part of {@link ObservedFacts}), from the same HA/peer read. */
    Optional<String> haRole(String haPeerReadOutput);

    /** The token a corroborating peer's own read must name back (PF-2), derived from this device's own reads. */
    Optional<String> selfReferenceForPeer(String identityReadOutput);
}
