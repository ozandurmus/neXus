package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * V186 (path analysis A3, PO record 2026-11-03 D-1): three tables only (no graph table), the override is audited and
 * its names, subnet and note are tier-2 redacted, snapshot and conflict rows are derived (no audit trigger), the D-1
 * device-deletion rule is in the schema (conflicts cascade, overrides are orphan-marked), and a re-run is a no-op.
 */
class PathAnalysisTopologySchemaTest {

    @Test
    void tablesTriggersDeletionRulesAndRedactionsAreAsDeclared() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("path_analysis_a3_topology")) {
            fixture.runFlyway();
            try (var db = fixture.migrateConnection()) {
                assertEquals("3", scalar(db, "select count(*) from information_schema.tables where table_name in "
                        + "('topology_snapshot','topology_conflict','topology_link_override')"));
                assertEquals("0", scalar(db, "select count(*) from information_schema.tables where table_name in "
                        + "('topology_node','topology_segment','topology_attachment','path_query_audit')"),
                        "D-1: the graph is never stored node by node; D-2: no path_query_audit table");
                assertEquals("1", scalar(db, "select count(*) from pg_trigger where tgname='trg_audit_topology_link_override'"));
                assertEquals("0", scalar(db, "select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid "
                        + "where c.relname in ('topology_snapshot','topology_conflict') and t.tgname like 'trg_audit_%'"));
                assertEquals("1", scalar(db, "select count(*) from pg_trigger where tgname='trg_topology_override_orphan_mark'"));
                String overrideFks = scalar(db, "select string_agg(pg_get_constraintdef(oid), ' ') from pg_constraint "
                        + "where conrelid='topology_link_override'::regclass and contype='f'");
                assertTrue(overrideFks.contains("ON DELETE SET NULL"), overrideFks);
                String conflictFks = scalar(db, "select string_agg(pg_get_constraintdef(oid), ' ') from pg_constraint "
                        + "where conrelid='topology_conflict'::regclass and contype='f'");
                assertTrue(conflictFks.contains("REFERENCES devices(device_id) ON DELETE CASCADE"), conflictFks);
                assertTrue(conflictFks.contains("REFERENCES topology_snapshot(snapshot_id) ON DELETE CASCADE"), conflictFks);
                for (String column : new String[] {"a_context", "a_interface_name", "b_context", "b_interface_name",
                        "assumed_subnet", "note"}) {
                    assertEquals("2", scalar(db, "select tier from audit_redaction_policy where "
                            + "table_name='topology_link_override' and column_name='" + column + "'"), column);
                }
                assertEquals("0", scalar(db, "select count(*) from information_schema.columns where table_name like "
                        + "'topology_%' and column_name in ('graph_json','manifest_json')"), "no JSONB graph blob");
                assertEquals("NO", scalar(db, "select is_grantable from information_schema.role_table_grants where "
                        + "grantee='ui2_app' and table_name='topology_link_override' and privilege_type='UPDATE'"));
                assertEquals("0", scalar(db, "select count(*) from information_schema.role_table_grants where "
                        + "grantee='ui2_app' and table_name='topology_link_override' and privilege_type='DELETE'"),
                        "an override is revoked, never deleted");
            }
            try (var resource = getClass().getResourceAsStream("/db/migration/V186__path_analysis_topology.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false);
                    statement.execute(sql);
                    assertEquals("8", scalar(db, "select count(*) from audit_redaction_policy where table_name in "
                            + "('topology_link_override','topology_conflict')"));
                    db.rollback();
                }
            }
        }
    }

    private static String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
