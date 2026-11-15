package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.OffsetDateTime;
import java.util.Objects;

import org.jooq.DSLContext;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ implementation; the write runs under {@link AuditedTransactionBoundary} so the audit trigger records old and new mode. */
public final class JooqLocalAuthModeRepository implements LocalAuthModeRepository {
    private static final String SELECT = "select mode, updated_at, updated_by from local_auth_mode_settings where settings_id = 'default'";

    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public JooqLocalAuthModeRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    @Override
    public Row read() {
        return transactions.inTransaction(JooqLocalAuthModeRepository::select);
    }

    @Override
    public Row write(String actorFingerprint, String mode) {
        if (!ALL_LOCAL.equals(mode) && !BREAK_GLASS_ONLY.equals(mode)) {
            throw new IllegalArgumentException("unknown local auth mode");
        }
        return audited.inTransaction(actorFingerprint, WRITE_ACTION, dsl -> {
            dsl.execute("insert into local_auth_mode_settings(settings_id, mode, updated_at, updated_by) "
                    + "values ('default', {0}, now(), {1}) on conflict (settings_id) do update set mode = excluded.mode, "
                    + "updated_at = now(), updated_by = excluded.updated_by", mode, actorFingerprint);
            return select(dsl);
        });
    }

    private static Row select(DSLContext dsl) {
        return dsl.fetchOptional(SELECT).map(r -> {
            OffsetDateTime at = r.get("updated_at", OffsetDateTime.class);
            return new Row(r.get("mode", String.class), at == null ? null : at.toInstant(), r.get("updated_by", String.class));
        }).orElse(new Row(ALL_LOCAL, null, null));
    }
}
