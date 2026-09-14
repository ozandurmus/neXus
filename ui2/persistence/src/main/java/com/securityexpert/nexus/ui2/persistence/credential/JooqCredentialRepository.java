package com.securityexpert.nexus.ui2.persistence.credential;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;

/**
 * jOOQ-backed {@link CredentialRepository}. Every mutation runs through
 * {@link AuditedTransactionBoundary} (F3) since {@code credentials} carries
 * {@code trg_audit_credentials} (V11) and {@code credential_references}
 * carries the generic {@code trg_audit_credential_references} (V1) -- both
 * fire on every mutating statement issued here.
 */
public final class JooqCredentialRepository implements CredentialRepository {

    private static final String COLUMNS = "credential_id, display_name, kind, username, encrypted_secret, "
            + "encrypted_passphrase, envelope_key_id, allows_check_point, allows_palo_alto, "
            + "created_by_actor_fingerprint, created_at, secret_set_at";

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqCredentialRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public String create(String credentialId, String credentialReferenceId, String displayName, CredentialKind kind,
            String username, byte[] encryptedSecret, byte[] encryptedPassphrase, String envelopeKeyId,
            boolean allowsCheckPoint, boolean allowsPaloAlto, String createdByActorFingerprint) {
        return auditedTransactionBoundary.inTransaction(createdByActorFingerprint, ACTION_CREDENTIAL_CREATE, dsl -> {
            dsl.execute("insert into credentials(credential_id, display_name, kind, username, encrypted_secret, "
                    + "encrypted_passphrase, envelope_key_id, allows_check_point, allows_palo_alto, "
                    + "created_by_actor_fingerprint) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9})",
                    credentialId, displayName, kind.wireValue(), username, encryptedSecret, encryptedPassphrase,
                    envelopeKeyId, allowsCheckPoint, allowsPaloAlto, createdByActorFingerprint);
            // CS-1: one credential_references row per stored credential, so
            // B1_04B section 6 and every existing consumer of the reference
            // model (device registration, in particular) keep working.
            dsl.execute("insert into credential_references(credential_reference_id, purpose, backend_pointer) "
                    + "values ({0}, {1}, {2})", credentialReferenceId, kind.wireValue(), credentialId);
            return credentialId;
        });
    }

    @Override
    public Optional<CredentialRecord> findById(String credentialId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows =
                    dsl.fetch("select " + COLUMNS + " from credentials where credential_id = {0}", credentialId);
            return rows.stream().findFirst().map(JooqCredentialRepository::toRecord);
        });
    }

    @Override
    public List<CredentialRecord> findAll() {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + COLUMNS + " from credentials order by created_at");
            return rows.stream().map(JooqCredentialRepository::toRecord).toList();
        });
    }

    @Override
    public Optional<String> findCredentialReferenceId(String credentialId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch(
                    "select credential_reference_id from credential_references where backend_pointer = {0}",
                    credentialId);
            return rows.stream().findFirst().map(row -> row.get("credential_reference_id", String.class));
        });
    }

    @Override
    public void replaceSecret(String credentialId, byte[] encryptedSecret, byte[] encryptedPassphrase,
            String envelopeKeyId, String actingAdminActorFingerprint) {
        auditedTransactionBoundary.inTransaction(actingAdminActorFingerprint, ACTION_CREDENTIAL_REPLACE_SECRET,
                dsl -> dsl.execute("update credentials set encrypted_secret = {0}, encrypted_passphrase = {1}, "
                                + "envelope_key_id = {2}, secret_set_at = {3} where credential_id = {4}",
                        encryptedSecret, encryptedPassphrase, envelopeKeyId, Timestamp.from(Instant.now()),
                        credentialId));
    }

    @Override
    public boolean isCredentialReferenceInUse(String credentialReferenceId) {
        return transactionBoundary.inTransaction(dsl -> !dsl
                .fetch("select 1 from devices where credential_reference_id = {0} limit 1", credentialReferenceId)
                .isEmpty());
    }

    @Override
    public void delete(String credentialId, String credentialReferenceId, String actingAdminActorFingerprint) {
        auditedTransactionBoundary.inTransaction(actingAdminActorFingerprint, ACTION_CREDENTIAL_DELETE, dsl -> {
            dsl.execute("delete from credentials where credential_id = {0}", credentialId);
            dsl.execute("delete from credential_references where credential_reference_id = {0}",
                    credentialReferenceId);
            return null;
        });
    }

    private static CredentialRecord toRecord(Record row) {
        return new CredentialRecord(
                row.get("credential_id", String.class),
                row.get("display_name", String.class),
                CredentialKind.fromWireValue(row.get("kind", String.class)),
                row.get("username", String.class),
                row.get("encrypted_secret", byte[].class),
                row.get("encrypted_passphrase", byte[].class),
                row.get("envelope_key_id", String.class),
                Boolean.TRUE.equals(row.get("allows_check_point", Boolean.class)),
                Boolean.TRUE.equals(row.get("allows_palo_alto", Boolean.class)),
                row.get("created_by_actor_fingerprint", String.class),
                row.get("created_at", Timestamp.class).toInstant(),
                row.get("secret_set_at", Timestamp.class).toInstant());
    }
}
