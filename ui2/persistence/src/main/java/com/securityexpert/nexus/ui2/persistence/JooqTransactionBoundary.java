package com.securityexpert.nexus.ui2.persistence;

import java.util.Objects;
import java.util.function.Function;

import org.jooq.DSLContext;

/**
 * The concrete {@link TransactionBoundary}: one jOOQ transaction per call,
 * committed on normal return, rolled back on any exception the work throws
 * (jOOQ's own {@code transactionResult} semantics). Every mutation this
 * movement performs against an audited table runs inside exactly one call
 * to {@link #inTransaction}, wrapped by {@link AuditedTransactionBoundary}
 * so the mutation and its {@code SET LOCAL} context commit or roll back
 * together (C1 §3.5's "commit atomically or neither does").
 */
public final class JooqTransactionBoundary implements TransactionBoundary {

    private final DSLContext dsl;

    public JooqTransactionBoundary(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public <T> T inTransaction(Function<DSLContext, T> work) {
        return dsl.transactionResult(configuration -> work.apply(configuration.dsl()));
    }
}
