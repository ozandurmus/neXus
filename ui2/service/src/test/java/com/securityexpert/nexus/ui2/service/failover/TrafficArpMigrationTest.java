package com.securityexpert.nexus.ui2.service.failover;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;

/** V248 and the gate fixture carry the same six rows with the same literals; V248 inserts them DRAFTED, V249 signs them off. */
class TrafficArpMigrationTest {
    @Test void migrationAndFixtureCarryTheSameSixRowsAndTheStateIsOneLine() throws Exception {
        String sql;
        try (var in = getClass().getClassLoader().getResourceAsStream("db/migration/V248__failover_traffic_arp_reads.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var rows = GateRegistryFixtureLoader.loadFromStream(
            getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        for (String id : new String[] {"asa_failover_show_traffic", "asa_failover_show_arp_statistics", "fgt_failover_performance_status",
                "fgt_failover_arp", "pan_failover_traffic_counters", "pan_failover_arp_table"}) {
            assertTrue(sql.contains("'" + id + "'"), id);
            var row = rows.stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow();
            assertTrue(sql.contains("'" + row.canonicalCommandKey().replace("'", "''") + "'"), "the migration carries the literal of " + id);
        }
        var state = java.util.regex.Pattern.compile("(?m)^\\s*VALUES \\('(DRAFTED|SIGNED_OFF)',").matcher(sql);
        assertTrue(state.find(), "one line decides the sign-off state");
        assertEquals("DRAFTED", state.group(1), "V248 is live as written: it inserted the rows DRAFTED");
        assertFalse(state.find(), "and only one");
        String signoff;
        try (var in = getClass().getClassLoader().getResourceAsStream("db/migration/V249__failover_traffic_arp_signoff.sql")) {
            signoff = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(signoff.contains("SET sign_off_state = 'SIGNED_OFF'") && signoff.contains("sign_off_state = 'DRAFTED'"));
        String decided = "SIGNED_OFF";
        for (String id : new String[] {"asa_failover_show_traffic", "fgt_failover_arp", "pan_failover_arp_table"})
            assertEquals(decided, rows.stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow().signOffState().name(),
                "the fixture mirrors the state after V249: " + id);
    }
}
