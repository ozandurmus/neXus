package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Optional;

/**
 * {@code credential_references} read port. Registration (contract §4)
 * only ever references an existing row by opaque id -- this port
 * deliberately has no {@code create}: a {@code CredentialReference} is
 * created by its own surface ({@code credential_references}/{@code
 * secrets_metadata}'s, out of scope of this movement, contract §2).
 */
public interface CredentialReferenceRepository {

    boolean exists(String credentialReferenceId);

    Optional<CredentialReferenceRecord> find(String credentialReferenceId);
}
