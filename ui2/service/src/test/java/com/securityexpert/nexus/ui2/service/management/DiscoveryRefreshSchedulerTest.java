package com.securityexpert.nexus.ui2.service.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;
import java.time.Clock;
import java.time.Instant;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

class DiscoveryRefreshSchedulerTest {
    @Test
    void skipsManagerWhoseLatestRunTimedOut() {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            if (ctx.sql().startsWith("update discovery_run")) return new MockResult[] { new MockResult(0) };
            assertTrue(ctx.sql().contains("latest.outcome_summary"));
            assertTrue(ctx.sql().contains("manager command timed out; stopped"));
            Result<Record> rows = create.fetchFromStringData(
                    new String[] { "vendor", "management_address", "credential_reference_id", "skip_timeout" },
                    new String[] { "check_point", "192.0.2.1", "fixture-ref", "true" });
            return new MockResult[] { new MockResult(1, rows) };
        }), SQLDialect.POSTGRES);
        TransactionBoundary tx = new TransactionBoundary() {
            @Override
            public <T> T inTransaction(Function<DSLContext, T> work) {
                return work.apply(dsl);
            }
        };

        assertEquals(0, new DiscoveryRefreshScheduler(tx, null, new JobWindowPolicy(
                Clock.fixed(Instant.parse("2026-10-08T09:10:00Z"), JobWindowPolicy.ZONE_ID), 60)).refreshAll());
    }

    @Test
    void closesRunsWhoseJobEndedBeforeChoosingTargets() {
        java.util.List<String> statements = new java.util.ArrayList<>();
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            statements.add(ctx.sql());
            if (ctx.sql().startsWith("update discovery_run")) return new MockResult[] { new MockResult(5) };
            Result<Record> none = create.fetchFromStringData(
                    new String[] { "vendor", "management_address", "credential_reference_id", "skip_timeout" });
            return new MockResult[] { new MockResult(0, none) };
        }), SQLDialect.POSTGRES);
        TransactionBoundary tx = new TransactionBoundary() {
            @Override
            public <T> T inTransaction(Function<DSLContext, T> work) {
                return work.apply(dsl);
            }
        };

        assertEquals(0, new DiscoveryRefreshScheduler(tx, null, new JobWindowPolicy(
                Clock.fixed(Instant.parse("2026-10-08T09:10:00Z"), JobWindowPolicy.ZONE_ID), 60)).refreshAll());
        assertEquals(2, statements.size());
        String close = statements.get(0);
        assertTrue(close.startsWith("update discovery_run"));
        assertTrue(close.contains("r.finished_at is null"));
        assertTrue(close.contains("'CANCELLED'"));
        assertTrue(!close.contains("'EXECUTING'") && !close.contains("'REQUESTED'") && !close.contains("'CLAIMED'"));
        assertTrue(statements.get(1).contains("x.finished_at is null"));
    }
}
