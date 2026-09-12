package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Contract §7 test 2, proved against a real PostgreSQL 16 server: the
 * <b>second</b> {@code migrate()} against the same database reports
 * {@code migrationsExecuted == 0} — not merely that the call did not throw.
 * The schema-history checksums are additionally asserted unchanged
 * (B1-1 harness item 2), so a second apply that re-ran and re-recorded a
 * statement cannot pass.
 */
class FlywaySecondApplyIsNoOpTest {

    @Test
    void secondMigrateCallExecutesZeroMigrationsAndLeavesTheHistoryUnchanged() throws SQLException {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.create("second_apply")) {
            MigrateResult first = fixture.runFlyway();
            assertTrue(first.migrationsExecuted > 0,
                    "the first migrate() applied nothing, so a zero on the second would prove nothing");

            List<String> historyAfterFirst;
            try (Connection migrate = fixture.migrateConnection()) {
                historyAfterFirst = schemaHistory(migrate);
            }
            assertEquals(first.migrationsExecuted, historyAfterFirst.size(),
                    "every applied migration must be recorded exactly once in flyway_schema_history");

            MigrateResult second = fixture.runFlyway();
            assertEquals(0, second.migrationsExecuted,
                    "the second migrate() must apply zero migrations");

            try (Connection migrate = fixture.migrateConnection()) {
                assertEquals(historyAfterFirst, schemaHistory(migrate),
                        "the second migrate() must leave flyway_schema_history -- version, checksum and "
                                + "success flag -- byte-for-byte unchanged");
            }
        }
    }

    /** Version, checksum and success flag of every history row, in order. */
    private static List<String> schemaHistory(Connection connection) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT installed_rank, version, checksum, success FROM flyway_schema_history "
                                + "ORDER BY installed_rank")) {
            while (result.next()) {
                rows.add(result.getInt(1) + "|" + result.getString(2) + "|" + result.getString(3)
                        + "|" + result.getBoolean(4));
            }
        }
        return rows;
    }
}
