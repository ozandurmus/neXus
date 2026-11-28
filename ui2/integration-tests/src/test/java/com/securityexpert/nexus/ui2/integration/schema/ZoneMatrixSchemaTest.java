package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.zonematrix.JooqZoneMatrixRepository;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Refused;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V216 and the delivery-step-2 migration (docs/design/ZONE_MATRIX_CONTRACT.md, FROZEN 2026-11-13) against a real
 * PostgreSQL. Synthetic data only.
 *
 * <p>The step-2 migration waits in {@code db/pending} until the ship order gives it a version number; until then this
 * test applies it after Flyway, and once it is numbered and in {@code db/migration} Flyway has applied it already.</p>
 */
class ZoneMatrixSchemaTest {
    private static Ui2PostgresFixture fixture;
    private static final String STEP_TWO = "zone_matrix_cells.sql";

    @BeforeAll static void migrate() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("zone_matrix");
        try (Connection db = fixture.migrateConnection(); var s = db.createStatement()) {
            try (var rows = s.executeQuery("SELECT to_regclass('zone_matrix_reference_confirmation') IS NULL")) {
                rows.next();
                if (rows.getBoolean(1)) s.execute(stepTwoSql());
            }
        }
    }

    /** The step-2 migration's text, from db/migration once numbered, else from db/pending. */
    private static String stepTwoSql() throws java.io.IOException {
        java.nio.file.Path migrations = Ui2PostgresFixture.migrationDirectory();
        for (java.nio.file.Path dir : List.of(migrations, migrations.resolveSibling("pending")))
            try (var files = java.nio.file.Files.list(dir)) {
                var found = files.filter(f -> f.getFileName().toString().endsWith("__" + STEP_TWO)).findFirst();
                if (found.isPresent()) return java.nio.file.Files.readString(found.get());
            }
        throw new IllegalStateException("the step-2 zone matrix migration is missing");
    }
    @AfterAll static void close() { if (fixture != null) fixture.close(); }

    private static final String ID = "00000000-0000-4000-8000-000000000001";
    private static final String SHA = "0".repeat(64);

    @Test void migrationIsDryRunnableInsideATransaction() throws Exception {
        try (Connection db = fixture.migrateConnection(); var statement = db.createStatement()) {
            String sql, stepThree;
            try (var resource = getClass().getResourceAsStream("/db/migration/V216__zone_matrix_versions.sql")) {
                assertNotNull(resource); sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            }
            try (var resource = getClass().getResourceAsStream("/db/migration/V228__zone_matrix_witness_access_log.sql")) {
                assertNotNull(resource); stepThree = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            }
            db.setAutoCommit(false);
            statement.execute("DROP TABLE zone_matrix_reference_confirmation, zone_matrix_access_log, zone_matrix_revision, "
                    + "zone_matrix_version CASCADE");
            statement.execute("DROP FUNCTION fn_zone_matrix_version_guard, fn_zone_matrix_append_only, "
                    + "fn_audit_capture_zone_matrix_revision, fn_audit_capture_zone_matrix_access");
            statement.execute(sql);
            statement.execute(stepTwoSql());
            statement.execute(stepThree);
            db.rollback();
        }
    }

    @Test void publishedVersionsAreImmutableAndHistoryIsAppendOnly() throws Exception {
        try (Connection db = fixture.appConnection(); var s = db.createStatement()) {
            db.setAutoCommit(false);
            s.execute("INSERT INTO zone_matrix_version (version_id,state,revision,content,content_sha256,created_by,updated_by) "
                    + "VALUES ('" + ID + "','DRAFT',1,'{}','" + SHA + "','synthetic-a','synthetic-a')");
            s.execute("INSERT INTO zone_matrix_revision (version_id,revision,actor,change_kind,content_sha256,content) "
                    + "VALUES ('" + ID + "',1,'synthetic-a','DRAFT_CREATE','" + SHA + "','{}')");
            s.execute("UPDATE zone_matrix_version SET state='ACTIVE', number=1, published_by='synthetic-b', published_at=now() WHERE version_id='" + ID + "'");
            s.execute("SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute("UPDATE zone_matrix_version SET content='{\"x\":1}' WHERE version_id='" + ID + "'"));
            s.execute("ROLLBACK TO SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM zone_matrix_version WHERE version_id='" + ID + "'"));
            s.execute("ROLLBACK TO SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute("UPDATE zone_matrix_revision SET actor='someone-else'"));
            s.execute("ROLLBACK TO SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM zone_matrix_revision"));
            s.execute("ROLLBACK TO SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO zone_matrix_version (version_id,state,revision,content,content_sha256,created_by,updated_by) "
                    + "VALUES ('00000000-0000-4000-8000-000000000002','ACTIVE',1,'{}','" + SHA + "','x','x')"));
            s.execute("ROLLBACK TO SAVEPOINT p");
            s.execute("UPDATE zone_matrix_version SET state='RETIRED' WHERE version_id='" + ID + "'");
            try (var rows = s.executeQuery("SELECT count(*), bool_and(after_state ? 'content_sha256'), bool_or(after_state ? 'content') "
                    + "FROM audit_log WHERE table_name='zone_matrix_revision' AND row_pk LIKE '" + ID + ":%'")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertTrue(rows.getBoolean(2)); assertFalse(rows.getBoolean(3));
            }
            db.rollback();
        }
    }

    @Test void theServiceRunsTheWholeStepOneFlowOnTheRealSchema() throws Exception {
        try (Connection db = fixture.appConnection()) {
            var repository = new JooqZoneMatrixRepository(new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            Set<String> compliance = Set.of("synthetic-compliance");
            var service = new ZoneMatrixService(repository, new ZoneMatrixService.Principals() {
                @Override public boolean holdsComplianceAdmin(String actor) { return compliance.contains(actor); }
                @Override public boolean isRoot(String actor) { return false; }
            }, new ObjectMapper(), Clock.systemUTC());
            try {
                var created = service.createZone("synthetic-compliance", null,
                        new ZoneMatrixService.ZoneInput("Z-APP", "", "APPLICATION", List.of("198.51.100.0/24", "2001:db8::/48")));
                long revision = ((Number) created.get("revision")).longValue();
                String sha = (String) created.get("content_sha256");
                Refused self = assertThrows(Refused.class, () -> service.publish("synthetic-compliance", false,
                        new ZoneMatrixService.PublishRequest(revision, sha, null)));
                assertEquals("SELF_APPROVAL", self.code);
                var published = service.publish("synthetic-security", false, new ZoneMatrixService.PublishRequest(revision, sha, null));
                assertEquals(1, published.get("number"));
                var lookup = service.lookup("synthetic-security", "198.51.100.9", null);
                assertEquals("Z-APP", lookup.get("zone_name"));
                assertEquals("UNASSIGNED", service.lookup("synthetic-security", "192.0.2.1", null).get("zone_name"));
                try (var s = db.createStatement(); var rows = s.executeQuery(
                        "SELECT count(*) FROM audit_log WHERE table_name='zone_matrix_access_log' AND action_id='zone_matrix_zones_read'")) {
                    assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
                }
                Map<String, Object> versions = Map.of("list", service.versions(ZoneMatrixService.View.NAMES, false));
                assertEquals(1, ((List<?>) versions.get("list")).size());
            } finally {
                cleanUp();
            }
        }
    }

    /** The app role cannot delete; the migration role (table owner) cleans up with the guards off. */
    private static void cleanUp() throws SQLException {
        try (Connection admin = fixture.migrateConnection(); var s = admin.createStatement()) {
            s.execute("ALTER TABLE zone_matrix_version DISABLE TRIGGER tr_zone_matrix_version_guard");
            s.execute("ALTER TABLE zone_matrix_revision DISABLE TRIGGER tr_zone_matrix_revision_append_only");
            s.execute("ALTER TABLE zone_matrix_access_log DISABLE TRIGGER tr_zone_matrix_access_log_append_only");
            s.execute("ALTER TABLE zone_matrix_reference_confirmation DISABLE TRIGGER tr_zone_matrix_reference_confirmation_append_only");
            s.execute("DELETE FROM zone_matrix_reference_confirmation; DELETE FROM zone_matrix_access_log; "
                    + "DELETE FROM zone_matrix_revision; DELETE FROM zone_matrix_version");
            s.execute("ALTER TABLE zone_matrix_version ENABLE TRIGGER tr_zone_matrix_version_guard");
            s.execute("ALTER TABLE zone_matrix_revision ENABLE TRIGGER tr_zone_matrix_revision_append_only");
            s.execute("ALTER TABLE zone_matrix_access_log ENABLE TRIGGER tr_zone_matrix_access_log_append_only");
            s.execute("ALTER TABLE zone_matrix_reference_confirmation ENABLE TRIGGER tr_zone_matrix_reference_confirmation_append_only");
        }
    }

    @Test void stepTwoChangeKindsConfirmationsAndTheirAuditAction() throws Exception {
        try (Connection db = fixture.appConnection(); var s = db.createStatement()) {
            db.setAutoCommit(false);
            s.execute("INSERT INTO zone_matrix_version (version_id,state,revision,content,content_sha256,created_by,updated_by) "
                    + "VALUES ('" + ID + "','DRAFT',2,'{}','" + SHA + "','synthetic-a','synthetic-a')");
            s.execute("INSERT INTO zone_matrix_revision (version_id,revision,actor,change_kind,content_sha256,content) "
                    + "VALUES ('" + ID + "',1,'synthetic-a','CELL_SET','" + SHA + "','{}')");
            s.execute("INSERT INTO zone_matrix_revision (version_id,revision,actor,change_kind,content_sha256,content) "
                    + "VALUES ('" + ID + "',2,'synthetic-c','REFERENCE_CONFIRM','" + SHA + "','{}')");
            s.execute("INSERT INTO zone_matrix_reference_confirmation (version_id,revision,source_zone,destination_zone,status,"
                    + "cell_sha256,confirmed_by,confirmed_at) VALUES ('" + ID + "',2,'UNASSIGNED','" + ID + "','CONFIRMED','"
                    + SHA + "','synthetic-c',now())");
            s.execute("SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO zone_matrix_revision (version_id,revision,actor,change_kind,"
                    + "content_sha256,content) VALUES ('" + ID + "',3,'synthetic-a','CELL_RENAME','" + SHA + "','{}')"));
            s.execute("ROLLBACK TO SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO zone_matrix_reference_confirmation (version_id,revision,"
                    + "source_zone,destination_zone,status,cell_sha256,confirmed_by,confirmed_at) VALUES ('" + ID + "',9,'UNASSIGNED',"
                    + "'UNASSIGNED','CONFIRMED','" + SHA + "','synthetic-c',now())"));
            s.execute("ROLLBACK TO SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO zone_matrix_reference_confirmation (version_id,revision,"
                    + "source_zone,destination_zone,status,cell_sha256,confirmed_by,confirmed_at) VALUES ('" + ID + "',2,'Z-APP',"
                    + "'UNASSIGNED','CONFIRMED','" + SHA + "','synthetic-c',now())"));
            s.execute("ROLLBACK TO SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute("UPDATE zone_matrix_reference_confirmation SET confirmed_by='someone-else'"));
            s.execute("ROLLBACK TO SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM zone_matrix_reference_confirmation"));
            s.execute("ROLLBACK TO SAVEPOINT p");
            try (var rows = s.executeQuery("SELECT action_id FROM audit_log WHERE table_name='zone_matrix_revision' AND row_pk LIKE '"
                    + ID + ":%' ORDER BY row_pk")) {
                assertTrue(rows.next()); assertEquals("zone_matrix_edit", rows.getString(1));
                assertTrue(rows.next()); assertEquals("zone_matrix_reference_confirm", rows.getString(1));
                assertFalse(rows.next());
            }
            db.rollback();
        }
    }

    @Test void theServiceRunsTheStepTwoFlowOnTheRealSchema() throws Exception {
        try (Connection db = fixture.appConnection()) {
            var repository = new JooqZoneMatrixRepository(new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            Set<String> compliance = Set.of("synthetic-compliance");
            var service = new ZoneMatrixService(repository, new ZoneMatrixService.Principals() {
                @Override public boolean holdsComplianceAdmin(String actor) { return compliance.contains(actor); }
                @Override public boolean isRoot(String actor) { return false; }
            }, new ObjectMapper(), Clock.systemUTC());
            try {
                var app = service.createZone("synthetic-security", null,
                        new ZoneMatrixService.ZoneInput("Z-APP", "", "APPLICATION", List.of("198.51.100.0/24")));
                var dbZone = service.createZone("synthetic-security", ((Number) app.get("revision")).longValue(),
                        new ZoneMatrixService.ZoneInput("Z-DB", "", "DATABASE", List.of("203.0.113.0/25")));
                var imported = service.importTemplate("synthetic-security", ((Number) dbZone.get("revision")).longValue(), false);
                String appId = (String) app.get("zone_id"), dbId = (String) dbZone.get("zone_id");
                var cell = service.setCell("synthetic-security", ((Number) imported.get("revision")).longValue(),
                        new ZoneMatrixService.CellInput(appId, dbId, "ALLOW_LISTED", List.of("tcp/5432"), "HIGH", null,
                                "Synthetic standard 1, article 2"));
                @SuppressWarnings("unchecked") var draft = (Map<String, Object>) service.draft(ZoneMatrixService.View.NAMES,
                        "synthetic-compliance", false, true).get("draft");
                @SuppressWarnings("unchecked") var cells = (List<Map<String, Object>>) ((Map<String, Object>) draft.get("matrix")).get("cells");
                String cellSha = (String) cells.stream().filter(c -> c.get("source").equals(appId) && c.get("destination").equals(dbId))
                        .findFirst().orElseThrow().get("cell_sha256");
                var confirmed = service.confirmReference("synthetic-compliance", false, ((Number) cell.get("revision")).longValue(),
                        appId, dbId, cellSha);
                long revision = ((Number) confirmed.get("revision")).longValue();
                String sha = (String) confirmed.get("content_sha256");
                assertEquals("SELF_APPROVAL", assertThrows(Refused.class, () -> service.publish("synthetic-compliance", false,
                        new ZoneMatrixService.PublishRequest(revision, sha, null))).code);
                var published = service.publish("synthetic-security-2", false, new ZoneMatrixService.PublishRequest(revision, sha, null));
                assertEquals(1, published.get("number"));
                repository.recordAccess("synthetic-security", "WITNESS_VIEW", (String) published.get("version_id"));
                try (var s = db.createStatement(); var rows = s.executeQuery("SELECT (SELECT count(*) FROM zone_matrix_reference_confirmation "
                        + "WHERE cell_sha256='" + cellSha + "'), (SELECT count(*) FROM audit_log WHERE action_id='zone_matrix_reference_confirm' "
                        + "AND table_name='zone_matrix_revision' AND NOT (after_state ? 'content')), "
                        + "(SELECT count(*) FROM audit_log WHERE action_id='zone_matrix_zones_read' "
                        + "AND table_name='zone_matrix_access_log' AND after_state->>'kind'='WITNESS_VIEW')")) {
                    assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals(1, rows.getInt(2)); assertEquals(1, rows.getInt(3));
                }
            } finally {
                cleanUp();
            }
        }
    }

    @Test void v278WidensFailureReasonCheckForHeapLimitAndIsDryRunnable() throws Exception {
        try (Connection db = fixture.migrateConnection(); var s = db.createStatement()) {
            try (var rows = s.executeQuery("SELECT convalidated, pg_get_constraintdef(oid) "
                    + "FROM pg_constraint WHERE conrelid = 'public.zone_matrix_run'::regclass "
                    + "AND conname = 'chk_zone_matrix_run_failure_reason'")) {
                assertTrue(rows.next(), "V278 named failure_reason CHECK constraint must exist");
                assertTrue(rows.getBoolean(1), "chk_zone_matrix_run_failure_reason must be validated");
                String def = rows.getString(2);
                assertTrue(def.contains("HEAP_LIMIT"), def);
                assertTrue(def.contains("TIME_LIMIT"), def);
                assertTrue(def.contains("STORE_ERROR"), def);
            }
            String v278;
            try (var resource = getClass().getResourceAsStream(
                    "/db/migration/V278__zone_matrix_run_heap_limit_failure_reason.sql")) {
                assertNotNull(resource);
                v278 = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            }
            db.setAutoCommit(false);
            s.execute(v278);
            db.rollback();
            db.setAutoCommit(true);
        }
        try (Connection db = fixture.appConnection(); var s = db.createStatement()) {
            db.setAutoCommit(false);
            s.execute("INSERT INTO zone_matrix_version (version_id, number, state, revision, content, content_sha256, "
                    + "created_by, updated_by, published_by, published_at) VALUES ('" + ID + "', 1, 'ACTIVE', 1, '{}', '"
                    + SHA + "', 'synthetic-a', 'synthetic-a', 'synthetic-b', now())");
            String runId = "00000000-0000-4000-8000-000000000276";
            s.execute("INSERT INTO zone_matrix_run (run_id, version_id, version_number, content_sha256, policy_source_id, "
                    + "engine_version, state, failure_reason, started_at, finished_at) VALUES ('" + runId + "', '" + ID
                    + "', 1, '" + SHA + "', 'pol-v276', 'step3-v1', 'FAILED', 'HEAP_LIMIT', now(), now())");
            s.execute("SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute(
                    "INSERT INTO zone_matrix_run (run_id, version_id, version_number, content_sha256, policy_source_id, "
                            + "engine_version, state, failure_reason, started_at, finished_at) VALUES "
                            + "('00000000-0000-4000-8000-000000000277', '" + ID + "', 1, '" + SHA
                            + "', 'pol-v276-bad', 'step3-v1', 'FAILED', 'UNKNOWN_CODE', now(), now())"));
            s.execute("ROLLBACK TO SAVEPOINT p");
            assertThrows(SQLException.class, () -> s.execute(
                    "INSERT INTO zone_matrix_run (run_id, version_id, version_number, content_sha256, policy_source_id, "
                            + "engine_version, state, failure_reason, started_at, finished_at) VALUES "
                            + "('00000000-0000-4000-8000-000000000278', '" + ID + "', 1, '" + SHA
                            + "', 'pol-v276-null', 'step3-v1', 'FAILED', NULL, now(), now())"),
                    "V267 (state = 'FAILED') = (failure_reason IS NOT NULL) constraint must stay intact");
            s.execute("ROLLBACK TO SAVEPOINT p");
            db.rollback();
        }
    }
}
