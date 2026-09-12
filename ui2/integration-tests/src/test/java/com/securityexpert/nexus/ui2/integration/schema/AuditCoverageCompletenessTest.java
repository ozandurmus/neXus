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
 * Contract §7 test 6 / C1 §3.5 "Test-enforced" fourth case, as amended by
 * {@code UI2_0_B1_ADJUDICATION_2026_09_12.md} finding F9, proved against a
 * real PostgreSQL 16 server.
 *
 * <p><b>F9, deliberately not "all eight tables":</b> the assertion is "every
 * mutation-bearing table in {@code information_schema.tables} carries
 * {@code trg_audit_<table>}, EXCEPT the documented exclusion list" — never a
 * hardcoded count and never a hardcoded expected table list. A future
 * migration that adds a mutation-bearing table without wiring its trigger
 * must fail this test the moment it lands.</p>
 */
class AuditCoverageCompletenessTest {

    /**
     * C3 §3.5 deliberately excludes {@code authz_decisions} and
     * {@code actor_authz_state} from the audit trigger set (adjudication
     * F9); {@code audit_log} excludes itself (C1 §3.5).
     *
     * <p>{@code flyway_schema_history} is excluded for a different reason
     * entirely: it is not a product table at all but Flyway's own
     * bookkeeping, written by {@code ui2_migrate} before any audit context
     * can exist. Wiring {@code fn_audit_capture} to it would make every
     * migration fail closed with {@code audit_context_missing}.</p>
     */
    static final Set<String> AUDIT_TRIGGER_EXCLUSIONS = Set.of(
            "audit_log", "authz_decisions", "actor_authz_state", "flyway_schema_history");

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_coverage");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void everyMutationBearingTableMinusTheExclusionListHasAnAuditTrigger() throws SQLException {
        try (Connection migrate = fixture.migrateConnection()) {
            Set<String> baseTables = baseTables(migrate);
            assertFalse(baseTables.isEmpty(), "no base table was enumerated -- a rule proved over zero "
                    + "tables proves nothing");

            Set<String> mustHaveTrigger = new TreeSet<>(baseTables);
            mustHaveTrigger.removeAll(AUDIT_TRIGGER_EXCLUSIONS);
            assertFalse(mustHaveTrigger.isEmpty(), "the exclusion list removed every table");

            Set<String> tablesWithAuditTrigger = tablesWithAuditTrigger(migrate);

            Set<String> missing = new TreeSet<>(mustHaveTrigger);
            missing.removeAll(tablesWithAuditTrigger);
            assertTrue(missing.isEmpty(),
                    "mutation-bearing table(s) with no trg_audit_<table>: " + missing
                            + " -- either wire the trigger, or amend this test's documented exclusion list "
                            + "from the owning contract");
        }
    }

    @Test
    void noExcludedTableCarriesAnAuditTriggerAfterAll() throws SQLException {
        // The exclusion list is a statement about the schema, so it must also
        // be checked in the other direction: an excluded table that silently
        // gained a trigger would mean the exclusion list is stale (and, for
        // flyway_schema_history, that migrations would fail closed).
        try (Connection migrate = fixture.migrateConnection()) {
            Set<String> withTrigger = tablesWithAuditTrigger(migrate);
            Set<String> unexpected = new TreeSet<>(AUDIT_TRIGGER_EXCLUSIONS);
            unexpected.retainAll(withTrigger);
            assertEquals(Set.of(), unexpected,
                    "a deliberately excluded table carries an audit trigger; the exclusion list is stale");
        }
    }

    private static Set<String> baseTables(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                                + "ORDER BY table_name")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }

    /**
     * Every table carrying a trigger named exactly {@code trg_audit_<table>}.
     * The name is matched against the table it is attached to, so a trigger
     * wired to the wrong table cannot satisfy this test.
     */
    private static Set<String> tablesWithAuditTrigger(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT DISTINCT event_object_table FROM information_schema.triggers "
                                + "WHERE trigger_schema = 'public' "
                                + "AND trigger_name = 'trg_audit_' || event_object_table")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }
}
