package com.securityexpert.nexus.ui2.persistence.jobrecords;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

class JobCancellationRepositoryTest {
    @Test void requestIsAtomicAndAuditedForQueuedClaimedAndExecutingJobs() {
        for (String state : List.of("CANCELLED", "EXECUTING")) {
            List<String> sql = new ArrayList<>(); List<Object> bindings = new ArrayList<>();
            var db = DSL.using(SQLDialect.POSTGRES);
            var tx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
                sql.add(context.sql()); bindings.addAll(Arrays.asList(context.bindings()));
                return new MockResult[] { new MockResult(1, context.sql().startsWith("update jobs")
                    ? db.fetchFromStringData(new String[] {"state"}, new String[] {state}) : null) };
            }), SQLDialect.POSTGRES));
            assertEquals(Optional.of(state), new JobCancellationRepository(tx).request("job-1", "synthetic-actor"));
            assertEquals("SET LOCAL app.actor_fingerprint = 'synthetic-actor'", sql.get(0));
            assertEquals("SET LOCAL app.action_id = 'job_cancel'", sql.get(1));
            assertTrue(sql.get(2).contains("cancel_requested = true"));
            assertTrue(sql.get(2).contains("case when state in ('REQUESTED','CLAIMED') then 'CANCELLED' else state end"));
            assertTrue(sql.get(2).contains("and state in ('REQUESTED','CLAIMED','EXECUTING') returning state"));
            assertEquals(List.of("job-1"), bindings);
        }
    }
    @Test void terminalOrMissingJobCannotBeCancelled() {
        var db = DSL.using(SQLDialect.POSTGRES);
        var tx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> new MockResult[] {
            new MockResult(0, context.sql().startsWith("update jobs") ? db.fetchFromStringData(new String[] {"state"}) : null)
        }), SQLDialect.POSTGRES));
        assertTrue(new JobCancellationRepository(tx).request("job-1", "synthetic-actor").isEmpty());
    }
    @Test void cancellationIsEpochScopedAndBlocksRequeueAndTerminalRaces() {
        List<String> sql = new ArrayList<>();
        var db = DSL.using(SQLDialect.POSTGRES);
        var tx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            return new MockResult[] { new MockResult(0, context.sql().startsWith("select")
                ? db.fetchFromStringData(new String[] {"job_id", "lease_epoch"}) : null) };
        }), SQLDialect.POSTGRES));
        var dao = new JooqJobLeaseDao(tx);
        assertFalse(dao.cancellationRequested("job-1", 2));
        dao.findExpiredCancellationRequests(); dao.findExpiredWithNoAttempt();
        dao.findExpiredAllBoundaryNo(); dao.findExpiredUnconfirmedYes();
        dao.transitionState("job-1", 2, "EXECUTING", "REQUESTED", "synthetic-actor", "test");
        assertTrue(sql.get(0).contains("lease_epoch = ? and cancel_requested and state = 'EXECUTING'"));
        assertTrue(sql.get(1).contains("cancel_requested and lease_expires_at < now()"));
        for (int i = 2; i < 5; i++) assertTrue(sql.get(i).contains("not j.cancel_requested"));
        assertTrue(sql.get(sql.size() - 1).contains("and (not cancel_requested or ? = 'CANCELLED')"));
    }
}
