package com.securityexpert.nexus.ui2.service.failover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CF-P0.14: every four-eyes gate compared requester/approver (or operator/second-approver)
 * principals with {@code equalsIgnoreCase}, which a trailing space defeats -- {@code "alice"} and
 * {@code "alice "} were treated as distinct principals, letting the same human satisfy both sides
 * of a dual-control gate.
 */
class PrincipalCanonicalizationTest {

    @Test
    @DisplayName("A trailing space no longer defeats the dual-control self-approval check")
    void trailingSpaceCannotDefeatDualControl() {
        assertThrows(IllegalArgumentException.class, () ->
            PrincipalCanonicalization.requireDistinctPrincipals("alice", "alice ", "Scheduling"));
    }

    @Test
    @DisplayName("A case difference no longer defeats the dual-control self-approval check")
    void caseDifferenceCannotDefeatDualControl() {
        assertThrows(IllegalArgumentException.class, () ->
            PrincipalCanonicalization.requireDistinctPrincipals("Alice", "ALICE", "Scheduling"));
    }

    @Test
    @DisplayName("Genuinely distinct principals are accepted")
    void distinctPrincipalsAccepted() {
        assertDoesNotThrow(() ->
            PrincipalCanonicalization.requireDistinctPrincipals("alice", "bob", "Scheduling"));
    }

    @Test
    @DisplayName("A control character (including a tab) in a principal is rejected")
    void controlCharacterRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            PrincipalCanonicalization.requireDistinctPrincipals("alice\tsmith", "bob", "Scheduling"));
    }

    @Test
    @DisplayName("A leading space is tolerated by canonicalization (only trailing whitespace is rejected outright)")
    void leadingSpaceIsCanonicalizedNotRejected() {
        assertEquals("alice", PrincipalCanonicalization.canonicalize(" alice", "requesterId"));
    }
}
