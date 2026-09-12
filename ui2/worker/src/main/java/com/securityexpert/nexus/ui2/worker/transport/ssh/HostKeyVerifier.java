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

    private final TrustRuleResolver trustRuleResolver;

    public HostKeyVerifier(TrustRuleResolver trustRuleResolver) {
        this.trustRuleResolver = trustRuleResolver;
    }

    /**
     * @return {@code true} only when a trust rule is registered for {@code
     *         trustRuleRef} AND the presented fingerprint matches it
     *         exactly. Never {@code true} for an unregistered rule, and
     *         never a partial/case-insensitive match.
     */
    public boolean isTrusted(String trustRuleRef, String presentedFingerprint) {
        Optional<String> expected = trustRuleResolver.resolveExpectedFingerprint(trustRuleRef);
        return expected.isPresent() && expected.get().equals(presentedFingerprint);
    }
}
