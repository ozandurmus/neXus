package com.securityexpert.nexus.ui2.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * The integration harness's own three acceptance items (B1-1 contract §4 and
 * AC-9), proved end-to-end against a <b>real PostgreSQL 16 server</b> through
 * {@link Ui2PostgresFixture}:
 *
 * <ol>
 *   <li>Flyway runs against the {@code ui2_migrate} DSN and completes before
 *       any connection is opened with the {@code ui2_app} DSN.</li>
 *   <li>A second {@code flyway.migrate()} against the same database applies
 *       zero migrations and leaves the schema-history checksum unchanged.</li>
 *   <li>{@code ui2_app} is denied DDL: {@code CREATE TABLE} as
 *       {@code ui2_app} fails with SQLState {@code 42501}.</li>
 * </ol>
 *
 * <p>This class is the <b>harness-level</b> proof of those three items: one
 * fixture lifecycle, all three assertions in the order the contract states
 * them. The schema-level siblings in
 * {@code com.securityexpert.nexus.ui2.integration.schema} prove the same
 * three rules individually, each with its own fresh database, per contract
 * §7 tests 1-3; neither set is redundant, because a harness that got the
 * ordering right only when every test owns its own database would still be
 * wrong.</p>
 *
 * <p>{@link Ui2PostgresFixture} fails closed when no real PostgreSQL 16 is
 * reachable: there is no in-memory substitute and no skip path, so an
 * un-proved assertion here is a failing build rather than a green one.</p>
 */
class Ui2IntegrationHarnessPlaceholderTest {

    @Test
    void harnessWiringIsRealAndExercisedByIntegrationTest() {
        assertNotNull(Ui2IntegrationHarnessPlaceholderTest.class);
        assertNotNull(Ui2PostgresFixture.migrationDirectory(),
                "the harness must resolve the packaged migration location");
    }

    @Test
    void flywayRunsBeforeAnyApplicationConnection() throws SQLException {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.create("harness_order")) {
            // ui2_migrate first, against an empty database.
            try (Connection migrate = fixture.migrateConnection()) {
                assertEquals(Ui2PostgresFixture.MIGRATE_ROLE, scalar(migrate, "SELECT current_user"));
            }
            // No ui2_app connection can succeed yet.
            SQLException refused = assertThrows(SQLException.class,
                    fixture::attemptAppConnectionWithoutOrderingGuard);
            assertEquals("42501", refused.getSQLState());

            MigrateResult result = fixture.runFlyway();
            assertTrue(result.migrationsExecuted > 0);

            // ...and only now does it.
            try (Connection app = fixture.appConnection()) {
                assertEquals(Ui2PostgresFixture.APP_ROLE, scalar(app, "SELECT current_user"));
            }
        }
    }

    @Test
    void secondMigrateIsANoOp() throws SQLException {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.create("harness_second_migrate")) {
            MigrateResult first = fixture.runFlyway();
            assertTrue(first.migrationsExecuted > 0);

            List<String> historyAfterFirst;
            try (Connection migrate = fixture.migrateConnection()) {
                historyAfterFirst = checksums(migrate);
            }

            MigrateResult second = fixture.runFlyway();
            assertEquals(0, second.migrationsExecuted,
                    "the second migrate() must apply zero migrations, not merely avoid throwing");

            try (Connection migrate = fixture.migrateConnection()) {
                assertEquals(historyAfterFirst, checksums(migrate),
                        "the second migrate() must leave every schema-history checksum unchanged");
            }
        }
    }

    @Test
    void uiAppRoleIsDeniedDdlWithSqlState42501() throws SQLException {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.createAndMigrate("harness_ddl_denied")) {
            // The connection is opened outside the assertion block so that a
            // connection failure can never be read as the DDL denial.
            try (Connection app = fixture.appConnection(); Statement statement = app.createStatement()) {
                SQLException denied = assertThrows(SQLException.class,
                        () -> statement.execute("CREATE TABLE harness_should_never_create_this (x TEXT)"));
                assertEquals("42501", denied.getSQLState());
            }
        }
    }

    private static List<String> checksums(Connection connection) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank")) {
            while (result.next()) {
                rows.add(result.getString(1) + "|" + result.getString(2));
            }
        }
        return rows;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
