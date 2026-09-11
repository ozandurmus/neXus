package com.securityexpert.nexus.ui2.persistence.device;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link CredentialReferenceRepository}. Read-only (no
 * mutation, so no audit context is required here -- C1 §3.5 only fires on
 * a mutating statement, and this class issues none).
 */
public final class JooqCredentialReferenceRepository implements CredentialReferenceRepository {

    private static final String COLUMNS = "credential_reference_id, purpose, backend_pointer, created_at";

    private final TransactionBoundary transactionBoundary;

    public JooqCredentialReferenceRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public boolean exists(String credentialReferenceId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch(
                    "select 1 from credential_references where credential_reference_id = {0} limit 1",
                    credentialReferenceId);
            return !rows.isEmpty();
        });
    }

    @Override
    public Optional<CredentialReferenceRecord> find(String credentialReferenceId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + COLUMNS
                    + " from credential_references where credential_reference_id = {0}", credentialReferenceId);
            return rows.stream().findFirst().map(JooqCredentialReferenceRepository::toRecord);
        });
    }

    private static CredentialReferenceRecord toRecord(Record row) {
        return new CredentialReferenceRecord(
                row.get("credential_reference_id", String.class),
                row.get("purpose", String.class),
                row.get("backend_pointer", String.class),
                row.get("created_at", Timestamp.class).toInstant());
    }
}
