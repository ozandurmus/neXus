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
            + "revoked_by_actor_fingerprint, binding_kind, directory_profile_id";

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
    public List<RoleBindingRecord> findAllActive() {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + COLUMNS
                    + " from role_bindings where revoked_at is null order by created_at desc");
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
    public String createDirectory(RoleBindingRecord binding, String actionId) {
        if (binding.bindingKind() == com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.LEGACY
                || binding.directoryProfileId() == null || binding.directoryProfileId().isBlank()) {
            throw new IllegalArgumentException("directory_binding_invalid");
        }
        return auditedTransactionBoundary.inTransaction(binding.createdByActorFingerprint(), actionId, dsl -> {
            dsl.execute("insert into role_bindings(binding_id, role_token, group_reference_encrypted, group_reference_key_id, "
                    + "created_by_actor_fingerprint, created_at, binding_kind, directory_profile_id) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7})",
                    binding.bindingId(), binding.roleToken(), binding.groupReferenceEncrypted(), binding.groupReferenceKeyId(),
                    binding.createdByActorFingerprint(), Timestamp.from(binding.createdAt()), binding.bindingKind().name(), binding.directoryProfileId());
            return binding.bindingId();
        });
    }

    @Override
    public <T> T directoryMutation(java.util.function.Function<DirectoryMutationRepositories, T> work) {
        return transactionBoundary.inTransaction(dsl -> {
            // ponytail: table locks serialize rare administration, row/advisory protocol if contention matters.
            // Compatible with existing local administration, login, cache refresh and revocation writers.
            dsl.execute("lock table role_bindings, actor_authz_state, sessions, local_credentials in share row exclusive mode");
            TransactionBoundary bound = new TransactionBoundary() {
                @Override public <R> R inTransaction(java.util.function.Function<org.jooq.DSLContext, R> operation) {
                    return operation.apply(dsl);
                }
            };
            return work.apply(new DirectoryMutationRepositories(new JooqRoleBindingRepository(bound),
                    new JooqActorAuthzStateRepository(bound), new JooqSessionRepository(bound), new JooqLocalCredentialsRepository(bound)));
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
                Optional.ofNullable(row.get("revoked_by_actor_fingerprint", String.class)),
                com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.valueOf(row.get("binding_kind", String.class)),
                row.get("directory_profile_id", String.class));
    }
}
