package com.securityexpert.nexus.ui2.worker.discovery.pan;

import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanTrustRuleResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.TrustResolution;

/**
 * A local, environment-variable-backed {@link PanTrustRuleResolver}. Trust
 * rules (TLS CA bundles/pins) are out of the credential store's scope
 * (2026-09-14 PO decision record: "a trust store is a later movement") --
 * this class keeps working exactly as it did when it was the trust half of
 * {@code EnvironmentPanCredentialAndTrustResolvers}; only the credential
 * half of that class is replaced, by {@link StoreBackedPanCredentialResolver}.
 */
final class EnvironmentPanTrustRuleResolver implements PanTrustRuleResolver {

    static final EnvironmentPanTrustRuleResolver INSTANCE = new EnvironmentPanTrustRuleResolver();

    private EnvironmentPanTrustRuleResolver() {
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
            return new TrustResolution.PinnedFingerprint(pinnedFingerprint.replace(":", "").trim().toLowerCase());
        }
        return new TrustResolution.Unresolved();
    }
}
