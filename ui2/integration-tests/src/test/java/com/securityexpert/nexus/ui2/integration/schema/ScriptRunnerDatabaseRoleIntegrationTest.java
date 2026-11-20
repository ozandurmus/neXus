package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobStepAttemptDao;
import com.securityexpert.nexus.ui2.persistence.runtime.ModuleRuntimeRepository;

/**
 * The broker's own database role {@code ui2_script_runner} (V258, {@code SCRIPT_EXECUTION_CONTRACT.md} Amendment
 * 2026-11-18 (b) item 5) proved on a real PostgreSQL 16: logged in as that role, the broker's whole database path works
 * (module heartbeat and release, claim, lease heartbeat, step attempt, every terminal transition, which fires the
 * jobs.state triggers), and the role reaches no script table, credential, session or audit row.
 */
class ScriptRunnerDatabaseRoleIntegrationTest {

    private static final String ROLE = "ui2_script_runner";

    private static String password() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        return "t" + HexFormat.of().formatHex(b);
    }

    private static void insertJob(Ui2PostgresFixture fixture, String jobId) throws SQLException {
        try (Connection app = fixture.appConnection()) {
            app.setAutoCommit(false);
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "fixture.script_run");
            try (Statement st = app.createStatement()) {
                st.executeUpdate("insert into jobs(job_id, job_type, capability_id, target_kind, target_ref, "
                        + "submitted_by_actor_fingerprint, submitted_at, idempotency_key, action_class, state) values ('"
                        + jobId + "', 'script_run', 'script_run', 'script', 'script-x@1', 'actor', now(), 'k-" + jobId
                        + "', 'read', 'REQUESTED')");
            }
            app.commit();
        }
    }

    @Test
    void theBrokerRoleRunsItsWholeDatabasePathAndReachesNothingElse() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("script_runner_role")) {
            String pw = password();
            try (Connection admin = fixture.adminDatabaseConnection(); Statement st = admin.createStatement()) {
                st.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + ROLE + "') THEN "
                        + "CREATE ROLE " + ROLE + " NOLOGIN; END IF; END $$");
                st.execute("ALTER ROLE " + ROLE + " LOGIN PASSWORD '" + pw + "'");
            }
            try (Connection migrate = fixture.migrateConnection(); Statement st = migrate.createStatement()) {
                st.execute("SELECT ui2_apply_script_runner_role_grants()");
            }
            insertJob(fixture, "job-script-role-1");
            insertJob(fixture, "job-script-role-2");

            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(fixture.databaseJdbcUrl());
            source.setUser(ROLE);
            source.setPassword(pw);
            var tx = new JooqTransactionBoundary(DSL.using(source, SQLDialect.POSTGRES));
            String worker = "script_runner-" + UUID.randomUUID();

            ModuleRuntimeRepository runtime = new ModuleRuntimeRepository(tx);
            assertTrue(runtime.heartbeat("script_runner", worker), "the broker owns its module row");
            var leases = new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE);
            var attempts = new JooqJobStepAttemptDao(tx);

            var first = leases.claimNext(worker, List.of("script_run"), Duration.ofSeconds(60)).orElseThrow();
            assertTrue(leases.heartbeat(first.jobId(), first.leaseEpoch(), Duration.ofSeconds(60)));
            assertTrue(leases.transitionState(first.jobId(), first.leaseEpoch(), "CLAIMED", "EXECUTING",
                    "system:worker", "script_run_running"));
            String attempt = attempts.insertPreContact(first.jobId(), first.leaseEpoch(), 0, "SCRIPT_RUN", "read", 1);
            assertTrue(attempts.writeOutcome(attempt, first.leaseEpoch(), "MATCHED", null, true, 10L, 0L, null));
            assertTrue(leases.transitionState(first.jobId(), first.leaseEpoch(), "EXECUTING", "COMPLETED",
                    "system:worker", "script_run_completed", "COMPLETED"));

            var second = leases.claimNext(worker, List.of("script_run"), Duration.ofSeconds(60)).orElseThrow();
            assertTrue(leases.transitionState(second.jobId(), second.leaseEpoch(), "CLAIMED", "EXECUTING",
                    "system:worker", "script_run_running"));
            assertTrue(leases.transitionState(second.jobId(), second.leaseEpoch(), "EXECUTING", "FAILED",
                    "system:worker", "script_run_failed", "EXIT_CODE_3"));
            assertTrue(runtime.heartbeat("script_runner", worker));
            runtime.releaseOwnership("script_runner", worker);

            try (Connection broker = source.getConnection(); Statement st = broker.createStatement()) {
                for (String table : List.of("script", "script_version", "script_run", "credentials",
                        "credential_references", "sessions", "audit_log", "backup_artefact", "local_credentials")) {
                    SQLException denied = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                            () -> st.executeQuery("select 1 from " + table + " limit 1"), table);
                    assertEquals("42501", denied.getSQLState(), table);
                }
            }
        }
    }
}
