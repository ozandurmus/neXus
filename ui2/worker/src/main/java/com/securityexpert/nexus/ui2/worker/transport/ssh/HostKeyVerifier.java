package com.securityexpert.nexus.ui2.worker.transport.ssh;

import java.util.Optional;

/**
 * Pure host-key trust decision (contract §5, AC-10, test 11). Deliberately
 * independent of JSch/any real socket -- the property this class embodies
 * ("never accept-any, never trust-on-first-use, always a definite
 * yes/no") is provable without a network connection, which is exactly
 * what {@code SshExecTrustRuleRejectsUntrustedHostKeyTest}'s non-container
 * half proves; the container-hosted-endpoint half (an actual TCP
 * handshake against an untrusted key) is the disabled placeholder.
 */
public final class HostKeyVerifier {

    public enum Decision { MATCH, MISMATCH, MISSING }

    private final TrustRuleResolver trustRuleResolver;

    public HostKeyVerifier(TrustRuleResolver trustRuleResolver) {
        this.trustRuleResolver = java.util.Objects.requireNonNull(trustRuleResolver, "trustRuleResolver");
    }

    /**
     * @return {@code true} only when a trust rule is registered for {@code
     *         trustRuleRef} AND the presented fingerprint matches it
     *         exactly. Never {@code true} for an unregistered rule, and
     *         never a partial/case-insensitive match.
     */
    public boolean isTrusted(String ref, String host, int port, String algorithm, String presentedFingerprint) {
        return verify(ref, host, port, algorithm, presentedFingerprint) == Decision.MATCH;
    }

    public Decision verify(String ref, String host, int port, String algorithm, String presentedFingerprint) {
        if (ref == null || ref.isBlank() || presentedFingerprint == null || presentedFingerprint.isBlank()) {
            return Decision.MISSING;
        }
        return trustRuleResolver.resolveExpectedFingerprint(ref, host, port, algorithm)
                .filter(expected -> !expected.isBlank())
                .map(expected -> expected.equals(presentedFingerprint) ? Decision.MATCH : Decision.MISMATCH)
                .orElse(Decision.MISSING);
    }

    public boolean isTrusted(String trustRuleRef, String presentedFingerprint) {
        if (trustRuleRef == null || trustRuleRef.isBlank() || presentedFingerprint == null || presentedFingerprint.isBlank()) {
            return false;
        }
        Optional<String> expected = trustRuleResolver.resolveExpectedFingerprint(trustRuleRef);
        return expected.isPresent() && !expected.get().isBlank() && expected.get().equals(presentedFingerprint);
    }
}
