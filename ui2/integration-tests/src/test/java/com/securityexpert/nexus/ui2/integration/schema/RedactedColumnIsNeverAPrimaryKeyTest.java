package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * {@code UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md} Amendment A-1 / contract
 * §8 test 14: fails if any {@code audit_redaction_policy} column is the
 * primary key of its table. {@code fn_audit_capture} computes
 * {@code row_pk} from the <b>unredacted</b> row
 * ({@code v_row ->> v_pk_col}), so a redacted primary key would be
 * persisted in cleartext in {@code audit_log.row_pk} and rendered verbatim
 * by contract §3.1. No current policy column is a primary key; this test
 * is what keeps that true.
 */
class RedactedColumnIsNeverAPrimaryKeyTest {

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_redacted_never_pk");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void noDeclaredRedactionIsAPrimaryKeyColumn() throws SQLException {
        try (Connection migrate = fixture.migrateConnection()) {
            Set<String> redactedColumns = redactedColumns(migrate);
            assertFalse(redactedColumns.isEmpty(), "no redacted column was enumerated -- vacuous");

            Set<String> violations = new TreeSet<>();
            for (String tableColumn : redactedColumns) {
                String table = tableColumn.substring(0, tableColumn.indexOf('.'));
                String column = tableColumn.substring(tableColumn.indexOf('.') + 1);
                if (isPrimaryKeyColumn(migrate, table, column)) {
                    violations.add(tableColumn);
                }
            }

            assertTrue(violations.isEmpty(),
                    "a declared-redacted column is a primary key -- fn_audit_capture derives row_pk from "
                            + "the unredacted row, so this value would be persisted in cleartext in "
                            + "audit_log.row_pk: " + violations);
        }
    }

    /**
     * Non-vacuity proof: this test's own primary-key lookup is proved
     * against a column that genuinely IS a primary key (every audited
     * table's own PK, which is never itself declared redacted), so a
     * defect that always returned {@code false} would be caught here.
     */
    @Test
    void proofOfNonVacuity_theCheckDoesDetectARealPrimaryKey() throws SQLException {
        try (Connection migrate = fixture.migrateConnection()) {
            assertTrue(isPrimaryKeyColumn(migrate, "sessions", "session_id"),
                    "sanity: session_id is genuinely the primary key of sessions");
            assertFalse(isPrimaryKeyColumn(migrate, "sessions", "csrf_secret"),
                    "sanity: csrf_secret is genuinely NOT the primary key of sessions");
        }
    }

    private static Set<String> redactedColumns(Connection connection) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT table_name || '.' || column_name FROM audit_redaction_policy")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    private static boolean isPrimaryKeyColumn(Connection connection, String table, String column)
            throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT a.attname FROM pg_index i "
                                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                                + "WHERE i.indrelid = '" + table + "'::regclass AND i.indisprimary")) {
            while (rs.next()) {
                if (rs.getString(1).equals(column)) {
                    return true;
                }
            }
        }
        return false;
    }
}
