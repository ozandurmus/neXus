package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.util.Optional;

import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialResolver;
import com.securityexpert.nexus.ui2.worker.transport.ssh.TrustRuleResolver;

/**
 * A local, environment-variable-backed stand-in for the credential/trust
 * store {@link SshCredentialResolver} and {@link TrustRuleResolver}
 * themselves declare out of scope ("the real, secret-backed implementation
 * is out of this movement's scope"). Exists only so {@link
 * DiscoveryRunnerMain} is runnable by the Product Owner today; both
 * {@code credentialRef} and {@code trustRuleRef} are accepted and ignored
 * here because no real reference-backed store exists yet to resolve them
 * against -- a later movement that builds that store replaces this class,
 * not the port it implements.
 */
final class EnvironmentCredentialAndTrustResolvers implements SshCredentialResolver, TrustRuleResolver {

    static final EnvironmentCredentialAndTrustResolvers INSTANCE = new EnvironmentCredentialAndTrustResolvers();

    private EnvironmentCredentialAndTrustResolvers() {
    }

    @Override
    public SshCredentialMaterial resolve(String credentialRef) {
        String username = System.getenv("CP_DISCOVERY_SSH_USERNAME");
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("CP_DISCOVERY_SSH_USERNAME is not set");
        }
        String password = System.getenv("CP_DISCOVERY_SSH_PASSWORD");
        return new SshCredentialMaterial(username, password == null ? null : password.toCharArray(), null);
    }

    @Override
    public Optional<String> resolveExpectedFingerprint(String trustRuleRef) {
        return Optional.ofNullable(System.getenv("CP_DISCOVERY_TRUST_FINGERPRINT"));
    }
}
