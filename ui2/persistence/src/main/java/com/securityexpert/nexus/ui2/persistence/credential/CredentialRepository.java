package com.securityexpert.nexus.ui2.persistence.credential;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;

/**
 * {@code credentials} persistence port (CS-1). Every mutation implicitly
 * goes through {@link com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary}
 * (F3): the table carries the narrow, allowlisted audit trigger
 * {@code fn_audit_capture_credentials()} (V11), so a mutating call here
 * always needs an actor fingerprint and action id.
 */
public interface CredentialRepository {

    String ACTION_CREDENTIAL_CREATE = "credential_create";
    String ACTION_CREDENTIAL_REPLACE_SECRET = "credential_replace_secret";
    String ACTION_CREDENTIAL_DELETE = "credential_delete";

    /**
     * CS-1: creates the {@code credentials} row and, in the same
     * transaction, the {@code credential_references} row every existing
     * consumer of the reference model needs (purpose = the kind's wire
     * value, backend_pointer = this credential's own id). Returns the new
     * credential's opaque id.
     */
    String create(String credentialId, String credentialReferenceId, String displayName, CredentialKind kind,
            String username, byte[] encryptedSecret, byte[] encryptedPassphrase, String envelopeKeyId,
            boolean allowsCheckPoint, boolean allowsPaloAlto, String createdByActorFingerprint);

    Optional<CredentialRecord> findById(String credentialId);

    List<CredentialRecord> findAll();

    /** The {@code credential_references} row created alongside this credential (CS-1) -- never a second row. */
    Optional<String> findCredentialReferenceId(String credentialId);

    void replaceSecret(String credentialId, byte[] encryptedSecret, byte[] encryptedPassphrase, String envelopeKeyId,
            String actingAdminActorFingerprint);

    /**
     * CS-4: true when some other row (a device, through its own
     * {@code credential_reference_id}) still points at
     * {@code credentialReferenceId} -- the caller refuses the delete rather
     * than calling {@link #delete} when this is true.
     */
    boolean isCredentialReferenceInUse(String credentialReferenceId);

    /** Deletes the {@code credentials} row and its {@code credential_references} row in the same transaction. */
    void delete(String credentialId, String credentialReferenceId, String actingAdminActorFingerprint);
}
