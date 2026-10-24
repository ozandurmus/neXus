package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * V179 (PO 2026-10-24): the PAN-OS 12 replacement for failover check 6, show session meter, is a SIGNED_OFF pan_firewall
 * read row; show session info (V104) is untouched; re-running is a no-op.
 */
class PanFailoverSessionMeterSchemaTest {
    @Test
    void meterRowIsSignedOffSessionInfoIsUntouchedAndTheMigrationIsIdempotent() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("pan_session_meter")) {
            fixture.runFlyway();
            try (var db = fixture.migrateConnection()) {
                String where = " from gate_registry where gate_id='pan_failover_session_meter'";
                assertEquals("SIGNED_OFF", scalar(db, "select sign_off_state" + where));
                assertEquals("pan_firewall", scalar(db, "select platform_role_scope" + where));
                assertEquals("<show><session><meter/></session></show>", scalar(db, "select canonical_command_key" + where));
                assertEquals("read", scalar(db, "select action_class" + where));
                assertTrue(scalar(db, "select source_document_pointer" + where)
                        .contains("PO_DECISION_RECORD_2026_10_24_PAN_OS12_REPLACEMENT_READS.md"));
                assertEquals("SIGNED_OFF", scalar(db, "select sign_off_state from gate_registry where gate_id='pan_failover_session_info'"));
            }
            try (var resource = getClass().getResourceAsStream("/db/migration/V179__pan_failover_session_meter.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false);
                    statement.execute(sql);
                    statement.execute(sql);
                    assertEquals("1", scalar(db, "select count(*) from gate_registry where gate_id='pan_failover_session_meter'"));
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
