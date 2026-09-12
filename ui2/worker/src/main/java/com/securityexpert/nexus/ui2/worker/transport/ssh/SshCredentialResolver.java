package com.securityexpert.nexus.ui2.worker.transport.ssh;

/**
 * Resolves an opaque {@code execution_credential_ref} to SSH auth material.
 * The real, secret-backed implementation is out of this movement's scope
 * (C2 §6 check 6, the server-side credential store, is not built here);
 * this seam exists so {@link SshExecTransport} depends on an interface,
 * never a concrete secrets backend.
 */
public interface SshCredentialResolver {

    SshCredentialMaterial resolve(String credentialRef);
}
