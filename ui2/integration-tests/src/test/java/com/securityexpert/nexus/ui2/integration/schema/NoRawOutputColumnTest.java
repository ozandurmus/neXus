package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Contract §7 test 9 / C1 §9 AC-6 check 7 / this contract's AC-7.
 *
 * <p>Statically enumerates every column {@code V1__initial_schema.sql}
 * creates and asserts none is named or commented as holding a raw device
 * transcript/response -- the {@code discard_raw} denylist pattern (design
 * §6.4) asserted at the schema layer. This is a text-level, container-free
 * test: it never opens a database connection.</p>
 */
class NoRawOutputColumnTest {

    /**
     * Mirrors the application-layer {@code discard_raw} denylist: any
     * column name containing one of these fragments would name or imply a
     * raw device transcript/response column.
     */
    private static final List<Pattern> DENYLIST = List.of(
            Pattern.compile("raw", Pattern.CASE_INSENSITIVE),
            Pattern.compile("transcript", Pattern.CASE_INSENSITIVE),
            Pattern.compile("response_body", Pattern.CASE_INSENSITIVE),
            Pattern.compile("output_bytes", Pattern.CASE_INSENSITIVE),
            Pattern.compile("output_text", Pattern.CASE_INSENSITIVE),
            Pattern.compile("command_output", Pattern.CASE_INSENSITIVE),
            Pattern.compile("stdout", Pattern.CASE_INSENSITIVE),
            Pattern.compile("stderr", Pattern.CASE_INSENSITIVE),
            Pattern.compile("device_output", Pattern.CASE_INSENSITIVE));

    @Test
    void noColumnInV1MatchesTheRawOutputDenylist() {
        Map<String, List<String>> tableColumns = SchemaV1Fixture.tableColumns();

        // AC-5: a rule proved over zero tables/columns is not proof of
        // anything -- fail loudly if parsing found nothing, rather than
        // silently "passing" a vacuous check.
        assertFalse(tableColumns.isEmpty(), "no CREATE TABLE statement was parsed out of V1__initial_schema.sql");
        assertTrue(tableColumns.values().stream().anyMatch(cols -> !cols.isEmpty()),
                "no column was parsed out of any CREATE TABLE statement in V1__initial_schema.sql");

        List<String> violations = new java.util.ArrayList<>();
        tableColumns.forEach((table, columns) -> columns.forEach(column -> {
            for (Pattern pattern : DENYLIST) {
                if (pattern.matcher(column).find()) {
                    violations.add(table + "." + column + " matches denylist pattern /" + pattern.pattern() + "/");
                }
            }
        }));

        assertTrue(violations.isEmpty(),
                "V1__initial_schema.sql declares a column that names or implies raw device output: " + violations);
    }

    @Test
    void exactlyTheNineContractTablesExist() {
        Map<String, List<String>> tableColumns = SchemaV1Fixture.tableColumns();

        List<String> expected = List.of(
                "devices", "endpoints", "credential_references", "provenance_records",
                "jobs", "job_steps", "cp_inventory_projection", "secrets_metadata", "audit_log");

        for (String table : expected) {
            assertTrue(tableColumns.containsKey(table),
                    "expected table " + table + " (C1 §3.1) to be created by V1__initial_schema.sql");
        }
        assertTrue(tableColumns.size() == expected.size(),
                "V1__initial_schema.sql creates " + tableColumns.keySet()
                        + " but the contract fixes exactly the nine tables of C1 §3.1: " + expected);
    }
}
