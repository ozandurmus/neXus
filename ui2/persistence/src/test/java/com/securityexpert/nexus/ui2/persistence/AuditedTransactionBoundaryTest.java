package com.securityexpert.nexus.ui2.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

/**
 * Contract §7's F3 audit-context mechanism, proved without a live
 * PostgreSQL instance using jOOQ's {@link MockDataProvider}: the two
 * {@code SET LOCAL} statements are issued, with the exact actor/action
 * values, strictly before the caller's own work runs.
 */
class AuditedTransactionBoundaryTest {

    /**
     * {@link TransactionBoundary#inTransaction} is a generic method (its
     * type parameter is declared on the method, not the interface), which
     * is not a valid lambda target -- javac needs a concrete functional
     * descriptor. An anonymous class implements it directly instead.
     */
    private static TransactionBoundary directDelegate(DSLContext dsl) {
        return new TransactionBoundary() {
            @Override
            public <T> T inTransaction(Function<DSLContext, T> work) {
                return work.apply(dsl);
            }
        };
    }

    @Test
    void issuesSetLocalActorAndActionBeforeTheCallersWork() {
        List<String> executedSql = new ArrayList<>();
        MockDataProvider provider = ctx -> {
            executedSql.add(ctx.sql());
            return new MockResult[] { new MockResult(0, null) };
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        AuditedTransactionBoundary audited = new AuditedTransactionBoundary(directDelegate(dsl));

        audited.inTransaction("af3a9c1e2b7d", "session_login", ctx -> {
            executedSql.add("WORK");
            return null;
        });

        assertEquals(3, executedSql.size());
        assertTrue(executedSql.get(0).contains("SET LOCAL app.actor_fingerprint"));
        assertTrue(executedSql.get(0).contains("af3a9c1e2b7d"));
        assertTrue(executedSql.get(1).contains("SET LOCAL app.action_id"));
        assertTrue(executedSql.get(1).contains("session_login"));
        assertEquals("WORK", executedSql.get(2));
    }

    @Test
    void refusesToRunWithoutAnActorFingerprint() {
        AuditedTransactionBoundary audited = new AuditedTransactionBoundary(new TransactionBoundary() {
            @Override
            public <T> T inTransaction(Function<DSLContext, T> work) {
                throw new AssertionError("delegate must never run when actor/action is blank");
            }
        });

        assertThrows(IllegalArgumentException.class,
                () -> audited.inTransaction("", "session_login", ctx -> null));
        assertThrows(IllegalArgumentException.class,
                () -> audited.inTransaction("af3a9c1e2b7d", "", ctx -> null));
        assertThrows(IllegalArgumentException.class,
                () -> audited.inTransaction(null, "session_login", ctx -> null));
    }

    @Test
    void escapesASingleQuoteInAnActorOrActionValue() {
        List<String> executedSql = new ArrayList<>();
        MockDataProvider provider = ctx -> {
            executedSql.add(ctx.sql());
            return new MockResult[] { new MockResult(0, null) };
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        AuditedTransactionBoundary audited = new AuditedTransactionBoundary(directDelegate(dsl));

        audited.inTransaction("a'b", "action'x", ctx -> null);

        assertTrue(executedSql.get(0).contains("a''b"));
        assertTrue(executedSql.get(1).contains("action''x"));
    }
}
