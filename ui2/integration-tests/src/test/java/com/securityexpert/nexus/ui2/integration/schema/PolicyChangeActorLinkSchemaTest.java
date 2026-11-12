package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * V208 (Palo Alto change actor from the Panorama config log; PO decision record 2026-11-12): the administrator name column is
 * restricted to a successful configuration-change entry that names a rule, the link table keeps its state/count pairing, and
 * its rows (which carry the candidate names) are deleted with the history row they explain. Synthetic values only. Needs the
 * PostgreSQL 16 fixture, as every test of this package.
 */
class PolicyChangeActorLinkSchemaTest {
    private static final String HEX = "a".repeat(64);
    private static Ui2PostgresFixture fixture;

    @BeforeAll static void migrate() { fixture = Ui2PostgresFixture.createAndMigrate("policy_change_actor_link"); }
    @AfterAll static void drop() { if (fixture != null) fixture.close(); }

    private static final String HEAD = "insert into policy_change_evidence(evidence_id, source_id, vendor, evidence_kind, container_ref, job_id, entry_ref, "
            + "change_time, time_basis, admin_ref, command_class, result_class, path_hmac, admin_name, rule_ref_hmac) values ";

    @Test void migrationIsRepeatableInsideOneRolledBackTransaction() throws Exception {
        try (var resource = getClass().getResourceAsStream("/db/migration/V208__policy_change_actor_link.sql")) {
            assertNotNull(resource);
            String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                db.setAutoCommit(false);
                statement.execute(sql);
                statement.execute(sql);
                assertEquals("1", scalar(db, "select count(*) from information_schema.tables where table_name = 'policy_change_actor_link'"));
                db.rollback();
            }
        }
    }

    @Test void anAdministratorNameIsOnlyAcceptedForASuccessfulChangeThatNamesARule() throws Exception {
        String when = "timestamp '2026-11-12 10:00:00', 'DEVICE_LOCAL', '" + HEX + "'";
        run(HEAD + "('n1', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j1', '1', " + when + ", 'edit', 'Succeeded', null, 'synthetic-admin', '" + HEX + "')");
        // A commit, a failed command, a rule-less entry, an unknown command class and a Check Point row never carry a name.
        assertThrows(SQLException.class, () -> run(HEAD + "('n2', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j1', '2', " + when + ", 'commit', 'Succeeded', null, 'synthetic-admin', '" + HEX + "')"));
        assertThrows(SQLException.class, () -> run(HEAD + "('n3', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j1', '3', " + when + ", 'edit', 'Failed', null, 'synthetic-admin', '" + HEX + "')"));
        assertThrows(SQLException.class, () -> run(HEAD + "('n4', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j1', '4', " + when + ", 'edit', 'Succeeded', null, 'synthetic-admin', null)"));
        assertThrows(SQLException.class, () -> run(HEAD + "('n5', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j1', '5', " + when + ", null, 'Succeeded', null, 'synthetic-admin', '" + HEX + "')"));
        assertThrows(SQLException.class, () -> run(HEAD + "('n6', 'mds-1', 'check_point', 'CP_SHOW_CHANGES', 'd1', 'j1', 'sess-1', timestamp '2026-11-12 10:00:00', 'UTC', 'UNKNOWN', null, null, null, 'synthetic-admin', '" + HEX + "')"));
        // Shape: 1 to 128 characters, no control characters; the rule reference is a 64-hex digest.
        assertThrows(SQLException.class, () -> run(HEAD + "('n7', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j1', '7', " + when + ", 'edit', 'Succeeded', null, '', '" + HEX + "')"));
        assertThrows(SQLException.class, () -> run(HEAD + "('n8', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j1', '8', " + when + ", 'edit', 'Succeeded', null, repeat('a', 129), '" + HEX + "')"));
        assertThrows(SQLException.class, () -> run(HEAD + "('n9', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j1', '9', " + when + ", 'edit', 'Succeeded', null, E'a\\nb', '" + HEX + "')"));
        assertThrows(SQLException.class, () -> run(HEAD + "('n10', 'pan-1', 'palo_alto', 'PAN_CONFIG_LOG', null, 'j1', '10', " + when + ", 'edit', 'Succeeded', null, null, 'not-a-digest')"));
    }

    @Test void linkRowsKeepTheirStateCountPairingAndGoWithTheHistoryRow() throws Exception {
        try (var db = fixture.migrateConnection()) {
            // A snapshot whose first publication is an initial capture, then a change: the trigger writes one history row.
            String rule = "{\"sections\":[{\"source\":\"DG-EXAMPLE\",\"name\":\"Pre rules\",\"rules\":[{\"id\":\"r1\",\"uuid\":\"u1\",\"name\":\"Rule-EXAMPLE\",\"action\":\"allow\"}]}],\"objects\":{}}";
            String changed = rule.replace("allow", "deny");
            try (var s = db.prepareStatement("insert into policy_snapshot values ('policy-1', '2026-11-12T06:00:00Z', '{}'::jsonb, ?::jsonb)")) { s.setString(1, rule); s.execute(); }
            try (var s = db.prepareStatement("update policy_snapshot set collected_at = '2026-11-12T09:00:00Z', snapshot = ?::jsonb where policy_id = 'policy-1'")) { s.setString(1, changed); s.execute(); }
            String id = scalar(db, "select revision_id from policy_rule_history where policy_id = 'policy-1' and not initial_capture order by revision_id desc limit 1");
            String insert = "insert into policy_change_actor_link(revision_id, state, reason, admin_count, candidate_names) values (" + id + ", ";
            try (var statement = db.createStatement()) {
                // State, reason and the candidate list agree: MATCHED has one, AMBIGUOUS two or more, UNKNOWN none and a reason.
                assertThrows(SQLException.class, () -> statement.execute(insert + "'MATCHED', null, 2, '[\"a\",\"b\"]'::jsonb)"));
                assertThrows(SQLException.class, () -> statement.execute(insert + "'AMBIGUOUS', null, 1, '[\"a\"]'::jsonb)"));
                assertThrows(SQLException.class, () -> statement.execute(insert + "'UNKNOWN', null, 0, '[]'::jsonb)"));
                assertThrows(SQLException.class, () -> statement.execute(insert + "'UNKNOWN', 'no entry', 0, '[]'::jsonb)"));
                assertThrows(SQLException.class, () -> statement.execute(insert + "'GUESSED', null, 1, '[\"a\"]'::jsonb)"));
                statement.execute(insert + "'AMBIGUOUS', null, 2, '[\"admin-a\",\"admin-b\"]'::jsonb)");
                assertThrows(SQLException.class, () -> statement.execute(insert + "'MATCHED', null, 1, '[\"admin-a\"]'::jsonb)"), "one link per history row");
                assertEquals("1", scalar(db, "select count(*) from policy_change_actor_link where revision_id = " + id));
                // PO D5: the candidate names live exactly as long as the history row.
                statement.execute("delete from policy_rule_history where revision_id = " + id);
                assertEquals("0", scalar(db, "select count(*) from policy_change_actor_link where revision_id = " + id));
            }
        }
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
