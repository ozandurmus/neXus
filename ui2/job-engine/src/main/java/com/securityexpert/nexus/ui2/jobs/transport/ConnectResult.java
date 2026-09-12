package com.securityexpert.nexus.ui2.jobs.transport;

/**
 * A {@code connect} outcome. A host-key mismatch is always {@link
 * #hostKeyRejected}, never {@link #timedOut} or a generic failure, and it
 * is always a <b>definite</b> failure (contract §5: "never OUTCOME_UNKNOWN,
 * since no mutation boundary was approached") -- this type has no
 * "ambiguous" variant by construction, matching that rule at the type
 * level rather than only in prose.
 */
public sealed interface ConnectResult {

    record Authenticated(TransportSession session) implements ConnectResult {
    }

    record AuthenticationFailed(String reason) implements ConnectResult {
    }

    record HostKeyRejected(String reason) implements ConnectResult {
    }

    record TimedOut() implements ConnectResult {
    }

    default boolean isSuccess() {
        return this instanceof Authenticated;
    }
}
