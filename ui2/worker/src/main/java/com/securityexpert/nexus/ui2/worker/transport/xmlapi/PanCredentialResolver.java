package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

/**
 * Resolves an opaque {@code credentialRef} to Panorama management-plane
 * credential material. The real, secret-backed implementation is out of
 * this movement's scope (mirrors {@code SshCredentialResolver}); this seam
 * exists so the discovery adapter depends on an interface, never a
 * concrete secrets backend.
 */
public interface PanCredentialResolver {

    PanCredentialMaterial resolve(String credentialRef);
}
