package com.securityexpert.nexus.ui2.worker.discovery.pan;

import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanTrustRuleResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.TrustResolution;

/** Palo Alto certificates require validity, but no chain or per-device authorization. */
public final class EnvironmentPanTrustRuleResolver implements PanTrustRuleResolver {

    public static final EnvironmentPanTrustRuleResolver INSTANCE = new EnvironmentPanTrustRuleResolver();

    private EnvironmentPanTrustRuleResolver() {
    }

    @Override
    public TrustResolution resolveTrust(String trustRuleRef) {
        return new TrustResolution.AcceptAnyValidCertificate();
    }
}
