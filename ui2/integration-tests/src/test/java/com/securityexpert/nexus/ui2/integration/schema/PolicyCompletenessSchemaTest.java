package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * V163 (policy collection completeness, PO approval 2026-10-18): the seven approved gate rows against the fixture, the
 * revision and change-evidence tables with their constraints, and repeatability. Synthetic values only. Needs the
 * PostgreSQL 16 fixture (UI2_TEST_JDBC_URL or Testcontainers), as every test of this package.
 */
class PolicyCompletenessSchemaTest {
    private static final Set<String> GATES = Set.of("pan_policy_nat_rule_hit_count", "pan_policy_config_log_enqueue",
            "pan_policy_config_log_seed", "pan_policy_log_job_get", "cp_policy_show_changes", "cp_policy_show_task",
            "cp_policy_nat_rulebase_page100");
    private static final String HEX = "a".repeat(64);
    /** Every CHECK, PRIMARY KEY and UNIQUE constraint of V163 by its explicit name. */
    private static final java.util.Map<String, Set<String>> CONSTRAINTS = java.util.Map.of(
            "policy_revision", Set.of("policy_revision_pkey", "policy_revision_vendor_check", "policy_revision_container_kind_check", "policy_revision_revision_no_check", "policy_revision_change_state_check", "policy_revision_unknown_reason_check", "policy_revision_marker_kind_check", "policy_revision_marker_check", "policy_revision_rules_total_check", "policy_revision_nat_rules_check", "policy_revision_rules_added_check", "policy_revision_rules_modified_check", "policy_revision_rules_removed_check", "policy_revision_objects_added_check", "policy_revision_objects_modified_check", "policy_revision_objects_removed_check", "policy_revision_evidence_count_check", "policy_revision_job_container", "policy_revision_unknown_reason_pairing_check", "policy_revision_vendor_kind_check"),
            "policy_change_evidence", Set.of("policy_change_evidence_pkey", "policy_change_evidence_vendor_check", "policy_change_evidence_evidence_kind_check", "policy_change_evidence_entry_ref_check", "policy_change_evidence_time_basis_check", "policy_change_evidence_admin_ref_check", "policy_change_evidence_command_class_check", "policy_change_evidence_result_class_check", "policy_change_evidence_path_hmac_check", "policy_change_evidence_redaction_count_check", "policy_change_evidence_added_count_check", "policy_change_evidence_modified_count_check", "policy_change_evidence_deleted_count_check", "policy_change_evidence_object_changes_check", "policy_change_evidence_vendor_kind_check", "policy_change_evidence_time_basis_pairing_check"),
            "policy_change_cursor", Set.of("policy_change_cursor_change_source_check", "policy_change_cursor_last_receive_time_check", "policy_change_cursor_boundary_seqnos_check", "policy_change_cursor_pkey"));
    private static Ui2PostgresFixture fixture;

    @BeforeAll static void migrate() { fixture = Ui2PostgresFixture.createAndMigrate("policy_completeness"); }
    @AfterAll static void drop() { if (fixture != null) fixture.close(); }

    @Test void sevenSignedOffReadRowsMatchTheFixtureAndStayUnambiguous() throws Exception {
        var rows = new java.util.HashMap<String, com.securityexpert.nexus.ui2.capability.GateRow>();
        try (var stream = getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml")) {
            GateRegistryFixtureLoader.loadFromStream(stream).stream().filter(r -> GATES.contains(r.gateId())).forEach(r -> rows.put(r.gateId(), r));
        }
        assertEquals(GATES, rows.keySet());
        try (var db = fixture.migrateConnection()) {
            for (var row : rows.values()) {
                String id = row.gateId();
                for (String[] column : new String[][] {
                        {"vendor", row.vendor()}, {"platform_role_scope", row.platformRoleScope()}, {"shell_context", row.shellContext()},
                        {"transport_kind", row.transportKind()}, {"canonical_command_key", row.canonicalCommandKey()},
                        {"action_class", "read"}, {"sign_off_state", "SIGNED_OFF"}, {"timeout_s", String.valueOf(row.timeoutS())},
                        {"retry_rule", row.retryRule()}, {"max_frequency", row.maxFrequency()},
                        {"session_reuse_rule", row.sessionReuseRule()}, {"unsupported_behavior_ref", row.unsupportedBehaviorRef()},
                        {"secret_output_risk", row.secretOutputRisk()}, {"source_document_pointer", row.sourceDocumentPointer()}})
                    assertEquals(column[1], scalar(db, "select " + column[0] + "::text from gate_registry where gate_id='" + id + "'"), id + "." + column[0]);
            }
            assertEquals("0", scalar(db, "select count(*) from (select 1 from gate_registry group by vendor, platform_role_scope, "
                    + "shell_context, transport_kind, canonical_command_key having count(*) > 1) d"), "two gate rows share one canonical key");
            // P-2 repeats the firewall row's text under a different scope.
            assertEquals("1", scalar(db, "select count(*) from gate_registry p join gate_registry f on f.canonical_command_key = p.canonical_command_key "
                    + "where p.gate_id = 'pan_policy_config_log_enqueue' and f.gate_id = 'pan_syslog_collect_config_enqueue' "
                    + "and p.platform_role_scope = 'panorama' and f.platform_role_scope = 'pan_firewall'"));
        }
    }

    @Test void migrationIsRepeatableInsideOneRolledBackTransaction() throws Exception {
        try (var resource = getClass().getResourceAsStream("/db/migration/V163__policy_completeness.sql")) {
            assertNotNull(resource);
            String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                db.setAutoCommit(false);
                statement.execute(sql);
                statement.execute(sql);
                assertEquals("7", scalar(db, "select count(*) from gate_registry where gate_id in ('" + String.join("','", GATES) + "')"));
                db.rollback();
            }
        }
    }

    @Test void tablesHaveTheContractedColumnsAndTheAppRoleCanUseThem() throws Exception {
        var columns = java.util.Map.of(
                "policy_revision", Set.of("revision_id", "source_id", "vendor", "container_kind", "container_ref", "job_id", "observed_at",
                        "revision_no", "change_state", "unknown_reason", "marker_kind", "marker", "complete", "rules_total", "nat_rules",
                        "rules_added", "rules_modified", "rules_removed", "objects_added", "objects_modified", "objects_removed", "evidence_count"),
                "policy_change_evidence", Set.of("evidence_id", "source_id", "vendor", "evidence_kind", "container_ref", "job_id", "observed_at",
                        "entry_ref", "change_time", "time_basis", "admin_ref", "command_class", "result_class", "path_hmac", "redaction_count",
                        "added_count", "modified_count", "deleted_count", "object_changes", "object_changes_truncated"),
                "policy_change_cursor", Set.of("source_id", "change_source", "last_receive_time", "boundary_seqnos", "updated_job_id", "updated_at"));
        try (var db = fixture.migrateConnection()) {
            for (var entry : columns.entrySet()) {
                Set<String> live = new TreeSet<>();
                try (var statement = db.createStatement(); var result = statement.executeQuery(
                        "select column_name from information_schema.columns where table_schema='public' and table_name='" + entry.getKey() + "'")) {
                    while (result.next()) live.add(result.getString(1));
                }
                assertEquals(new TreeSet<>(entry.getValue()), live, entry.getKey());
                for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE"})
                    assertEquals("true", scalar(db, "select has_table_privilege('ui2_app', '" + entry.getKey() + "', '" + privilege + "')"), entry.getKey() + " " + privilege);
            }
            // No free-text column: nothing named after a log line, change detail, user or object name.
            assertEquals("0", scalar(db, "select count(*) from information_schema.columns where table_schema='public' and table_name in "
                    + "('policy_revision','policy_change_evidence','policy_change_cursor') and (column_name ~ '(log_text|raw|detail|user_name|object_name|rule_name|xpath)')"));
        }
    }

    @Test void everyConstraintCarriesItsExplicitName() throws Exception {
        try (var db = fixture.migrateConnection()) {
            for (var entry : CONSTRAINTS.entrySet()) {
                Set<String> live = new TreeSet<>();
                try (var statement = db.createStatement(); var result = statement.executeQuery(
                        "select conname from pg_constraint where conrelid = '" + entry.getKey() + "'::regclass and contype in ('c','p','u')")) {
                    while (result.next()) live.add(result.getString(1));
                }
                assertEquals(new TreeSet<>(entry.getValue()), live, entry.getKey());
            }
        }
    }

    @Test void revisionConstraintsAcceptTheContractShapesAndRefuseEverythingElse() throws Exception {
        String head = "insert into policy_revision(source_id, vendor, container_kind, container_ref, job_id, revision_no, change_state, "
                + "unknown_reason, marker_kind, marker, complete) values ";
        run(head + "('mds-1', 'check_point', 'domain', 'd1', 'j1', 1, 'UNKNOWN', 'NO_PREVIOUS_MARKER', 'CP_PUBLISHED_SESSION', "
                + "'{\"uid\":\"s1\",\"publish_time\":\"2026-10-18T06:00:00+0300\",\"posix\":2}'::jsonb, true)");
        run(head + "('pan-1', 'palo_alto', 'device_group', 'g1', 'j1', 2, 'CHANGED', null, 'PAN_CONTENT_VERSION', '{\"content_version\":\"" + HEX + "\"}'::jsonb, true)");
        run(head + "('mds-1', 'check_point', 'domain', 'd1', 'j2', 1, 'UNCHANGED', null, null, '{}'::jsonb, false)");
        // One row per (job, container).
        assertThrows(SQLException.class, () -> run(head + "('mds-1', 'check_point', 'domain', 'd1', 'j1', 1, 'UNCHANGED', null, null, '{}'::jsonb, true)"));
        // UNKNOWN needs a reason; a reason needs UNKNOWN; states and vendors are closed sets.
        assertThrows(SQLException.class, () -> run(head + "('a', 'check_point', 'domain', 'd', 'j9', 1, 'UNKNOWN', null, null, '{}'::jsonb, true)"));
        assertThrows(SQLException.class, () -> run(head + "('a', 'check_point', 'domain', 'd', 'j9', 1, 'CHANGED', 'SOME_REASON', null, '{}'::jsonb, true)"));
        assertThrows(SQLException.class, () -> run(head + "('a', 'check_point', 'domain', 'd', 'j9', 1, 'MAYBE', null, null, '{}'::jsonb, true)"));
        assertThrows(SQLException.class, () -> run(head + "('a', 'fortinet', 'domain', 'd', 'j9', 1, 'CHANGED', null, null, '{}'::jsonb, true)"));
        // A vendor and its container kind go together.
        assertThrows(SQLException.class, () -> run(head + "('a', 'palo_alto', 'domain', 'd', 'j9', 1, 'CHANGED', null, null, '{}'::jsonb, true)"));
        // The marker is an opaque token object: no other key may carry text into it.
        assertThrows(SQLException.class, () -> run(head + "('a', 'check_point', 'domain', 'd', 'j9', 1, 'CHANGED', null, null, '{\"name\":\"x\"}'::jsonb, true)"));
        assertThrows(SQLException.class, () -> run(head + "('a', 'check_point', 'domain', 'd', 'j9', 0, 'CHANGED', null, null, '{}'::jsonb, true)"));
        assertThrows(SQLException.class, () -> run("update policy_revision set rules_added = -1 where job_id = 'j1'"));
        try (var db = fixture.migrateConnection()) {
            assertEquals("1", scalar(db, "select count(*) from policy_revision where job_id = 'j1' and vendor = 'check_point' and rules_added is null"),
                    "an unmeasured count stays NULL, never zero");
        }
    }

    @Test void evidenceConstraintsKeepOnlyOpaqueDigestsAndClassesAndDropDuplicates() throws Exception {
        String head = "insert into policy_change_evidence(evidence_id, source_id, vendor, evidence_kind, container_ref, job_id, entry_ref, "
                + "change_time, time_basis, admin_ref, command_class, result_class, path_hmac) values ";
        run(head + "('e1', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', 'g1', 'j1', '10', timestamp '2026-10-18 06:00:01', 'DEVICE_LOCAL', '" + HEX + "', 'edit', 'Succeeded', '" + HEX + "')");
        run(head + "('e2', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j1', '11', null, null, 'masked:5', null, null, null)");
        run(head + "('e3', 'mds-1', 'check_point', 'CP_SHOW_CHANGES', 'd1', 'j1', 'sess-1', timestamp '2026-10-18 03:00:00', 'UTC', 'UNKNOWN', null, null, null)");
        // The same entry in the same container is one row, whatever job read it.
        assertThrows(SQLException.class, () -> run(head + "('e4', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', 'g1', 'j2', '10', null, null, 'UNKNOWN', null, null, null)"));
        assertThrows(SQLException.class, () -> run(head + "('e5', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j2', '11', null, null, 'UNKNOWN', null, null, null)"),
                "an entry without a container is deduplicated too");
        // A raw admin name is not an identity form; neither is a path in clear.
        assertThrows(SQLException.class, () -> run(head + "('e6', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j2', '12', null, null, 'synthetic-admin', null, null, null)"));
        assertThrows(SQLException.class, () -> run(head + "('e7', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j2', '13', null, null, 'UNKNOWN', null, null, '/config/shared')"));
        assertThrows(SQLException.class, () -> run(head + "('e8', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j2', '14', null, null, 'UNKNOWN', 'Edit Something', null, null)"));
        // The vendor and the evidence kind go together; a time needs its basis.
        assertThrows(SQLException.class, () -> run(head + "('e9', 'pan-1', 'palo_alto', 'CP_SHOW_CHANGES', null, 'j2', '15', null, null, 'UNKNOWN', null, null, null)"));
        assertThrows(SQLException.class, () -> run(head + "('e10', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j2', '16', timestamp '2026-10-18 06:00:01', null, 'UNKNOWN', null, null, null)"));
        assertThrows(SQLException.class, () -> run(head + "('e11', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j2', 'has space', null, null, 'UNKNOWN', null, null, null)"));
        // The cursor: one per source, verbatim device-local time.
        run("insert into policy_change_cursor(source_id, change_source, last_receive_time, boundary_seqnos, updated_job_id) "
                + "values ('pan-1', 'pan_config_log', '2026/10/18 06:00:01', '[\"10\"]'::jsonb, 'j1')");
        assertThrows(SQLException.class, () -> run("insert into policy_change_cursor(source_id, change_source, last_receive_time, updated_job_id) "
                + "values ('pan-1', 'pan_config_log', '2026/10/18 06:00:02', 'j2')"));
        assertThrows(SQLException.class, () -> run("insert into policy_change_cursor(source_id, change_source, last_receive_time, updated_job_id) "
                + "values ('pan-2', 'pan_config_log', 'yesterday', 'j2')"));
        assertThrows(SQLException.class, () -> run("insert into policy_change_cursor(source_id, change_source, last_receive_time, updated_job_id) "
                + "values ('pan-2', 'cp_something', '2026/10/18 06:00:02', 'j2')"));
    }

    private static String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static void run(String sql) throws SQLException {
        try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
