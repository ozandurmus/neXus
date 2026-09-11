package com.securityexpert.nexus.ui2.persistence.identity;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link RoleBindingRepository}. Every mutation carries the
 * audit context (F3): {@code role_bindings} is audited on every
 * {@code INSERT}/{@code UPDATE}/{@code DELETE} (C3 §4.2).
 */
public final class JooqRoleBindingRepository implements RoleBindingRepository {

    private static final String COLUMNS = "binding_id, role_token, group_reference_encrypted, "
            + "group_reference_key_id, created_by_actor_fingerprint, created_at, revoked_at, "
            + "revoked_by_actor_fingerprint";

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqRoleBindingRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public List<RoleBindingRecord> findActiveByToken(String roleToken) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + COLUMNS
                    + " from role_bindings where role_token = {0} and revoked_at is null", roleToken);
            return rows.stream().map(JooqRoleBindingRepository::toRecord).toList();
        });
    }

    @Override
    public Optional<RoleBindingRecord> find(String bindingId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + COLUMNS + " from role_bindings where binding_id = {0}",
                    bindingId);
            return rows.stream().findFirst().map(JooqRoleBindingRepository::toRecord);
        });
    }

    @Override
    public boolean hasAnyActiveBinding(String roleToken) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch(
                    "select 1 from role_bindings where role_token = {0} and revoked_at is null limit 1", roleToken);
            return !rows.isEmpty();
        });
    }

    @Override
    public String create(String bindingId, String roleToken, byte[] groupReferenceEncrypted,
            String groupReferenceKeyId, String createdByActorFingerprint, String actionId) {
        return auditedTransactionBoundary.inTransaction(createdByActorFingerprint, actionId, dsl -> {
            dsl.execute("insert into role_bindings(binding_id, role_token, group_reference_encrypted, "
                    + "group_reference_key_id, created_by_actor_fingerprint, created_at) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5})",
                    bindingId, roleToken, groupReferenceEncrypted, groupReferenceKeyId,
                    createdByActorFingerprint, Timestamp.from(java.time.Instant.now()));
            return bindingId;
        });
    }

    @Override
    public void revoke(String bindingId, String revokedByActorFingerprint, String actionId) {
        auditedTransactionBoundary.inTransaction(revokedByActorFingerprint, actionId, dsl -> dsl.execute(
                "update role_bindings set revoked_at = {0}, revoked_by_actor_fingerprint = {1} "
                        + "where binding_id = {2} and revoked_at is null",
                Timestamp.from(java.time.Instant.now()), revokedByActorFingerprint, bindingId));
    }

    private static RoleBindingRecord toRecord(Record row) {
        Timestamp revokedAt = row.get("revoked_at", Timestamp.class);
        return new RoleBindingRecord(
                row.get("binding_id", String.class),
                row.get("role_token", String.class),
                row.get("group_reference_encrypted", byte[].class),
                row.get("group_reference_key_id", String.class),
                row.get("created_by_actor_fingerprint", String.class),
                row.get("created_at", Timestamp.class).toInstant(),
                Optional.ofNullable(revokedAt).map(Timestamp::toInstant),
                Optional.ofNullable(row.get("revoked_by_actor_fingerprint", String.class)));
    }
}
