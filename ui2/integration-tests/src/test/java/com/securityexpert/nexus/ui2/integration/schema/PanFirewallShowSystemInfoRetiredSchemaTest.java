package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/** V171 (PO 2026-10-21): the firewall show system info row is BLOCKED, the Panorama row untouched, re-running a no-op. */
class PanFirewallShowSystemInfoRetiredSchemaTest {
    @Test
    void firewallRowIsBlockedPanoramaRowStaysSignedOffAndTheMigrationIsIdempotent() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("pan_show_system_info_retired")) {
            fixture.runFlyway();
            try (var db = fixture.migrateConnection()) {
                assertEquals("BLOCKED", scalar(db,
                        "select sign_off_state from gate_registry where gate_id='pan_inventory_show_system_info'"));
                assertEquals("pan_firewall", scalar(db,
                        "select platform_role_scope from gate_registry where gate_id='pan_inventory_show_system_info'"));
                assertEquals("SIGNED_OFF", scalar(db,
                        "select sign_off_state from gate_registry where gate_id='pan_panorama_show_system_info'"));
                assertEquals("1", scalar(db, "select count(*) from information_schema.columns "
                        + "where table_name='device_inventory_run' and column_name='not_collected'"));
                assertEquals("0", scalar(db, "select count(*) from gate_registry where sign_off_state='SIGNED_OFF' "
                        + "and platform_role_scope='pan_firewall' and canonical_command_key='<show><system><info/></system></show>'"));
            }
            try (var resource = getClass().getResourceAsStream("/db/migration/V171__pan_firewall_command_retirement.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false);
                    statement.execute(sql);
                    statement.execute(sql);
                    assertEquals("BLOCKED", scalar(db,
                            "select sign_off_state from gate_registry where gate_id='pan_inventory_show_system_info'"));
                    assertEquals("SIGNED_OFF", scalar(db,
                            "select sign_off_state from gate_registry where gate_id='pan_panorama_show_system_info'"));
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
