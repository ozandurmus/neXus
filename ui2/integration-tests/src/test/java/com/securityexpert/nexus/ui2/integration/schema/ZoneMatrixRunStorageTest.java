package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.zonematrix.JooqZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V267 zone-matrix runs and findings (docs/design/ZONE_MATRIX_CONTRACT.md sections 5.1, 5.9, 8.1 and 8.3, FROZEN
 * 2026-11-13; delivery step 4b-1) against a real PostgreSQL. Synthetic data only.
 */
class ZoneMatrixRunStorageTest {
    private static final String VERSION = "00000000-0000-4000-8000-0000000000a1";
    private static final String SHA = "a".repeat(64);
    private static final Instant STARTED = Instant.parse("2026-11-23T09:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-11-23T09:00:05Z");
    private static Ui2PostgresFixture fixture;

    @BeforeAll static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("zone_matrix_runs");
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            s.execute("INSERT INTO zone_matrix_version (version_id, number, state, revision, content, content_sha256, "
                    + "created_by, updated_by, published_by, published_at) VALUES ('" + VERSION + "', 1, 'ACTIVE', 1, "
                    + "'{}', '" + SHA + "', 'security-1', 'security-1', 'compliance-1', now())");
        }
    }

    @AfterAll static void close() { if (fixture != null) fixture.close(); }

    private static NewRun run(String runId, String policy) {
        return new NewRun(runId, VERSION, 1, SHA, policy, "step3-v1", STARTED);
    }

    private static Finding finding(String policy, String ruleUid, String outcome) {
        return new Finding(policy, ruleUid, List.of("00Parent", "parent-B"), List.of("ZONE-A->ZONE-B"), "r-0007",
                7, "RULE-SYNTHETIC-7", "sec-1", "Pre rules", List.of("FW-TANGO-04"), outcome,
                "VIOLATION".equals(outcome) ? null : "unresolved object", "HIGH", List.of("TIME_IGNORED"), null,
                List.of(new Cell("zone-a", "zone-b", "ALLOW_LISTED", "HIGH", List.of("tcp/22"), "original",
                        new Witness("192.0.2.16/28", "198.51.100.77/32", "tcp/22", 3221225488L, 3325256781L))),
                List.of(), "original", FINISHED, null, FINISHED, null, null);
    }

    private static final Counts COUNTS = new Counts(1, 3, 1, 0, 2, 2);
    private static final Coverage COVERAGE = new Coverage("INCOMPLETE", List.of("PAN_LOCAL_RULES_NOT_COLLECTED"), 0, true,
            "PAN", Instant.parse("2026-11-23T08:00:00Z"), 7);

    private static long scalar(Statement s, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    private static boolean bool(Statement s, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getBoolean(1);
        }
    }

    @Test void zm39_completeAndFindingsAreOneTransactionAndPartialFindingsNeverVisible() throws Exception {
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            ZoneMatrixRunRepository repository = new JooqZoneMatrixRunRepository(
                    new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            String policy = "pol-tx-" + UUID.randomUUID();
            String first = UUID.randomUUID().toString();
            repository.start(run(first, policy));
            assertEquals(List.of(), repository.findingsOf(first), "a RUNNING run shows no findings");
            List<Finding> findings = List.of(finding(policy, "uid-1", "VIOLATION"), finding(policy, "uid-2", "NOT_EVALUABLE"));
            assertTrue(repository.complete(first, FINISHED, 5_000L, COUNTS, COVERAGE, findings));
            Run complete = repository.latestComplete(policy).orElseThrow();
            assertEquals("COMPLETE", complete.state());
            assertEquals(COUNTS, complete.counts());
            assertEquals(List.of("PAN_LOCAL_RULES_NOT_COLLECTED"), complete.coverageItems());
            assertEquals(7, complete.snapshotRuleCount());
            assertEquals(findings, repository.findingsOf(first), "findings round-trip unchanged");

            // A write that fails on its second finding rolls back the first one too; the run stays RUNNING.
            String second = UUID.randomUUID().toString();
            repository.start(run(second, policy));
            List<Finding> broken = List.of(finding(policy, "uid-3", "VIOLATION"), finding(policy, "uid-4", "COMPLIANT"));
            assertThrows(RuntimeException.class,
                    () -> repository.complete(second, FINISHED, 1L, COUNTS, COVERAGE, broken));
            assertEquals(0, scalar(s, "SELECT count(*) FROM zone_matrix_finding WHERE run_id = '" + second + "'"));
            assertEquals(List.of(second), repository.runningRunOf(policy).stream().toList());
            assertEquals(first, repository.latestComplete(policy).orElseThrow().runId());
            assertTrue(repository.fail(second, FailureReason.STORE_ERROR, FINISHED));
            assertFalse(repository.complete(second, FINISHED, 1L, COUNTS, COVERAGE, findings),
                    "a FAILED run never gains findings");
            assertEquals(List.of(), repository.findingsOf(second));
            assertEquals(first, repository.latestComplete(policy).orElseThrow().runId());

            // The database itself refuses findings outside the completing transaction and any change to a finished run.
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO zone_matrix_finding (run_id, policy_source_id, "
                    + "rule_uid, inline_parent_path, violated_pairs, target_device_ids, outcome, flags, violated_cells, "
                    + "undecided_parts, first_observed_at, last_observed_at) VALUES ('" + first + "', '" + policy
                    + "', 'uid-9', '[]', '[]', '[]', 'VIOLATION', '[]', '[]', '[]', now(), now())"));
            assertThrows(SQLException.class, () -> s.execute(
                    "UPDATE zone_matrix_run SET state = 'FAILED', failure_reason = 'ENGINE_ERROR' WHERE run_id = '" + first + "'"));
            assertThrows(SQLException.class, () -> s.execute(
                    "UPDATE zone_matrix_finding SET rule_name = 'changed' WHERE run_id = '" + first + "'"));
        }
    }

    @Test void zm39_oneRunningRunPerPolicyAndStartupRecovery() throws Exception {
        try (Connection db = fixture.appConnection()) {
            ZoneMatrixRunRepository repository = new JooqZoneMatrixRunRepository(
                    new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            String policy = "pol-0042-Ab-" + UUID.randomUUID();
            String a = UUID.randomUUID().toString();
            repository.start(run(a, policy));
            RunningRunExists refused = assertThrows(RunningRunExists.class,
                    () -> repository.start(run(UUID.randomUUID().toString(), policy)));
            assertEquals(a, refused.existingRunId());
            // Differs by case only: a different opaque id (identity law), so it gets its own slot.
            String other = UUID.randomUUID().toString();
            repository.start(run(other, policy.toLowerCase(java.util.Locale.ROOT)));

            int interrupted = repository.failInterrupted(FINISHED);
            assertTrue(interrupted >= 2);
            for (String id : List.of(a, other)) {
                Run row = repository.runs(500).stream().filter(r -> r.runId().equals(id)).findFirst().orElseThrow();
                assertEquals("FAILED", row.state());
                assertEquals("INTERRUPTED_AT_STARTUP", row.failureReason());
            }
            assertTrue(repository.latestComplete(policy).isEmpty(), "an interrupted run is never read");
            assertTrue(repository.runningRunOf(policy).isEmpty(), "the slot is free again");
            String again = UUID.randomUUID().toString();
            repository.start(run(again, policy));
            assertEquals(List.of(again), repository.runningRunOf(policy).stream().toList());
        }
    }

    @Test void noDeleteGrantAndLifecycleColumnsOnlyAreUpdatable() throws Exception {
        try (Connection admin = fixture.adminDatabaseConnection(); Statement s = admin.createStatement()) {
            for (String role : List.of("ui2_app", "ui2_service")) {
                for (String table : List.of("zone_matrix_run", "zone_matrix_finding")) {
                    assertFalse(bool(s, "SELECT has_table_privilege('" + role + "', 'public." + table + "', 'DELETE')"),
                            role + " " + table);
                    assertTrue(bool(s, "SELECT has_table_privilege('" + role + "', 'public." + table + "', 'SELECT')"));
                    assertTrue(bool(s, "SELECT has_table_privilege('" + role + "', 'public." + table + "', 'INSERT')"));
                }
                assertTrue(bool(s, "SELECT has_table_privilege('" + role + "', 'public.zone_matrix_run', 'UPDATE')"));
                assertFalse(bool(s, "SELECT has_table_privilege('" + role + "', 'public.zone_matrix_finding', 'UPDATE')"));
                for (String column : List.of("first_observed_any_version", "last_observed_at", "resolved_at",
                        "resolution_reason")) {
                    assertTrue(bool(s, "SELECT has_column_privilege('" + role + "', 'public.zone_matrix_finding', '"
                            + column + "', 'UPDATE')"), role + " " + column);
                }
                for (String column : List.of("run_id", "rule_uid", "outcome", "violated_cells", "first_observed_at")) {
                    assertFalse(bool(s, "SELECT has_column_privilege('" + role + "', 'public.zone_matrix_finding', '"
                            + column + "', 'UPDATE')"), role + " " + column);
                }
            }
            for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                if (role.equals("ui2_service")) continue;
                for (String table : List.of("zone_matrix_run", "zone_matrix_finding")) {
                    assertFalse(bool(s, "SELECT has_table_privilege('" + role + "', 'public." + table + "', 'SELECT')"),
                            role + " " + table);
                }
            }
        }
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            SQLException denied = assertThrows(SQLException.class,
                    () -> s.execute("DELETE FROM zone_matrix_finding WHERE false"));
            assertEquals("42501", denied.getSQLState());
            denied = assertThrows(SQLException.class, () -> s.execute("DELETE FROM zone_matrix_run WHERE false"));
            assertEquals("42501", denied.getSQLState());
        }
    }

    @Test void identityTextIsStoredByteExact() throws Exception {
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            ZoneMatrixRunRepository repository = new JooqZoneMatrixRunRepository(
                    new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            String policy = "00Pol-" + UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
            String runId = UUID.randomUUID().toString();
            repository.start(run(runId, policy));
            String uid = "0001aBc-\"quoted\"\\path\u00e7";
            Finding exact = new Finding(policy, uid, List.of("007", "0007"), List.of("Zone-A->ZONE-a"), "0009",
                    9, "Rule \"nine\"", "00sec", "Pre rules", List.of("FW-TANGO-04", "fw-tango-04"), "UNKNOWN",
                    "analysis budget", null, List.of(), "000123", List.of(), List.of("IPv6 part"), null, FINISHED, null,
                    FINISHED, null, null);
            assertTrue(repository.complete(runId, FINISHED, 1L, new Counts(0, 0, 0, 1, 0, 1), COVERAGE, List.of(exact)));
            assertEquals(List.of(exact), repository.findingsOf(runId));
            assertEquals(1, scalar(s, "SELECT count(*) FROM zone_matrix_finding WHERE run_id = '" + runId
                    + "' AND rule_uid = '0001aBc-\"quoted\"\\path\u00e7' AND policy_source_id = '" + policy + "'"));
            assertEquals(0, scalar(s, "SELECT count(*) FROM zone_matrix_finding WHERE run_id = '" + runId
                    + "' AND (rule_uid = '1abc' OR policy_source_id = '" + policy.toLowerCase(java.util.Locale.ROOT) + "')"));
            assertEquals(policy, repository.latestComplete(policy).orElseThrow().policySourceId());
            assertTrue(repository.latestComplete(policy.toLowerCase(java.util.Locale.ROOT)).isEmpty());
        }
    }

    @Test void migrationIsDryRunnableInsideATransaction() throws Exception {
        String sql;
        try (var resource = getClass().getResourceAsStream("/db/migration/V267__zone_matrix_runs_and_findings.sql")) {
            assertNotNull(resource);
            sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection db = fixture.migrateConnection(); Statement s = db.createStatement()) {
            db.setAutoCommit(false);
            s.execute("DROP TABLE zone_matrix_finding, zone_matrix_run");
            s.execute("DROP FUNCTION fn_zone_matrix_run_guard, fn_zone_matrix_finding_insert_guard");
            s.execute(sql);
            db.rollback();
            db.setAutoCommit(true);
            assertTrue(bool(s, "SELECT to_regclass('zone_matrix_finding') IS NOT NULL"));
        }
    }
}
