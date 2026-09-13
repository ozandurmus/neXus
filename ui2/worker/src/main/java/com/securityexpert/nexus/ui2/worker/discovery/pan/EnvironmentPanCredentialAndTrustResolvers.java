package com.securityexpert.nexus.ui2.worker.discovery.pan;

import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanTrustRuleResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.TrustResolution;

/**
 * A local, environment-variable-backed stand-in for the credential/trust
 * store {@link PanCredentialResolver} and {@link PanTrustRuleResolver}
 * themselves declare out of scope -- exists only so {@link
 * PanDiscoveryRunnerMain} is runnable by the Product Owner today, exactly
 * as cp's {@code EnvironmentCredentialAndTrustResolvers} exists for the
 * ssh_exec runner. Both {@code credentialRef} and {@code trustRuleRef} are
 * accepted and ignored here because no real reference-backed store exists
 * yet to resolve them against -- a later movement that builds that store
 * replaces this class, not the ports it implements.
 */
final class EnvironmentPanCredentialAndTrustResolvers implements PanCredentialResolver, PanTrustRuleResolver {

    static final EnvironmentPanCredentialAndTrustResolvers INSTANCE = new EnvironmentPanCredentialAndTrustResolvers();

    private EnvironmentPanCredentialAndTrustResolvers() {
    }

    @Override
    public PanCredentialMaterial resolve(String credentialRef) {
        String username = System.getenv("PAN_DISCOVERY_USERNAME");
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("PAN_DISCOVERY_USERNAME is not set");
        }
        String password = System.getenv("PAN_DISCOVERY_PASSWORD");
        return new PanCredentialMaterial(username, password == null ? new char[0] : password.toCharArray());
    }

    /** TLS trust law: a CA bundle path takes precedence; a pinned fingerprint is the fallback; otherwise unresolved (fail-closed). */
    @Override
    public TrustResolution resolveTrust(String trustRuleRef) {
        String caBundlePath = System.getenv("PAN_DISCOVERY_TRUST_CA_BUNDLE_PATH");
        if (caBundlePath != null && !caBundlePath.isBlank()) {
            return new TrustResolution.CaBundlePath(caBundlePath);
        }
        String pinnedFingerprint = System.getenv("PAN_DISCOVERY_TRUST_PINNED_FINGERPRINT_SHA256");
        if (pinnedFingerprint != null && !pinnedFingerprint.isBlank()) {
            return new TrustResolution.PinnedFingerprint(pinnedFingerprint);
        }
        return new TrustResolution.Unresolved();
    }
}
