package com.securityexpert.nexus.ui2.persistence;

import java.util.Objects;
import java.util.function.Function;

import org.jooq.DSLContext;

/**
 * <b>The audit-context mechanism (adjudication F3).</b> C1 §3.5 requires
 * every mutating transaction against a mutation-bearing table to carry
 * {@code app.actor_fingerprint} and {@code app.action_id} in the current
 * transaction, set before the mutation, or {@code fn_audit_capture()}
 * raises {@code audit_context_missing} and the whole transaction rolls
 * back. C1 §3.5 names only "the Java service's transaction interceptor";
 * this class <b>is</b> that interceptor's mechanism — B1-3 owns it per
 * adjudication F3 part one (request-scoped mutations; B1-4 owns the
 * executor-scoped equivalent for worker mutations).
 *
 * <p>Every caller that mutates an audited table (directly, or through a
 * repository in this module) must go through {@link #inTransaction} rather
 * than {@link TransactionBoundary} directly — bypassing this class and
 * calling the plain {@link TransactionBoundary} for such a mutation is
 * exactly the bug this class exists to make structurally unlikely: the
 * database itself still refuses the write (that is what makes F3's fix
 * fail-closed rather than merely convention), but this class is the
 * intended, single call path.</p>
 *
 * <p>{@code SET LOCAL} scopes the value to the current transaction only —
 * never visible to a concurrent transaction, discarded on commit/rollback
 * (C1 §3.5). PostgreSQL's {@code SET} command does not accept a JDBC bind
 * parameter in place of its value, so the value is embedded as a quoted SQL
 * string literal with standard single-quote doubling ({@link #sqlLiteral}) —
 * every value passed here is this movement's own opaque fingerprint or
 * closed-vocabulary action id, never end-user free text.</p>
 */
public final class AuditedTransactionBoundary {

    private final TransactionBoundary delegate;

    public AuditedTransactionBoundary(TransactionBoundary delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public <T> T inTransaction(String actorFingerprint, String actionId, Function<DSLContext, T> work) {
        return inTransaction(actorFingerprint, actionId, null, work);
    }

    public <T> T inTransaction(String actorFingerprint, String actionId, String correlationRunId,
            Function<DSLContext, T> work) {
        requireNonBlank(actorFingerprint, "actorFingerprint");
        requireNonBlank(actionId, "actionId");
        return delegate.inTransaction(dsl -> {
            dsl.execute("SET LOCAL app.actor_fingerprint = " + sqlLiteral(actorFingerprint));
            dsl.execute("SET LOCAL app.action_id = " + sqlLiteral(actionId));
            if (correlationRunId != null && !correlationRunId.isBlank()) {
                dsl.execute("SET LOCAL app.correlation_run_id = " + sqlLiteral(correlationRunId));
            }
            return work.apply(dsl);
        });
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be set before a transaction that will mutate "
                    + "an audited table -- see AuditedTransactionBoundary javadoc (adjudication F3)");
        }
    }

    private static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
