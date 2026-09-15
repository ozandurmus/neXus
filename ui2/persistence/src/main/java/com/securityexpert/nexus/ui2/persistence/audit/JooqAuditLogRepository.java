package com.securityexpert.nexus.ui2.persistence.audit;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link AuditLogRepository}, in the same raw-SQL-plus-record-mapping
 * style as {@code JooqDiscoveryRunRepository}. Every method here issues only
 * {@code SELECT} -- {@code ui2_app} is granted nothing else on either table
 * (contract §7, AC-8), and no method in this class constructs an
 * {@code INSERT}/{@code UPDATE}/{@code DELETE}/{@code COPY}/{@code TRUNCATE}
 * statement against {@code audit_log} or {@code audit_redaction_policy}.
 */
public final class JooqAuditLogRepository implements AuditLogRepository {

    private final TransactionBoundary transactionBoundary;

    public JooqAuditLogRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = transactionBoundary;
    }

    @Override
    public AuditPage findPage(AuditListFilters filters, Optional<Long> cursorExclusive, int limit) {
        List<Object> bindings = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "select audit_id, table_name, row_pk, operation, actor_fingerprint, action_id, occurred_at, "
                        + "correlation_run_id, before_state::text as before_state, after_state::text as after_state "
                        + "from audit_log where 1 = 1");

        filters.scopeActorFingerprint().ifPresent(value -> appendEquals(sql, bindings, "actor_fingerprint", value));
        filters.tableName().ifPresent(value -> appendEquals(sql, bindings, "table_name", value));
        filters.rowPk().ifPresent(value -> appendEquals(sql, bindings, "row_pk", value));
        filters.operation().ifPresent(value -> appendEquals(sql, bindings, "operation", value));
        filters.actorFingerprint().ifPresent(value -> appendEquals(sql, bindings, "actor_fingerprint", value));
        filters.actionId().ifPresent(value -> appendEquals(sql, bindings, "action_id", value));
        filters.correlationRunId().ifPresent(value -> appendEquals(sql, bindings, "correlation_run_id", value));
        filters.occurredFrom().ifPresent(value -> {
            sql.append(" and occurred_at >= {").append(bindings.size()).append('}');
            bindings.add(Timestamp.from(value));
        });
        filters.occurredTo().ifPresent(value -> {
            sql.append(" and occurred_at < {").append(bindings.size()).append('}');
            bindings.add(Timestamp.from(value));
        });
        cursorExclusive.ifPresent(value -> {
            sql.append(" and audit_id < {").append(bindings.size()).append('}');
            bindings.add(value);
        });

        // One extra row fetched, never returned, solely to know whether a
        // next_cursor exists -- never a COUNT(*), never an OFFSET (§5.3, AC-7).
        sql.append(" order by audit_id desc limit {").append(bindings.size()).append('}');
        bindings.add((long) (limit + 1));

        List<AuditLogRow> fetched = transactionBoundary.inTransaction(
                dsl -> dsl.fetch(sql.toString(), bindings.toArray()))
                .stream().map(JooqAuditLogRepository::toRow).toList();

        boolean hasMore = fetched.size() > limit;
        List<AuditLogRow> page = hasMore ? fetched.subList(0, limit) : fetched;
        Optional<Long> nextCursor = hasMore ? Optional.of(page.get(page.size() - 1).auditId()) : Optional.empty();
        return new AuditPage(List.copyOf(page), nextCursor);
    }

    @Override
    public Optional<AuditLogRow> findById(long auditId, Optional<String> scopeActorFingerprint) {
        List<Object> bindings = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "select audit_id, table_name, row_pk, operation, actor_fingerprint, action_id, occurred_at, "
                        + "correlation_run_id, before_state::text as before_state, after_state::text as after_state "
                        + "from audit_log where audit_id = {0}");
        bindings.add(auditId);
        scopeActorFingerprint.ifPresent(value -> appendEquals(sql, bindings, "actor_fingerprint", value));

        return transactionBoundary.inTransaction(dsl -> dsl.fetch(sql.toString(), bindings.toArray()))
                .stream().findFirst().map(JooqAuditLogRepository::toRow);
    }

    @Override
    public List<AuditRedactionPolicyRow> findRedactionPolicy() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                        "select table_name, column_name, tier, reason from audit_redaction_policy")
                .stream().map(JooqAuditLogRepository::toPolicyRow).toList());
    }

    private static void appendEquals(StringBuilder sql, List<Object> bindings, String column, String value) {
        sql.append(" and ").append(column).append(" = {").append(bindings.size()).append('}');
        bindings.add(value);
    }

    private static AuditLogRow toRow(Record row) {
        return new AuditLogRow(
                row.get("audit_id", Long.class),
                row.get("table_name", String.class),
                row.get("row_pk", String.class),
                row.get("operation", String.class),
                row.get("actor_fingerprint", String.class),
                row.get("action_id", String.class),
                row.get("occurred_at", Timestamp.class).toInstant(),
                Optional.ofNullable(row.get("correlation_run_id", String.class)),
                Optional.ofNullable(row.get("before_state", String.class)),
                Optional.ofNullable(row.get("after_state", String.class)));
    }

    private static AuditRedactionPolicyRow toPolicyRow(Record row) {
        return new AuditRedactionPolicyRow(
                row.get("table_name", String.class),
                row.get("column_name", String.class),
                row.get("tier", Integer.class),
                row.get("reason", String.class));
    }
}
