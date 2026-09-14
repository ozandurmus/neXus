package com.securityexpert.nexus.ui2.persistence.credential;

import java.time.Instant;

import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;

/**
 * A full {@code credentials} row (CS-1), including the two encrypted
 * columns. Deliberately not the type the HTTP API, the CLI, or the frontend
 * ever see -- {@link com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialView}
 * is that type. This record exists only for the store's own write/decrypt
 * path (worker resolution, administration round trips).
 */
public record CredentialRecord(
        String credentialId,
        String displayName,
        CredentialKind kind,
        String username,
        byte[] encryptedSecret,
        byte[] encryptedPassphrase,
        String envelopeKeyId,
        boolean allowsCheckPoint,
        boolean allowsPaloAlto,
        String createdByActorFingerprint,
        Instant createdAt,
        Instant secretSetAt) {
}
