package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.persistence.runtime.ModuleRuntimeRepository;

/**
 * V262 (PO 2026-11-23): a worker liveness write that changes only telemetry columns is no longer audited; every
 * ownership, drain and job lifecycle change still is, and a write without an audit context still fails closed.
 */
class AuditTelemetryNarrowingTest {

    private static Ui2PostgresFixture fixture;
    private static TransactionBoundary tx;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.create("audit_telemetry");
        fixture.runFlyway();
        tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
    }

    @AfterAll
    static void close() {
        if (fixture != null) fixture.close();
    }

    @Test
    void moduleHeartbeatAndPoolMetricsAreSkippedButOwnershipAndDrainAreAudited() throws SQLException {
        var runtime = new ModuleRuntimeRepository(tx);
        long start = audit("module_runtime_control", "general");

        assertTrue(runtime.heartbeat("general", "general-a"));
        assertEquals(start + 1, audit("module_runtime_control", "general"), "taking ownership is audited");

        assertTrue(runtime.heartbeat("general", "general-a"));
        runtime.poolStats("general", "general-a", 1, 2, 0, 0, 0);
        runtime.poolStats("general", "general-a", 1, 2, 0, 0, 0);
        runtime.poolStats("general", "general-a", 3, 0, 1, 4, 5);
        assertEquals(start + 1, audit("module_runtime_control", "general"), "liveness and pool metrics are telemetry");

        audited("module_drain", "update module_runtime_control set drain_requested = true, "
                + "drain_generation = drain_generation + 1 where module = 'general'");
        assertEquals(start + 2, audit("module_runtime_control", "general"), "a drain request is audited");

        assertTrue(runtime.heartbeat("general", "general-a"));
        assertEquals(start + 3, audit("module_runtime_control", "general"), "the first drain acknowledgement is audited");
        assertTrue(runtime.heartbeat("general", "general-a"));
        assertEquals(start + 3, audit("module_runtime_control", "general"), "re-stamping the acknowledgement time is not");

        audited("module_drain", "update module_runtime_control set owner_heartbeat_at = now() where module = 'general'");
        assertEquals(start + 4, audit("module_runtime_control", "general"),
                "a telemetry column written under any other action is still audited");

        runtime.releaseOwnership("general", "general-a");
        assertEquals(start + 5, audit("module_runtime_control", "general"), "releasing ownership is audited");

        assertThrows(RuntimeException.class, () -> tx.inTransaction(db -> db.execute(
                "update module_runtime_control set pool_active = 9 where module = 'general'")),
                "a write without an audit context still fails closed");
    }

    @Test
    void jobHeartbeatIsSkippedButJobLifecycleIsAudited() throws SQLException {
        new JooqJobRecordDao(tx, JobWindowTestPolicy.PERMISSIVE).insertRequestedIfAbsentForRun("job-tel", "key-tel",
                "cp_policy_collect", "run-tel", "read", "cp_policy_collect", "synthetic-actor", "policy_collect").orElseThrow();
        assertEquals(1, audit("jobs", "job-tel"), "INSERT is audited");

        audited("policy_collect", "update jobs set state = 'EXECUTING', lease_epoch = 1, "
                + "lease_expires_at = now() + interval '1 hour' where job_id = 'job-tel'");
        assertEquals(2, audit("jobs", "job-tel"));

        audited("job_heartbeat", "update jobs set lease_expires_at = now() + interval '2 hours', last_heartbeat_at = now() "
                + "where job_id = 'job-tel'");
        audited("job_heartbeat", "update jobs set lease_expires_at = now() + interval '3 hours', last_heartbeat_at = now() "
                + "where job_id = 'job-tel'");
        assertEquals(2, audit("jobs", "job-tel"), "a heartbeat that only extends the lease is telemetry");

        audited("job_heartbeat", "update jobs set last_heartbeat_at = now(), cancel_requested = true where job_id = 'job-tel'");
        assertEquals(3, audit("jobs", "job-tel"), "any other column changed under the heartbeat action is audited");

        audited("job_lease_expire", "update jobs set lease_expires_at = now() where job_id = 'job-tel'");
        assertEquals(4, audit("jobs", "job-tel"), "a lease change by any other action is audited");
    }

    @Test
    void theOldTriggersAreDisabledAndTheNewOnesEnabled() throws SQLException {
        try (Connection migrate = fixture.migrateConnection(); Statement statement = migrate.createStatement();
                ResultSet rows = statement.executeQuery("select c.relname || '/' || t.tgname || '/' || t.tgenabled::text "
                        + "from pg_trigger t join pg_class c on c.oid = t.tgrelid where t.tgname in ("
                        + "'module_runtime_control_audit','module_runtime_control_audit_governance',"
                        + "'trg_audit_jobs','trg_audit_jobs_insert_delete','trg_audit_jobs_update') order by 1")) {
            var states = new java.util.ArrayList<String>();
            while (rows.next()) states.add(rows.getString(1));
            assertEquals(java.util.List.of("jobs/trg_audit_jobs/D", "jobs/trg_audit_jobs_insert_delete/O",
                    "jobs/trg_audit_jobs_update/O", "module_runtime_control/module_runtime_control_audit/D",
                    "module_runtime_control/module_runtime_control_audit_governance/O"), states);
        }
    }

    private static void audited(String action, String sql) {
        new AuditedTransactionBoundary(tx).inTransaction("synthetic-actor", action, db -> db.execute(sql));
    }

    private static long audit(String table, String pk) throws SQLException {
        try (Connection migrate = fixture.migrateConnection(); var statement = migrate.prepareStatement(
                "select count(*) from audit_log where table_name = ? and row_pk = ?")) {
            statement.setString(1, table);
            statement.setString(2, pk);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }
}
