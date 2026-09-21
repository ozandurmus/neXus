package com.securityexpert.nexus.ui2.worker.discovery.pan;

import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanTrustRuleResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.TrustResolution;
import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointPanCertTrustRepository;

/**
 * A local, environment-variable-backed {@link PanTrustRuleResolver}. Trust
 * rules (TLS CA bundles/pins) are out of the credential store's scope
 * (2026-09-14 PO decision record: "a trust store is a later movement") --
 * this class keeps working exactly as it did when it was the trust half of
 * {@code EnvironmentPanCredentialAndTrustResolvers}; only the credential
 * half of that class is replaced, by {@link StoreBackedPanCredentialResolver}.
 */
public final class EnvironmentPanTrustRuleResolver implements PanTrustRuleResolver {

    public static final EnvironmentPanTrustRuleResolver INSTANCE = new EnvironmentPanTrustRuleResolver();

    private final ManagementEndpointPanCertTrustRepository repository;
    private final boolean allowDeviceTrust;

    private EnvironmentPanTrustRuleResolver() {
        this(null, false);
    }

    public EnvironmentPanTrustRuleResolver(ManagementEndpointPanCertTrustRepository repository,
            boolean allowDeviceTrust) {
        this.repository = repository;
        this.allowDeviceTrust = allowDeviceTrust;
    }

    /** TLS trust law: a CA bundle path takes precedence; a pinned fingerprint is the fallback; otherwise unresolved (fail-closed). */
    @Override
    public TrustResolution resolveTrust(String trustRuleRef) {
        String caBundlePath = System.getenv("PAN_DISCOVERY_TRUST_CA_BUNDLE_PATH");
        if (caBundlePath != null && !caBundlePath.isBlank()) {
            return new TrustResolution.CaBundlePath(caBundlePath);
        }
        if (repository != null || allowDeviceTrust) {
            return new TrustResolution.PaloAltoDeviceTrust(java.util.Optional.empty());
        }
        String pinnedFingerprint = System.getenv("PAN_DISCOVERY_TRUST_PINNED_FINGERPRINT_SHA256");
        if (pinnedFingerprint != null && !pinnedFingerprint.isBlank()) {
            return new TrustResolution.PinnedFingerprint(pinnedFingerprint.replace(":", "").trim().toLowerCase());
        }
        return new TrustResolution.Unresolved();
    }

    @Override
    public TrustResolution resolveTrust(String trustRuleRef, String managementAddress, int managementPort) {
        String caBundlePath = System.getenv("PAN_DISCOVERY_TRUST_CA_BUNDLE_PATH");
        if (caBundlePath != null && !caBundlePath.isBlank()) {
            return new TrustResolution.CaBundlePath(caBundlePath);
        }
        if (repository != null || allowDeviceTrust) {
            return new TrustResolution.PaloAltoDeviceTrust(() -> {
                if (repository != null) {
                    try {
                        var stored = repository.findActiveFingerprint(managementAddress, managementPort);
                        if (stored.isPresent()) {
                            return stored;
                        }
                    } catch (RuntimeException ignored) {
                        // The deploy-time pin remains the compatibility fallback when storage is unavailable.
                    }
                }
                String scopedName = "UI2_" + trustRuleRef.toUpperCase(java.util.Locale.ROOT).replace('.', '_')
                        + "_FINGERPRINT";
                String scoped = System.getenv(scopedName);
                if (scoped != null && !scoped.isBlank()) {
                    return java.util.Optional.of(normalize(scoped));
                }
                String fallback = System.getenv("PAN_DISCOVERY_TRUST_PINNED_FINGERPRINT_SHA256");
                return fallback == null || fallback.isBlank()
                        ? java.util.Optional.empty() : java.util.Optional.of(normalize(fallback));
            });
        }
        return resolveTrust(trustRuleRef);
    }

    private static String normalize(String fingerprint) {
        return fingerprint.replace(":", "").trim().toLowerCase(java.util.Locale.ROOT);
    }
}
