package com.securityexpert.nexus.ui2.integration.failover;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.sql.Connection;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobCancellationRepository;

/**
 * A readiness run whose job ended without a result never blocks its unit: V162 closes it with the job
 * transition, admission and the scheduler sweep close an older orphan, and the one-time repair SQL closes the
 * pre-V162 rows idempotently. Execution runs are outside all three. Synthetic rows only; no executor or device
 * transport is constructed.
 */
class ReadinessOrphanCloseTest {
    private static final String CLUSTER = "CLS-TEST-01";
    private static final String ACTOR = Ui2Rows.ACTOR;
    /** Gradle runs this module from ui2/integration-tests; the ops SQL lives at the repository root. */
    private static final java.nio.file.Path REPAIR_SQL = java.nio.file.Paths.get(System.getProperty("user.dir"))
        .getParent().getParent().resolve("docs/operations/sql/2026_10_18_close_orphan_readiness_runs.sql");

    private record Unit(Ui2PostgresFixture fixture, JooqCpFailoverRepository repository,
            JobCancellationRepository cancellation, String device, String peer) {}

    private static Unit unit(Ui2PostgresFixture fixture) throws Exception {
        String device, peer;
        try (var connection = fixture.appConnection()) {
            device = Ui2Rows.insertDevice(connection, Ui2Rows.insertCredentialReference(connection), "ENROLLED");
            Ui2Rows.insertEndpoint(connection, device);
            peer = Ui2Rows.insertDevice(connection, Ui2Rows.insertCredentialReference(connection), "ENROLLED");
            Ui2Rows.insertEndpoint(connection, peer);
            new AuditedTransactionBoundary(new JooqTransactionBoundary(DSL.using(connection, SQLDialect.POSTGRES)))
                .inTransaction(ACTOR, "synthetic_enrollment", dsl -> dsl.execute(
                    "update devices set vendor_hint='check_point',role='gateway',cluster_member_ref={0}", CLUSTER));
        }
        var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        return new Unit(fixture, new JooqCpFailoverRepository(boundary, true, JobWindowTestPolicy.PERMISSIVE),
            new JobCancellationRepository(boundary), device, peer);
    }

    private static Object one(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getObject(1);
        }
    }

    private static String jobOf(Unit u, String runId) {
        return u.repository().detail(runId).orElseThrow().run().jobId();
    }

    private static void assertClosedWithoutResult(Unit u, String runId) throws Exception {
        var run = u.repository().detail(runId).orElseThrow().run();
        assertEquals("STOPPED", run.state());
        assertEquals("UNKNOWN", run.outcome());
        assertEquals(JooqCpFailoverRepository.JOB_ENDED_WITHOUT_RESULT, run.message());
        assertNull(run.failedCheck());
        try (var admin = u.fixture().adminConnection()) {
            assertEquals(Boolean.TRUE, one(admin, "select r.finished_at = j.finished_at from failover_run r "
                + "join jobs j on j.job_id=r.job_id where r.run_id='" + runId + "'"), "The run ends when its job ended");
        }
    }

    /** Pre-V162 orphan: the job is cancelled with the trigger off, as the 2026-10-12 direct SQL left it. */
    private static void cancelWithoutTrigger(Unit u, String jobId) throws Exception {
        try (var admin = u.fixture().adminConnection()) {
            admin.setAutoCommit(false);
            try (var statement = admin.createStatement()) {
                statement.execute("alter table jobs disable trigger failover_readiness_job_terminal");
                Ui2Rows.setAuditContext(admin, ACTOR, "synthetic_direct_cancel");
                statement.execute("update jobs set state='CANCELLED',outcome='CANCELLED',terminal_reason='CANCELLED',"
                    + "finished_at=now() where job_id='" + jobId + "'");
                statement.execute("alter table jobs enable trigger failover_readiness_job_terminal");
            }
            admin.commit();
        }
    }

    @Test
    void cancellingAQueuedReadinessJobClosesItsRunInTheSameTransactionAndFreesTheUnit() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("readiness_orphan_trigger")) {
            var u = unit(fixture);
            var first = u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point");
            assertEquals("ADMITTED", first.code());
            assertEquals("RUN_ALREADY_ACTIVE",
                u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point").code(),
                "A queued job is a live run");
            assertFalse(u.repository().finished(first.runId()));

            assertEquals("CANCELLED", u.cancellation().request(jobOf(u, first.runId()), ACTOR).orElseThrow());
            assertTrue(u.repository().finished(first.runId()));
            assertClosedWithoutResult(u, first.runId());
            try (var admin = fixture.adminConnection()) {
                assertEquals("job_cancel", one(admin, "select action_id from audit_log where table_name='failover_run' "
                    + "and row_pk='" + first.runId() + "' order by audit_id desc limit 1"),
                    "The run's audit row carries the job transition's own action");
            }
            assertTrue(u.repository().readinessStatuses().stream().anyMatch(s -> "UNKNOWN".equals(s.outcome())
                && JooqCpFailoverRepository.JOB_ENDED_WITHOUT_RESULT.equals(s.stopCode())));

            var second = u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point");
            assertEquals("ADMITTED", second.code());
            assertNotEquals(first.runId(), second.runId());
        }
    }

    @Test
    void everyTerminalJobStateClosesAnOpenReadinessRunButACompletedRunKeepsItsResult() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("readiness_orphan_states")) {
            var u = unit(fixture);
            for (String terminal : new String[] {"FAILED", "REJECTED", "OUTCOME_UNKNOWN", "RECONCILED", "COMPLETED"}) {
                var run = u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point");
                assertEquals("ADMITTED", run.code(), terminal);
                try (var admin = fixture.adminConnection()) {
                    admin.setAutoCommit(false);
                    Ui2Rows.setAuditContext(admin, ACTOR, "synthetic_terminal_" + terminal.toLowerCase());
                    try (var statement = admin.createStatement()) {
                        statement.execute("update jobs set state='" + terminal + "',finished_at=now() where job_id='"
                            + jobOf(u, run.runId()) + "'");
                    }
                    admin.commit();
                }
                assertClosedWithoutResult(u, run.runId());
            }
            var done = u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point");
            u.repository().state(done.runId(), "DONE", "DONE", "READY", null, "READY");
            try (var admin = fixture.adminConnection()) {
                admin.setAutoCommit(false);
                Ui2Rows.setAuditContext(admin, ACTOR, "synthetic_terminal_completed");
                try (var statement = admin.createStatement()) {
                    statement.execute("update jobs set state='COMPLETED',finished_at=now() where job_id='"
                        + jobOf(u, done.runId()) + "'");
                }
                admin.commit();
            }
            var kept = u.repository().detail(done.runId()).orElseThrow().run();
            assertEquals("DONE", kept.state());
            assertEquals("READY", kept.outcome());
        }
    }

    @Test
    void anOrphanFromBeforeV162IsClosedByAdmissionAndByTheSweep() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("readiness_orphan_admission")) {
            var u = unit(fixture);
            var orphan = u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point");
            cancelWithoutTrigger(u, jobOf(u, orphan.runId()));
            assertFalse(u.repository().finished(orphan.runId()), "Without V162 the cancelled job leaves the run open");

            var admitted = u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point");
            assertEquals("ADMITTED", admitted.code(), "One orphan never blocks the unit");
            assertClosedWithoutResult(u, orphan.runId());

            var swept = u.repository().requestReadiness(CLUSTER, "7", ACTOR, u.device(), "check_point");
            cancelWithoutTrigger(u, jobOf(u, swept.runId()));
            assertEquals(1, u.repository().closeOrphanReadinessRuns());
            assertClosedWithoutResult(u, swept.runId());
            assertEquals(0, u.repository().closeOrphanReadinessRuns());
            assertFalse(u.repository().finished(admitted.runId()), "A run whose job is still queued stays open");
        }
    }

    @Test
    void executionRunsAreOutsideTheReadinessClose() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("readiness_orphan_execution")) {
            var u = unit(fixture);
            var members = Set.of(u.device(), u.peer());
            var approval = u.repository().createRequestApproval(UUID.randomUUID().toString(), CLUSTER, null, CLUSTER,
                members, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Synthetic approval", ACTOR,
                "check_point", JooqCpFailoverRepository.ADMIN_SINGLE);
            var execution = u.repository().requestBound(CLUSTER, null, Instant.now(), ACTOR, u.device(), true,
                "check_point", members, approval.approval().id(), approval.revision(), approval.executionNonce(), true,
                approval.policy());
            assertEquals("ADMITTED", execution.code());
            u.cancellation().request(jobOf(u, execution.runId()), ACTOR);
            assertEquals(0, u.repository().closeOrphanReadinessRuns());
            var run = u.repository().detail(execution.runId()).orElseThrow().run();
            assertEquals("FAILOVER", run.kind());
            assertNotEquals(JooqCpFailoverRepository.JOB_ENDED_WITHOUT_RESULT, run.message());
        }
    }

    @Test
    void theOneTimeRepairSqlClosesPreV162OrphansOnceAndTouchesNothingElse() throws Exception {
        String sql = Files.readString(REPAIR_SQL);
        try (var fixture = Ui2PostgresFixture.createAndMigrate("readiness_orphan_repair")) {
            var u = unit(fixture);
            var orphan = u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point");
            cancelWithoutTrigger(u, jobOf(u, orphan.runId()));
            var live = u.repository().requestReadiness(CLUSTER, "3", ACTOR, u.device(), "check_point");
            for (int pass = 1; pass <= 2; pass++) {
                try (var admin = fixture.adminConnection()) {
                    admin.setAutoCommit(false);
                    try (var statement = admin.createStatement()) { statement.execute(sql); }
                    admin.commit();
                }
                assertClosedWithoutResult(u, orphan.runId());
                assertFalse(u.repository().finished(live.runId()), "A queued job's run is not an orphan");
            }
            try (var admin = fixture.adminConnection()) {
                assertEquals(1L, one(admin, "select count(*) from audit_log where table_name='failover_run' "
                    + "and action_id='failover_readiness_orphan_repair' and row_pk='" + orphan.runId() + "'"),
                    "The second pass changed nothing");
            }
        }
    }

    @Test
    void theRepairSqlOutsideATransactionRefusesForLackOfAuditContext() throws Exception {
        String sql = Files.readString(REPAIR_SQL);
        try (var fixture = Ui2PostgresFixture.createAndMigrate("readiness_orphan_repair_autocommit")) {
            var u = unit(fixture);
            var orphan = u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point");
            cancelWithoutTrigger(u, jobOf(u, orphan.runId()));
            try (var admin = fixture.adminConnection(); var statement = admin.createStatement()) {
                admin.setAutoCommit(true);
                // One multi-statement execute is one implicit transaction in PostgreSQL's simple protocol, so
                // run statement by statement as psql does without BEGIN.
                for (String part : sql.replaceAll("(?m)^--.*$", "").split(";")) {
                    if (part.isBlank()) continue;
                    if (part.contains("UPDATE failover_run")) {
                        var refused = assertThrows(java.sql.SQLException.class, () -> statement.execute(part));
                        assertTrue(refused.getMessage().contains("audit_context_missing"));
                    } else statement.execute(part);
                }
            }
            assertFalse(u.repository().finished(orphan.runId()));
        }
    }
}
