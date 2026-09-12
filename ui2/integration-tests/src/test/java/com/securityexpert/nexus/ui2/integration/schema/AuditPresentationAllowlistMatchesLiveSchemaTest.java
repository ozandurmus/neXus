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
import com.securityexpert.nexus.ui2.service.audit.AuditPresentationAllowlist;

/**
 * Contract §8 test 13 / AC-4: enumerates, from the live database, every
 * column of every {@code trg_audit_*} table and fails unless the committed
 * {@link AuditPresentationAllowlist} equals that set minus
 * {@code audit_redaction_policy}'s columns. Proves the allowlist cannot
 * drift into hiding a legitimately new column or into listing a removed
 * one.
 */
class AuditPresentationAllowlistMatchesLiveSchemaTest {

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_allowlist_matches_schema");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void allowlistEqualsLiveAuditedColumnsMinusPolicyColumns() throws SQLException {
        try (Connection migrate = fixture.migrateConnection()) {
            Set<String> liveAuditedColumns = auditedColumns(migrate);
            assertFalse(liveAuditedColumns.isEmpty(), "no audited column was enumerated -- vacuous");

            Set<String> policyColumns = redactedColumns(migrate);
            assertFalse(policyColumns.isEmpty(), "no policy column was enumerated -- vacuous");

            Set<String> expectedAllowlist = new TreeSet<>(liveAuditedColumns);
            expectedAllowlist.removeAll(policyColumns);

            Set<String> committedAllowlist = new TreeSet<>();
            for (String table : AuditPresentationAllowlist.auditedTables()) {
                for (String column : AuditPresentationAllowlist.columnsOf(table)) {
                    committedAllowlist.add(table + "." + column);
                }
            }

            Set<String> missingFromAllowlist = new TreeSet<>(expectedAllowlist);
            missingFromAllowlist.removeAll(committedAllowlist);
            assertTrue(missingFromAllowlist.isEmpty(),
                    "column(s) live and unredacted but missing from the committed allowlist "
                            + "(a new column not yet classified): " + missingFromAllowlist);

            Set<String> extraInAllowlist = new TreeSet<>(committedAllowlist);
            extraInAllowlist.removeAll(expectedAllowlist);
            assertTrue(extraInAllowlist.isEmpty(),
                    "column(s) in the committed allowlist that are redacted or no longer live "
                            + "(a stale allowlist entry): " + extraInAllowlist);

            assertEquals(expectedAllowlist, committedAllowlist);
        }
    }

    /**
     * Non-vacuity proof: inject a real unclassified column (present live,
     * unredacted, and absent from the allowlist) and observe the exact
     * failure mode the test above would report, then remove it and observe
     * the pass.
     */
    @Test
    void proofOfNonVacuity_injectingARealUnclassifiedColumnIsDetected() throws SQLException {
        try (Connection migrate = fixture.migrateConnection()) {
            try (Statement statement = migrate.createStatement()) {
                statement.execute("ALTER TABLE devices ADD COLUMN test_injected_gap TEXT");
            }
            try {
                Set<String> liveAuditedColumns = auditedColumns(migrate);
                Set<String> policyColumns = redactedColumns(migrate);
                Set<String> expectedAllowlist = new TreeSet<>(liveAuditedColumns);
                expectedAllowlist.removeAll(policyColumns);

                Set<String> committedAllowlist = new TreeSet<>();
                for (String table : AuditPresentationAllowlist.auditedTables()) {
                    for (String column : AuditPresentationAllowlist.columnsOf(table)) {
                        committedAllowlist.add(table + "." + column);
                    }
                }

                assertTrue(expectedAllowlist.contains("devices.test_injected_gap"),
                        "sanity: the injected column must be live and unredacted");
                assertFalse(committedAllowlist.contains("devices.test_injected_gap"),
                        "sanity: the committed allowlist must not already know about it");

                Set<String> missingFromAllowlist = new TreeSet<>(expectedAllowlist);
                missingFromAllowlist.removeAll(committedAllowlist);
                assertTrue(missingFromAllowlist.contains("devices.test_injected_gap"),
                        "the real gate must report the injected column as missing from the allowlist");
            } finally {
                try (Statement statement = migrate.createStatement()) {
                    statement.execute("ALTER TABLE devices DROP COLUMN test_injected_gap");
                }
            }

            // Reverted: the real assertion now passes again.
            Set<String> liveAuditedColumns = auditedColumns(migrate);
            Set<String> policyColumns = redactedColumns(migrate);
            Set<String> expectedAllowlist = new TreeSet<>(liveAuditedColumns);
            expectedAllowlist.removeAll(policyColumns);
            assertFalse(expectedAllowlist.contains("devices.test_injected_gap"),
                    "after reverting, the injected column must be gone");
        }
    }

    private static Set<String> auditedColumns(Connection connection) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT c.relname || '.' || a.attname "
                                + "FROM pg_trigger t "
                                + "JOIN pg_class c ON c.oid = t.tgrelid "
                                + "JOIN pg_attribute a ON a.attrelid = c.oid "
                                + "  AND a.attnum > 0 AND NOT a.attisdropped "
                                + "WHERE t.tgname LIKE 'trg_audit_%' AND NOT t.tgisinternal "
                                + "ORDER BY 1")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
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
}
