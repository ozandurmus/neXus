package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Optional;

/**
 * The confirm's device-contact outcome (contract §4). {@code
 * CredentialUnresolvable} is EC-3's pre-contact refusal -- {@link
 * ConfirmCapabilityExecutor} never attempts {@code connect} once it has
 * this variant. Every other outcome means a connection was attempted, so
 * downstream state (recorded identity, facts, mismatch) may need writing
 * even when the read itself failed to parse.
 */
public sealed interface ConfirmResult {

    /**
     * @param selfReferenceForPeer the token a corroborating peer's own HA
     *     read would need to name back for PF-2 (Check Point: observed
     *     hostname; Palo Alto: serial) -- distinct from {@code
     *     presentedIdentity}'s connection-layer fingerprint/certificate,
     *     because a vendor's peer-naming read never cites that value
     *     (PF-4: form {@code UNKNOWN} until measured; this is the
     *     documented, bound-at-one-site reading).
     */
    record Completed(PresentedIdentity presentedIdentity, ObservedFacts facts, HaPeerClaim haPeerClaim,
            Optional<String> selfReferenceForPeer) implements ConfirmResult {
    }

    /** EC-3: refuses before any contact -- {@code connect} is never called. */
    record CredentialUnresolvable(String reason) implements ConfirmResult {
    }

    /** Connect itself failed (host-key rejection, timeout, authentication) -- never a mismatch, never a fact. */
    record ConnectFailed(String reason) implements ConfirmResult {
    }
}
