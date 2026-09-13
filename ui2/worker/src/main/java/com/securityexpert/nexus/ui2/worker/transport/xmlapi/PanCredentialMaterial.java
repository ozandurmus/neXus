package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

/**
 * Resolved Panorama management-plane credential for one {@code
 * credentialRef} (T-5: opaque reference in, never a raw secret persisted or
 * logged). Mirrors {@code SshCredentialMaterial}'s shape for the ssh_exec
 * adapter.
 */
public record PanCredentialMaterial(String username, char[] password) {
}
