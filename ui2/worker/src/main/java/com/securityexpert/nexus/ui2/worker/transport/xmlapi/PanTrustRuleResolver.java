package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

/**
 * Resolves a capability's {@code trustRuleRef} to a {@link TrustResolution}
 * (TLS trust law). The real, secret/PKI-backed implementation is out of
 * this movement's scope, exactly as {@code TrustRuleResolver} states for
 * ssh_exec; this seam exists so {@link PanXmlApiTransport} and the
 * discovery adapter depend on an interface, never a concrete trust store.
 */
public interface PanTrustRuleResolver {

    TrustResolution resolveTrust(String trustRuleRef);
}
