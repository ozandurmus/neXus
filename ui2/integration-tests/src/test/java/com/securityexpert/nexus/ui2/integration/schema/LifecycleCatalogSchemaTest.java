package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

class LifecycleCatalogSchemaTest {
    private static Ui2PostgresFixture fixture;
    @BeforeAll static void migrate() { fixture = Ui2PostgresFixture.createAndMigrate("lifecycle_catalog"); }
    @AfterAll static void close() { if (fixture != null) fixture.close(); }

    @Test void catalogShipsEmptyAndMigrationCanBeDryRunTwiceThenRolledBack() throws Exception {
        try (Connection db = fixture.migrateConnection(); var statement = db.createStatement()) {
            try (var rows = statement.executeQuery("select count(*) from lifecycle_catalog")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            String sql;
            try (var resource = getClass().getResourceAsStream("/db/migration/V144__lifecycle_catalog.sql")) {
                assertNotNull(resource); sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            }
            db.setAutoCommit(false);
            statement.execute(sql); statement.execute(sql); db.rollback();
            try (var rows = statement.executeQuery("select count(*) from lifecycle_catalog")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            db.rollback();
        }
    }

    @Test void appMutationsRequireAuditContextReplaceTheSameKeyAndRedactProvenance() throws Exception {
        try (Connection db = fixture.appConnection(); var statement = db.createStatement()) {
            db.setAutoCommit(false);
            assertThrows(java.sql.SQLException.class, () -> statement.execute(insert("2030-01-01")));
            db.rollback();
            statement.execute("SET LOCAL app.actor_fingerprint = 'synthetic-actor'");
            statement.execute("SET LOCAL app.action_id = 'lifecycle_catalog_write'");
            statement.execute(insert("2030-01-01"));
            statement.execute(insert("2031-01-01") + " ON CONFLICT (vendor,kind,product) DO UPDATE SET end_of_support=excluded.end_of_support");
            try (var rows = statement.executeQuery("select count(*), max(end_of_support)::text from lifecycle_catalog")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals("2031-01-01", rows.getString(2));
            }
            try (var rows = statement.executeQuery("select count(*) from audit_log where table_name='lifecycle_catalog' "
                    + "and after_state->>'note' like 'sha256:%' and after_state->>'imported_by' like 'sha256:%'")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
            }
            statement.execute("DELETE FROM lifecycle_catalog WHERE catalog_id='synthetic-row'");
            db.rollback();
        }
    }

    private static String insert(String date) {
        return "INSERT INTO lifecycle_catalog (catalog_id,vendor,kind,product,end_of_support,source,note,imported_by) "
                + "VALUES ('synthetic-row','CHECKPOINT','HARDWARE','Example Appliance','" + date
                + "','IMPORT','Synthetic source','synthetic-actor')";
    }
}
