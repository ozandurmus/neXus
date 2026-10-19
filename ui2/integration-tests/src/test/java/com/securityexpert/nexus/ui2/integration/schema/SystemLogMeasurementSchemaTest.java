package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.CollectionEngineRows;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogMeasurementRepository;

/** V151: gate rows, the diagnostics module mapping and the summary-only, lease-fenced, audited measurement table. */
class SystemLogMeasurementSchemaTest {
    @Test
    void migrationIsIdempotentMapsTheModuleAndStoresOnlyFencedAuditedSummaries() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("system_log_measurement")) {
            fixture.runFlyway();
            try (var resource = getClass().getResourceAsStream("/db/migration/V151__pan_syslog_measurement.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false); statement.execute(sql); statement.execute(sql); db.rollback();
                }
            }
            String device, job;
            try (var db = fixture.migrateConnection()) {
                assertEquals("diagnostics", scalar(db, "select ui2_job_module('pan_syslog_measure')"));
                assertEquals("diagnostics", scalar(db, "select ui2_job_module('diagnostic_read')"));
                assertEquals("inventory", scalar(db, "select ui2_job_module('pan_inventory_collect')"));
                assertNull(scalar(db, "select ui2_job_module('pan_syslog_collect')"));
                assertEquals("3", scalar(db, "select count(*) from gate_registry where gate_id like 'pan_syslog_measure_%' "
                        + "and sign_off_state='SIGNED_OFF' and action_class='read' and vendor='palo_alto' "
                        + "and platform_role_scope='pan_firewall' and transport_kind='PAN_XML_API'"));
                device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
                job = CollectionEngineRows.insertJobWithCapability(db, device, "pan_syslog_measure");
            }
            var repository = new JooqSystemLogMeasurementRepository(
                    new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            String summary = "{\"status\":\"MEASURED\",\"entries\":3,\"fieldNames\":[\"receive_time\"]}";
            assertFalse(repository.record(job, 1, device, "palo_alto", "pan_system", summary), "a REQUESTED job records nothing");
            try (var db = fixture.migrateConnection()) {
                // The claim fence admits the job only through its module mapping (V151).
                audited(db, "update jobs set state='CLAIMED', lease_worker_id='general-synthetic', lease_epoch=1, "
                        + "lease_expires_at=now()+interval '10 minutes' where job_id='" + job + "'");
                audited(db, "update jobs set state='EXECUTING' where job_id='" + job + "'");
            }
            assertFalse(repository.record(job, 2, device, "palo_alto", "pan_system", summary), "a stale epoch records nothing");
            assertTrue(repository.record(job, 1, device, "palo_alto", "pan_system", summary));
            assertFalse(repository.record(job, 1, device, "palo_alto", "pan_system", summary), "one row per job and source");
            assertTrue(repository.record(job, 1, device, "palo_alto", "pan_config",
                    "{\"status\":\"UNKNOWN\",\"reason\":\"LOG_JOB_NOT_FINISHED\"}"));
            assertThrows(RuntimeException.class, () -> repository.record(job, 1, device, "palo_alto", "pan_traffic", summary));
            String second;
            try (var db = fixture.migrateConnection()) {
                second = CollectionEngineRows.insertJobWithCapability(db, device, "pan_syslog_measure");
                audited(db, "update jobs set state='CLAIMED', lease_worker_id='general-synthetic', lease_epoch=1, "
                        + "lease_expires_at=now()+interval '10 minutes' where job_id='" + second + "'");
                audited(db, "update jobs set state='EXECUTING' where job_id='" + second + "'");
            }
            assertThrows(RuntimeException.class, () -> repository.record(second, 1, device, "palo_alto", "pan_system",
                    "{\"status\":\"MEASURED\",\"logs\":[\"synthetic-raw-marker\"]}"), "a log body is refused by the schema");
            assertThrows(RuntimeException.class, () -> repository.record(second, 1, device, "palo_alto", "pan_system",
                    "{\"entries\":1}"), "a summary without a typed status is refused");
            var rows = repository.list(Optional.of(device), 10);
            assertEquals(2, rows.size());
            assertTrue(rows.stream().anyMatch(r -> "pan_system".equals(r.source()) && r.summaryJson().contains("\"entries\": 3")));
            assertTrue(repository.list(Optional.of("dev-none"), 10).isEmpty());
            try (var db = fixture.migrateConnection()) {
                assertEquals("2", scalar(db, "select count(*) from audit_log where table_name='system_log_measurement'"));
                assertEquals("0", scalar(db, "select count(*) from system_log_measurement where summary::text like '%synthetic-raw-marker%'"));
            }
        }
    }

    /** V159: Check Point numeric summaries join the table; a vendor can only carry its own sources. */
    @Test
    void checkPointNumericSummariesAreAcceptedOnlyForCheckPointGateSources() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("system_log_measurement_cp")) {
            fixture.runFlyway();
            try (var resource = getClass().getResourceAsStream("/db/migration/V159__syslog_measurement_check_point.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false); statement.execute(sql); statement.execute(sql); db.rollback();
                }
            }
            String device, job;
            try (var db = fixture.migrateConnection()) {
                device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
                job = CollectionEngineRows.insertJobWithCapability(db, device, "diagnostic_read");
                audited(db, "update jobs set state='CLAIMED', lease_worker_id='general-synthetic', lease_epoch=1, "
                        + "lease_expires_at=now()+interval '10 minutes' where job_id='" + job + "'");
                audited(db, "update jobs set state='EXECUTING' where job_id='" + job + "'");
            }
            var repository = new JooqSystemLogMeasurementRepository(
                    new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            String summary = "{\"status\":\"MEASURED\",\"schema\":\"cp_syslog_numeric_v1\",\"kind\":\"stat\","
                    + "\"files\":[{\"class\":\"messages\",\"present\":true,\"sizeBytes\":48210123,\"mtimeEpoch\":1760699400}]}";
            assertTrue(repository.record(job, 1, device, "check_point", "cp_syslog_measure_stat", summary));
            assertFalse(repository.record(job, 1, device, "check_point", "cp_syslog_measure_stat", summary), "one row per job and source");
            assertThrows(RuntimeException.class, () -> repository.record(job, 1, device, "check_point", "pan_system", summary),
                    "a Check Point row cannot carry a Palo Alto source");
            assertThrows(RuntimeException.class, () -> repository.record(job, 1, device, "palo_alto", "cp_syslog_measure_ls_sa", summary),
                    "a Palo Alto row cannot carry a Check Point source");
            assertThrows(RuntimeException.class, () -> repository.record(job, 1, device, "check_point", "cp_syslog_measure_made_up", summary));
            assertThrows(RuntimeException.class, () -> repository.record(job, 1, device, "check_point", "cp_syslog_measure_ls_sa",
                    "{\"status\":\"MEASURED\",\"lines\":[\"synthetic-raw-marker\"]}"), "a raw body is refused by the schema");
            var rows = repository.list(Optional.of(device), Optional.of("check_point"), Optional.of("cp_syslog_measure_stat"), 10);
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).summaryJson().contains("48210123"));
            assertTrue(repository.list(Optional.of(device), Optional.of("palo_alto"), Optional.empty(), 10).isEmpty());
        }
    }

    /** V166: the Spike Detective layout review is a diagnostics job and its one summary row is a Check Point source. */
    @Test
    void theSpikeLayoutReviewIsADiagnosticsJobWhoseSummaryIsACheckPointSource() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("system_log_measurement_spike")) {
            fixture.runFlyway();
            try (var resource = getClass().getResourceAsStream("/db/migration/V166__spike_layout_review.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false); statement.execute(sql); statement.execute(sql); db.rollback();
                }
            }
            String device, job;
            try (var db = fixture.migrateConnection()) {
                assertEquals("diagnostics", scalar(db, "select ui2_job_module('cp_spike_layout_review')"));
                assertEquals("diagnostics", scalar(db, "select ui2_job_module('cp_system_log_collect')"));
                assertEquals("packet_analysis", scalar(db, "select ui2_job_module('packet_analysis_run')"));
                assertEquals("policy", scalar(db, "select ui2_job_module('cp_policy_collect')"));
                device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
                job = CollectionEngineRows.insertJobWithCapability(db, device, "cp_spike_layout_review");
                audited(db, "update jobs set state='CLAIMED', lease_worker_id='general-synthetic', lease_epoch=1, "
                        + "lease_expires_at=now()+interval '10 minutes' where job_id='" + job + "'");
                audited(db, "update jobs set state='EXECUTING' where job_id='" + job + "'");
            }
            var repository = new JooqSystemLogMeasurementRepository(
                    new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            String summary = "{\"status\":\"MEASURED\",\"schema\":\"cp_spike_layout_review_v1\",\"outcome\":\"REVIEWED\"}";
            assertThrows(RuntimeException.class, () -> repository.record(job, 1, device, "palo_alto", "cp_spike_layout_review", summary));
            assertTrue(repository.record(job, 1, device, "check_point", "cp_spike_layout_review", summary));
            assertFalse(repository.record(job, 1, device, "check_point", "cp_spike_layout_review", summary), "one row per job");
            assertEquals(1, repository.list(Optional.of(device), Optional.of("check_point"),
                    Optional.of("cp_spike_layout_review"), 10).size());
        }
    }

    private static String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static void audited(Connection db, String sql) throws SQLException {
        db.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (var statement = db.createStatement()) { statement.execute(sql); }
            db.commit();
        } catch (SQLException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(true);
        }
    }
}
