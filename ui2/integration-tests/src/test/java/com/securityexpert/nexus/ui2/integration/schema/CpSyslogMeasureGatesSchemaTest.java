package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/** V149 (M1/M2) and V157 (M5): the Check Point system-log measurement gates match the committed fixture exactly. */
class CpSyslogMeasureGatesSchemaTest {
    @Test
    void migrationRowsMatchTheFixtureAreScopedToGaiaGatewaysAndStayIdempotent() throws Exception {
        var fixtureRows = new HashMap<String, String>();
        try (var stream = getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml")) {
            GateRegistryFixtureLoader.loadFromStream(stream).stream()
                    .filter(row -> row.gateId().startsWith("cp_syslog_measure_"))
                    .forEach(row -> fixtureRows.put(row.gateId(), row.canonicalCommandKey()));
        }
        assertEquals(15, fixtureRows.size());
        try (var fixture = Ui2PostgresFixture.create("cp_syslog_measure_gates")) {
            fixture.runFlyway();
            try (var resource = getClass().getResourceAsStream("/db/migration/V157__syslog_measure_m5_gates.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false); statement.execute(sql); statement.execute(sql); db.rollback();
                }
            }
            try (var db = fixture.migrateConnection()) {
                assertEquals("15", scalar(db, "select count(*) from gate_registry where gate_id like 'cp_syslog_measure_%' "
                        + "and sign_off_state='SIGNED_OFF' and action_class='read' and vendor='check_point' "
                        + "and platform_role_scope='cp_gaia_gateway' and shell_context='expert' "
                        + "and transport_kind='SSH_EXEC' and timeout_s=30 and retry_rule='none' "
                        + "and max_frequency like 'phase A measurement only: at most twice per device, 24 h apart%'"));
                for (var entry : fixtureRows.entrySet())
                    assertEquals(entry.getValue(), scalar(db, "select canonical_command_key from gate_registry where gate_id='"
                            + entry.getKey() + "'"), entry.getKey());
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
