package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Contract §5 / AC-10 / §8 test 11's non-container-provable half: host-key
 * trust is a pure, always-definite decision (never accept-any, never
 * trust-on-first-use). The container-hosted-endpoint half (a real TCP
 * handshake presenting an untrusted key) is the disabled placeholder
 * {@code SshExecTrustRuleRejectsUntrustedHostKeyTest} in {@code
 * integration-tests}.
 */
class HostKeyVerifierTest {

    @Test
    void aMatchingFingerprintIsTrusted() {
        HostKeyVerifier verifier = new HostKeyVerifier(trustRuleRef -> Optional.of("aa:bb:cc"));
        assertTrue(verifier.isTrusted("trust-1", "aa:bb:cc"));
    }

    @Test
    void aMismatchedFingerprintIsRejectedNeverAmbiguous() {
        HostKeyVerifier verifier = new HostKeyVerifier(trustRuleRef -> Optional.of("aa:bb:cc"));
        assertFalse(verifier.isTrusted("trust-1", "unexpected-fingerprint"));
    }

    @Test
    void anUnregisteredTrustRuleIsRejectedNeverAcceptAny() {
        // AGENTS.md "Check Point" note: "the adapter never defaults to
        // accept-any or trust-on-first-use." An unregistered trust rule
        // must fail closed, not be treated as "no rule, so allow."
        HostKeyVerifier verifier = new HostKeyVerifier(trustRuleRef -> Optional.empty());
        assertFalse(verifier.isTrusted("unregistered-trust-rule", "any-fingerprint-at-all"));
    }

    @Test
    void aCaseOrPartialMatchIsNeverTrusted() {
        HostKeyVerifier verifier = new HostKeyVerifier(trustRuleRef -> Optional.of("AA:BB:CC"));
        assertFalse(verifier.isTrusted("trust-1", "aa:bb:cc"), "fingerprint comparison must be exact, never "
                + "case-insensitive or partial");
    }
}
