package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.util.Optional;

import com.securityexpert.nexus.ui2.worker.transport.ssh.TrustRuleResolver;

/**
 * A local, environment-variable-backed {@link TrustRuleResolver}. Trust
 * rules (SSH host-key fingerprints) are out of the credential store's scope
 * (2026-09-14 PO decision record: "a trust store is a later movement") --
 * this class keeps working exactly as it did when it was the trust half of
 * {@code EnvironmentCredentialAndTrustResolvers}; only the credential half
 * of that class is replaced, by {@link StoreBackedSshCredentialResolver}.
 */
final class EnvironmentTrustRuleResolver implements TrustRuleResolver {

    static final EnvironmentTrustRuleResolver INSTANCE = new EnvironmentTrustRuleResolver();

    private EnvironmentTrustRuleResolver() {
    }

    @Override
    public Optional<String> resolveExpectedFingerprint(String trustRuleRef) {
        return Optional.ofNullable(System.getenv("CP_DISCOVERY_TRUST_FINGERPRINT"));
    }
}
