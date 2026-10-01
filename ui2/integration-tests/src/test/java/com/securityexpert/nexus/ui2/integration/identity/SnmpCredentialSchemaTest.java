package com.securityexpert.nexus.ui2.integration.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;

class SnmpCredentialSchemaTest {
    @Test
    void snmpConstraintsAndAuditAllowlistHoldForAppRole() throws SQLException {
        try (var fixture = Ui2PostgresFixture.create("snmp_credentials")) {
            fixture.runFlyway();
            try (Connection app = fixture.appConnection()) {
                for (String level : new String[] {"noAuthNoPriv", "authNoPriv", "authPriv"}) {
                    String id = insert(app, level, !level.equals("noAuthNoPriv"), level.equals("authPriv"));
                    try (var query = app.prepareStatement("select after_state::text from audit_log "
                            + "where table_name = 'credentials' and row_pk = ?")) {
                        query.setString(1, id);
                        try (var rows = query.executeQuery()) {
                            assertTrue(rows.next());
                            String audit = rows.getString(1);
                            assertTrue(audit.contains(level));
                            assertFalse(audit.contains("encrypted_secret"));
                            assertFalse(audit.contains("encrypted_passphrase"));
                            assertFalse(audit.contains("synthetic-auth"));
                            assertFalse(audit.contains("synthetic-privacy"));
                        }
                    }
                }
                assertThrows(SQLException.class, () -> insert(app, "authPriv", true, false));
                assertThrows(SQLException.class, () -> insert(app, "authNoPriv", false, false));
                assertThrows(SQLException.class, () -> insert(app, "noAuthNoPriv", true, false));
                assertThrows(SQLException.class, () -> insert(app, null, false, false));
            }
        }
    }

    private String insert(Connection app, String level, boolean auth, boolean privacy) throws SQLException {
        Ui2Rows.setAuditContext(app, "fixture-actor", "credential_create");
        String id = UUID.randomUUID().toString();
        try (var insert = app.prepareStatement("insert into credentials (credential_id, display_name, kind, "
                + "username, encrypted_secret, encrypted_passphrase, envelope_key_id, created_by_actor_fingerprint, "
                + "snmp_security_level, snmp_auth_protocol, snmp_priv_protocol) "
                + "values (?, 'Synthetic SNMP', 'snmp_v3', 'synthetic-user', ?, ?, 'fixture-key', 'fixture-actor', ?, ?, ?)")) {
            insert.setString(1, id);
            insert.setBytes(2, auth ? "synthetic-auth".getBytes(java.nio.charset.StandardCharsets.UTF_8) : null);
            insert.setBytes(3, privacy ? "synthetic-privacy".getBytes(java.nio.charset.StandardCharsets.UTF_8) : null);
            insert.setString(4, level);
            insert.setString(5, auth ? "SHA-256" : null);
            insert.setString(6, privacy ? "AES-128" : null);
            insert.executeUpdate();
        }
        return id;
    }
}
