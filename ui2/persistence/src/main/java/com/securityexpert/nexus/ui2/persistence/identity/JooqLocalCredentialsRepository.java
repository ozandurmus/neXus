package com.securityexpert.nexus.ui2.persistence.identity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;

/**
 * jOOQ-backed {@link LocalCredentialsRepository}. Every mutation runs
 * through {@link AuditedTransactionBoundary} (F3) since {@code local_credentials}
 * carries {@code trg_audit_local_credentials} (V8, C3A contract §8) on
 * every {@code INSERT}/{@code UPDATE}/{@code DELETE}.
 */
public final class JooqLocalCredentialsRepository implements LocalCredentialsRepository {

    private static final String COLUMNS = "local_identity_id, local_identity_name, verifier, salt, algorithm_id, "
            + "memory_cost_kib, time_cost, parallelism, failed_attempt_count, locked_until, created_at, updated_at, "
            + "must_change_password";
            + "enabled, created_by_actor_fingerprint, password_set_at, must_change_password";

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqLocalCredentialsRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public Optional<LocalCredentialRecord> findByName(String localIdentityName) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + COLUMNS
                    + " from local_credentials where local_identity_name = {0}", localIdentityName);
            return rows.stream().findFirst().map(JooqLocalCredentialsRepository::toRecord);
        });
    }

    @Override
    public Optional<LocalCredentialRecord> findById(String localIdentityId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + COLUMNS
                    + " from local_credentials where local_identity_id = {0}", localIdentityId);
            return rows.stream().findFirst().map(JooqLocalCredentialsRepository::toRecord);
        });
    }

    @Override
    public boolean anyExist() {
        return transactionBoundary.inTransaction(dsl -> !dsl.fetch("select 1 from local_credentials limit 1").isEmpty());
    }

    @Override
    public List<LocalCredentialRecord> findAll() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch("select " + COLUMNS + " from local_credentials")
                .stream().map(JooqLocalCredentialsRepository::toRecord).toList());
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + COLUMNS + " from local_credentials order by created_at");
            return rows.stream().map(JooqLocalCredentialsRepository::toRecord).toList();
        });
    }

    @Override
    public String create(String localIdentityId, String localIdentityName, Argon2PasswordHasher.Verifier verifier,
            String createdByActorFingerprint) {
        return auditedTransactionBoundary.inTransaction(createdByActorFingerprint, ACTION_CREDENTIAL_CREATE, dsl -> {
            dsl.execute("insert into local_credentials(local_identity_id, local_identity_name, verifier, salt, "
                    + "algorithm_id, memory_cost_kib, time_cost, parallelism, enabled, created_by_actor_fingerprint, "
                    + "password_set_at, must_change_password) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, true, {8}, {9}, true)",
                    localIdentityId, localIdentityName, verifier.verifier(), verifier.salt(), verifier.algorithmId(),
                    verifier.parameters().memoryCostKib(), verifier.parameters().timeCost(),
                    verifier.parameters().parallelism(), createdByActorFingerprint, Timestamp.from(Instant.now()));
            return localIdentityId;
        });
    }

    @Override
    public void recordFailedAttempt(String localIdentityId, Instant now, int lockoutThreshold,
            Duration lockoutDuration) {
        auditedTransactionBoundary.inTransaction(SYSTEM_LOCAL_LOGIN_ATTEMPT, ACTION_LOGIN_FAILURE, dsl -> dsl.execute(
                "update local_credentials set failed_attempt_count = failed_attempt_count + 1, updated_at = {0}, "
                        + "locked_until = case when failed_attempt_count + 1 >= {1} then {2} else locked_until end "
                        + "where local_identity_id = {3}",
                Timestamp.from(now), lockoutThreshold, Timestamp.from(now.plus(lockoutDuration)), localIdentityId));
    }

    @Override
    public void recordSuccessfulLogin(String localIdentityId, Instant now, String actorFingerprint) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, ACTION_LOGIN_SUCCESS, dsl -> dsl.execute(
                "update local_credentials set failed_attempt_count = 0, locked_until = null, updated_at = {0} "
                        + "where local_identity_id = {1}",
                Timestamp.from(now), localIdentityId));
    }

    @Override
    public void changePassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
            String changedByActorFingerprint) {
        // AC-2: must_change_password clears in the SAME statement that
        // writes the new verifier -- never a second, separately-committable
        // call (NXS-LOCAL-0152).
        auditedTransactionBoundary.inTransaction(changedByActorFingerprint, ACTION_PASSWORD_CHANGE, dsl -> dsl.execute(
                "update local_credentials set verifier = {0}, salt = {1}, algorithm_id = {2}, memory_cost_kib = {3}, "
                        + "time_cost = {4}, parallelism = {5}, updated_at = {6}, must_change_password = false "
                        + "time_cost = {4}, parallelism = {5}, updated_at = {6}, password_set_at = {6} "
                        + "where local_identity_id = {7}",
                newVerifier.verifier(), newVerifier.salt(), newVerifier.algorithmId(),
                newVerifier.parameters().memoryCostKib(), newVerifier.parameters().timeCost(),
                newVerifier.parameters().parallelism(), Timestamp.from(java.time.Instant.now()), localIdentityId));
    }

    @Override
    public void markMustChangePassword(String localIdentityId, String actorFingerprint) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, ACTION_MUST_CHANGE_PASSWORD_SEED, dsl -> dsl.execute(
                "update local_credentials set must_change_password = true, updated_at = {0} where local_identity_id = {1}",
                Timestamp.from(java.time.Instant.now()), localIdentityId));
    public void adminSetPassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
            String settingAdminActorFingerprint) {
        auditedTransactionBoundary.inTransaction(settingAdminActorFingerprint, ACTION_ADMIN_PASSWORD_RESET,
                dsl -> dsl.execute(
                        "update local_credentials set verifier = {0}, salt = {1}, algorithm_id = {2}, "
                                + "memory_cost_kib = {3}, time_cost = {4}, parallelism = {5}, updated_at = {6}, "
                                + "password_set_at = {6}, must_change_password = true where local_identity_id = {7}",
                        newVerifier.verifier(), newVerifier.salt(), newVerifier.algorithmId(),
                        newVerifier.parameters().memoryCostKib(), newVerifier.parameters().timeCost(),
                        newVerifier.parameters().parallelism(), Timestamp.from(Instant.now()), localIdentityId));
    }

    @Override
    public void setEnabled(String localIdentityId, boolean enabled, String actingAdminActorFingerprint) {
        String actionId = enabled ? ACTION_ENABLE : ACTION_DISABLE;
        auditedTransactionBoundary.inTransaction(actingAdminActorFingerprint, actionId, dsl -> dsl.execute(
                "update local_credentials set enabled = {0}, updated_at = {1} where local_identity_id = {2}",
                enabled, Timestamp.from(Instant.now()), localIdentityId));
    }

    private static LocalCredentialRecord toRecord(Record row) {
        Timestamp lockedUntil = row.get("locked_until", Timestamp.class);
        return new LocalCredentialRecord(
                row.get("local_identity_id", String.class),
                row.get("local_identity_name", String.class),
                row.get("verifier", byte[].class),
                row.get("salt", byte[].class),
                row.get("algorithm_id", String.class),
                row.get("memory_cost_kib", Integer.class),
                row.get("time_cost", Integer.class),
                row.get("parallelism", Integer.class),
                row.get("failed_attempt_count", Integer.class),
                Optional.ofNullable(lockedUntil).map(Timestamp::toInstant),
                row.get("created_at", Timestamp.class).toInstant(),
                row.get("updated_at", Timestamp.class).toInstant(),
                Boolean.TRUE.equals(row.get("must_change_password", Boolean.class)));
                row.get("enabled", Boolean.class),
                row.get("created_by_actor_fingerprint", String.class),
                row.get("password_set_at", Timestamp.class).toInstant(),
                row.get("must_change_password", Boolean.class));
    }
}
