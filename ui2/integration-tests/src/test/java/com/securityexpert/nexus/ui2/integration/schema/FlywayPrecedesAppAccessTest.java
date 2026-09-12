package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * Contract §7 test 1, proved against a real PostgreSQL 16 server
 * ({@link Ui2PostgresFixture}).
 *
 * <p>"The harness opens the {@code ui2_migrate} connection, runs Flyway, and
 * only then opens a {@code ui2_app} connection; fails if any {@code ui2_app}
 * connection succeeds before Flyway's {@code migrate()} call returns."</p>
 *
 * <p>This class therefore drives the fixture itself rather than taking a
 * migrated database from a {@code @BeforeAll}: the pre-migration window is
 * the whole subject.</p>
 */
class FlywayPrecedesAppAccessTest {

    @Test
    void noUi2AppConnectionSucceedsBeforeFlywayMigrateReturns() throws SQLException {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.create("flyway_precedes")) {
            // 1. ui2_migrate can connect to the fresh, empty database.
            try (Connection migrate = fixture.migrateConnection()) {
                assertEquals(Ui2PostgresFixture.MIGRATE_ROLE, currentUser(migrate));
                assertEquals(0L, applicationTableCount(migrate),
                        "the fixture's database must be empty before Flyway runs");
            }

            // 2. ui2_app cannot connect at all yet -- asserted against the
            // server, with the fixture's own in-process ordering guard
            // bypassed, so this proves PostgreSQL refuses it rather than
            // that the fixture declined to try.
            SQLException refused = assertThrows(SQLException.class,
                    fixture::attemptAppConnectionWithoutOrderingGuard,
                    "a ui2_app connection succeeded before Flyway's migrate() returned");
            assertEquals("42501", refused.getSQLState(),
                    "the pre-migration ui2_app connection must be refused as insufficient_privilege");

            // 3. Flyway runs, and actually applies migrations -- a migrate()
            // that applied nothing would make the rest of this vacuous.
            MigrateResult result = fixture.runFlyway();
            assertTrue(result.migrationsExecuted > 0,
                    "Flyway applied zero migrations, so this test would prove nothing");

            // 4. Only now does a ui2_app connection succeed, and it sees the
            // schema Flyway created.
            try (Connection app = fixture.appConnection()) {
                assertEquals(Ui2PostgresFixture.APP_ROLE, currentUser(app));
                assertEquals(0L, count(app, "SELECT count(*) FROM devices"),
                        "ui2_app must be able to read the migrated schema once Flyway has returned");
            }
        }
    }

    @Test
    void theHarnessItselfRefusesAppAccessBeforeMigrate() throws SQLException {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.create("flyway_precedes_guard")) {
            // The in-process half of the same rule: no test can obtain a
            // ui2_app connection or DataSource from the fixture before
            // migrate() has returned, so the ordering cannot be broken by a
            // future test that simply forgets it.
            assertThrows(IllegalStateException.class, fixture::appConnection);
            assertThrows(IllegalStateException.class, fixture::appDataSource);

            fixture.runFlyway();
            try (Connection app = fixture.appConnection()) {
                assertEquals(Ui2PostgresFixture.APP_ROLE, currentUser(app));
            }
        }
    }

    private static String currentUser(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT current_user")) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static long applicationTableCount(Connection connection) throws SQLException {
        return count(connection, "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'");
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
