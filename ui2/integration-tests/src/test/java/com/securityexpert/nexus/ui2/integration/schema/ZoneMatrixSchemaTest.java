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

/** V216 (docs/design/ZONE_MATRIX_CONTRACT.md, FROZEN 2026-11-13) against a real PostgreSQL. Synthetic data only. */
class ZoneMatrixSchemaTest {
    private static Ui2PostgresFixture fixture;
    @BeforeAll static void migrate() { fixture = Ui2PostgresFixture.createAndMigrate("zone_matrix"); }
    @AfterAll static void close() { if (fixture != null) fixture.close(); }

    private static final String ID = "00000000-0000-4000-8000-000000000001";
    private static final String SHA = "0".repeat(64);

    @Test void migrationIsDryRunnableInsideATransaction() throws Exception {
        try (Connection db = fixture.migrateConnection(); var statement = db.createStatement()) {
            String sql;
            try (var resource = getClass().getResourceAsStream("/db/migration/V216__zone_matrix_versions.sql")) {
                assertNotNull(resource); sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            }
            db.setAutoCommit(false);
            statement.execute("DROP TABLE zone_matrix_access_log, zone_matrix_revision, zone_matrix_version CASCADE");
            statement.execute("DROP FUNCTION fn_zone_matrix_version_guard, fn_zone_matrix_append_only, "
                    + "fn_audit_capture_zone_matrix_revision, fn_audit_capture_zone_matrix_access");
            statement.execute(sql);
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
                // The app role cannot delete; the migration role (table owner) cleans up with the guards off.
                try (Connection admin = fixture.migrateConnection(); var s = admin.createStatement()) {
                    s.execute("ALTER TABLE zone_matrix_version DISABLE TRIGGER tr_zone_matrix_version_guard");
                    s.execute("ALTER TABLE zone_matrix_revision DISABLE TRIGGER tr_zone_matrix_revision_append_only");
                    s.execute("ALTER TABLE zone_matrix_access_log DISABLE TRIGGER tr_zone_matrix_access_log_append_only");
                    s.execute("DELETE FROM zone_matrix_access_log; DELETE FROM zone_matrix_revision; DELETE FROM zone_matrix_version");
                    s.execute("ALTER TABLE zone_matrix_version ENABLE TRIGGER tr_zone_matrix_version_guard");
                    s.execute("ALTER TABLE zone_matrix_revision ENABLE TRIGGER tr_zone_matrix_revision_append_only");
                    s.execute("ALTER TABLE zone_matrix_access_log ENABLE TRIGGER tr_zone_matrix_access_log_append_only");
                }
            }
        }
    }
}
