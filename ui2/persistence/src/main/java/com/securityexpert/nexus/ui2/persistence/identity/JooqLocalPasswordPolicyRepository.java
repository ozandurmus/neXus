package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;

/**
 * jOOQ implementation. The settings write runs under {@link AuditedTransactionBoundary} so the audit trigger on
 * {@code local_password_policy_settings} records actor, action and the old and new values; the settings hold no
 * secrets. The history table carries tier-1 material and has no audit trigger.
 */
public final class JooqLocalPasswordPolicyRepository implements LocalPasswordPolicyRepository {
    static final String SELECT = "select enabled, min_length, require_upper, require_lower, require_digit, "
            + "require_symbol, history_count, max_age_days, updated_at, updated_by "
            + "from local_password_policy_settings where settings_id = 'default'";

    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public JooqLocalPasswordPolicyRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    @Override
    public Row read() {
        return transactions.inTransaction(JooqLocalPasswordPolicyRepository::select);
    }

    @Override
    public Row write(String actorFingerprint, LocalPasswordPolicySettings s) {
        Objects.requireNonNull(s, "settings");
        return audited.inTransaction(actorFingerprint, WRITE_ACTION, dsl -> {
            dsl.execute("insert into local_password_policy_settings(settings_id, enabled, min_length, require_upper, "
                            + "require_lower, require_digit, require_symbol, history_count, max_age_days, updated_at, updated_by) "
                            + "values ('default', {0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, now(), {8}) "
                            + "on conflict (settings_id) do update set enabled = excluded.enabled, "
                            + "min_length = excluded.min_length, require_upper = excluded.require_upper, "
                            + "require_lower = excluded.require_lower, require_digit = excluded.require_digit, "
                            + "require_symbol = excluded.require_symbol, history_count = excluded.history_count, "
                            + "max_age_days = excluded.max_age_days, updated_at = now(), updated_by = excluded.updated_by",
                    s.enabled(), s.minLength(), s.requireUpper(), s.requireLower(), s.requireDigit(), s.requireSymbol(),
                    s.historyCount(), s.maxAgeDays(), actorFingerprint);
            return select(dsl);
        });
    }

    @Override
    public List<Argon2PasswordHasher.Verifier> recentRetired(String localIdentityId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return transactions.inTransaction(dsl -> dsl.fetch("select verifier, salt, algorithm_id, memory_cost_kib, "
                        + "time_cost, parallelism from local_password_history where local_identity_id = {0} "
                        + "order by history_id desc limit {1}", localIdentityId, limit).stream()
                .map(r -> LocalCredentialRecord.verifierFrom(r.get("verifier", byte[].class), r.get("salt", byte[].class),
                        r.get("algorithm_id", String.class), r.get("memory_cost_kib", Integer.class),
                        r.get("time_cost", Integer.class), r.get("parallelism", Integer.class)))
                .toList());
    }

    @Override
    public void retire(String localIdentityId, Argon2PasswordHasher.Verifier retired, int keep) {
        transactions.inTransaction(dsl -> {
            dsl.execute("insert into local_password_history(local_identity_id, verifier, salt, algorithm_id, "
                            + "memory_cost_kib, time_cost, parallelism) values ({0}, {1}, {2}, {3}, {4}, {5}, {6})",
                    localIdentityId, retired.verifier(), retired.salt(), retired.algorithmId(),
                    retired.parameters().memoryCostKib(), retired.parameters().timeCost(),
                    retired.parameters().parallelism());
            dsl.execute("delete from local_password_history where local_identity_id = {0} and history_id not in "
                    + "(select history_id from local_password_history where local_identity_id = {0} "
                    + "order by history_id desc limit {1})", localIdentityId, Math.max(keep, 1));
            return null;
        });
    }

    private static Row select(DSLContext dsl) {
        return dsl.fetchOptional(SELECT).map(JooqLocalPasswordPolicyRepository::row)
                .orElse(new Row(LocalPasswordPolicySettings.DEFAULTS, null, null));
    }

    private static Row row(Record r) {
        var settings = new LocalPasswordPolicySettings(Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                r.get("min_length", Integer.class), Boolean.TRUE.equals(r.get("require_upper", Boolean.class)),
                Boolean.TRUE.equals(r.get("require_lower", Boolean.class)),
                Boolean.TRUE.equals(r.get("require_digit", Boolean.class)),
                Boolean.TRUE.equals(r.get("require_symbol", Boolean.class)), r.get("history_count", Integer.class),
                r.get("max_age_days", Integer.class));
        OffsetDateTime at = r.get("updated_at", OffsetDateTime.class);
        return new Row(settings, at == null ? null : at.toInstant(), r.get("updated_by", String.class));
    }
}
