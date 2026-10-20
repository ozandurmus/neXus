package com.securityexpert.nexus.ui2.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/**
 * Argon review 2026-10-20 P2-3: releasing an orphan UNRESOLVED_DISPATCH fence (the run's failover_quarantine row is
 * already inactive, so no incident can carry the reason) still persists the release reason, on the run's
 * job_reconciliation row, in the same audited transaction; if it cannot be recorded, nothing is released.
 */
class FailoverOrphanReleaseReasonTest {
    private static final String ADMIN = "synthetic-security-admin";
    private static final String REASON = "Synthetic: member state confirmed on the console";

    private static final class Db implements TransactionBoundary {
        final List<String> sql = new ArrayList<>();
        final List<Object[]> bindings = new ArrayList<>();
        boolean activeIncident;
        int reconciliationRows = 1;
        final DSLContext dsl;
        Db() {
            var plain = DSL.using(SQLDialect.POSTGRES);
            dsl = DSL.using(new MockConnection(ctx -> {
                String q = ctx.sql();
                sql.add(q);
                bindings.add(ctx.bindings());
                String[][] rows = null;
                if (q.startsWith("select pg_try_advisory_xact_lock")) rows = new String[][] {{"acquired"}, {"true"}};
                else if (q.startsWith("select state,job_id from failover_run"))
                    rows = new String[][] {{"state", "job_id"}, {"STOPPED", "job-a"}};
                else if (q.startsWith("select cluster_ref from failover_quarantine"))
                    rows = activeIncident ? new String[][] {{"cluster_ref"}, {"unit-a"}} : new String[][] {{"cluster_ref"}};
                else if (q.startsWith("select 1 from jobs")) rows = new String[][] {{"one"}};
                else if (q.startsWith("select nonce from failover_dispatch_intent"))
                    rows = new String[][] {{"nonce"}, {"nonce-a"}};
                else if (q.startsWith("select ui2_release_failover_incident"))
                    rows = new String[][] {{"released"}, {"true"}};
                if (rows != null) return new MockResult[] { new MockResult(rows.length - 1, plain.fetchFromStringData(rows)) };
                if (q.startsWith("insert into job_reconciliation")) return new MockResult[] { new MockResult(reconciliationRows) };
                return new MockResult[] { new MockResult(1) };
            }), SQLDialect.POSTGRES);
        }
        @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(dsl); }
        JooqCpFailoverRepository repository() {
            return new JooqCpFailoverRepository(this, true,
                    new JobWindowPolicy(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC), 60));
        }
        int index(String prefix) {
            for (int i = 0; i < sql.size(); i++) if (sql.get(i).startsWith(prefix)) return i;
            return -1;
        }
    }

    @Test void theOrphanFenceReleaseRecordsTheReasonOnTheRunsJobAsStillUnknown() {
        var db = new Db();
        assertEquals(JooqCpFailoverRepository.INCIDENT_RELEASED, db.repository().releaseIncident("run-a", ADMIN, REASON));
        int intents = db.index("update failover_dispatch_intent set observation='OUTCOME_UNKNOWN'");
        int record = db.index("insert into job_reconciliation");
        assertTrue(intents >= 0 && record > intents, "the reason is recorded after the sends are marked OUTCOME_UNKNOWN");
        List<Object> bound = Arrays.asList(db.bindings.get(record));
        assertTrue(bound.containsAll(List.of("run-a", REASON, ADMIN)), "run, reason and the session's actor are bound");
        String statement = db.sql.get(record);
        assertTrue(statement.contains("'RECONCILED_STILL_UNKNOWN'"), "the release never claims the write's effect");
        assertTrue(statement.contains("observation='OUTCOME_UNKNOWN'"));
        assertTrue(db.sql.stream().anyMatch(s -> s.startsWith("SET LOCAL app.actor_fingerprint")),
                "written in the release's own audited transaction");
        assertEquals(-1, db.index("select ui2_release_failover_incident"), "there is no incident to release");
    }

    @Test void nothingIsReleasedWhenTheReasonCannotBeRecorded() {
        var db = new Db();
        db.reconciliationRows = 0;
        var refused = assertThrows(IllegalStateException.class, () -> db.repository().releaseIncident("run-a", ADMIN, REASON));
        assertEquals("INCIDENT_RELEASE_REASON_NOT_RECORDED", refused.getMessage());
    }

    @Test void anIncidentReleaseKeepsTheReasonOnTheIncidentAndWritesNoReconciliationRow() {
        var db = new Db();
        db.activeIncident = true;
        assertEquals(JooqCpFailoverRepository.INCIDENT_RELEASED, db.repository().releaseIncident("run-a", ADMIN, REASON));
        int release = db.index("select ui2_release_failover_incident");
        assertTrue(release >= 0);
        assertTrue(Arrays.asList(db.bindings.get(release)).contains(REASON));
        assertEquals(-1, db.index("insert into job_reconciliation"));
    }
}
