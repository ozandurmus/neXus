package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * V172 (PO 2026-10-21): the firewall type=version identity read is a SIGNED_OFF pan_firewall gate row; V171's withdrawal of
 * the firewall show system info row and the Panorama row are untouched; re-running is a no-op.
 */
class PanFirewallTypeVersionSchemaTest {
    @Test
    void typeVersionRowIsSignedOffTheSystemInfoWithdrawalStandsAndTheMigrationIsIdempotent() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("pan_type_version")) {
            fixture.runFlyway();
            try (var db = fixture.migrateConnection()) {
                assertEquals("SIGNED_OFF", scalar(db, "select sign_off_state from gate_registry where gate_id='pan_identity_version'"));
                assertEquals("pan_firewall", scalar(db, "select platform_role_scope from gate_registry where gate_id='pan_identity_version'"));
                assertEquals("type=version", scalar(db, "select canonical_command_key from gate_registry where gate_id='pan_identity_version'"));
                assertEquals("read", scalar(db, "select action_class from gate_registry where gate_id='pan_identity_version'"));
                assertTrue(scalar(db, "select source_document_pointer from gate_registry where gate_id='pan_identity_version'")
                        .contains("PO_DECISION_RECORD_2026_10_21_PAN_TYPE_VERSION_AND_CHECK7.md"));
                assertEquals("BLOCKED", scalar(db, "select sign_off_state from gate_registry where gate_id='pan_inventory_show_system_info'"));
                assertEquals("SIGNED_OFF", scalar(db, "select sign_off_state from gate_registry where gate_id='pan_panorama_show_system_info'"));
                assertEquals("1", scalar(db, "select count(*) from gate_registry where canonical_command_key='type=version'"));
            }
            try (var resource = getClass().getResourceAsStream("/db/migration/V172__pan_firewall_type_version_identity.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false);
                    statement.execute(sql);
                    statement.execute(sql);
                    assertEquals("1", scalar(db, "select count(*) from gate_registry where gate_id='pan_identity_version'"));
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
