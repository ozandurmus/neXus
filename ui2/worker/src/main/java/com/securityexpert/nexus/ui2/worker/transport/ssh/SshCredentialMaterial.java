package com.securityexpert.nexus.ui2.worker.transport.ssh;

/**
 * Resolved SSH authentication material for one {@code
 * execution_credential_ref} (C2 §5.4/C1 §6: opaque reference in, never a
 * raw secret persisted or logged). The server-side credential store
 * itself (C2 §6 check 6) is out of this movement's scope -- {@link
 * SshCredentialResolver} is the seam a later movement wires to it; this
 * record is only the shape the adapter needs once resolved.
 */
public record SshCredentialMaterial(String username, char[] password, byte[] privateKeyPem) {
}
