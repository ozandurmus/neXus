package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * V248 (PO chat 2026-11-17): the six "Traffic and ARP (information)" readiness reads are read-only gate rows inserted
 * DRAFTED, with the exact literals; V249 signs them off ("Evet, onaylıyorum"); both migrations run twice are no-ops; the
 * earlier-run query runs on the schema.
 */
class FailoverTrafficArpSchemaTest {
    private static final Map<String, String> LITERALS = Map.of(
        "asa_failover_show_traffic", "show traffic",
        "asa_failover_show_arp_statistics", "show arp statistics",
        "fgt_failover_performance_status", "get system performance status",
        "fgt_failover_arp", "get system arp",
        "pan_failover_traffic_counters", "<show><counter><interface>all</interface></counter></show>",
        "pan_failover_arp_table", "<show><arp><entry name = 'all'/></arp></show>");

    @Test
    void sixSignedOffReadRowsWithExactLiteralsAndIdempotentMigrations() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("failover_traffic_arp")) {
            fixture.runFlyway();
            try (var db = fixture.migrateConnection()) {
                for (var entry : LITERALS.entrySet()) {
                    String where = " from gate_registry where gate_id='" + entry.getKey() + "'";
                    assertEquals("SIGNED_OFF", scalar(db, "select sign_off_state" + where), entry.getKey());
                    assertEquals("read", scalar(db, "select action_class" + where), entry.getKey());
                    assertEquals(entry.getValue(), scalar(db, "select canonical_command_key" + where), entry.getKey());
                    assertEquals("30", scalar(db, "select timeout_s" + where));
                    assertEquals("none", scalar(db, "select retry_rule" + where));
                    String pointer = scalar(db, "select source_document_pointer" + where);
                    assertTrue(pointer.contains("PO approval 2026-11-17") && !pointer.contains("awaiting"), entry.getKey());
                }
                // The FortiGate support-bundle row for the same command is another row (transport and purpose) and stays signed off.
                assertEquals("SIGNED_OFF", scalar(db, "select sign_off_state from gate_registry where gate_id='fgt_sb_performance_status'"));
                assertEquals("SIGNED_OFF", scalar(db, "select sign_off_state from gate_registry where gate_id='fgt_show_system_interface'"));
                assertTrue(scalar(db, "select max_frequency from gate_registry where gate_id='fgt_show_system_interface'").contains("readiness run"));
                // The earlier-run query is valid SQL on the real schema (no run: nothing found).
                assertEquals("0", scalar(db, "select count(*) from failover_check_result c join failover_run r on r.run_id=c.run_id "
                    + "where c.check_no=10 and c.phase='pre' and jsonb_typeof(c.derived->'trafficBytesPerSecond')='number'"));
            }
            for (String file : new String[] {"V248__failover_traffic_arp_reads.sql", "V249__failover_traffic_arp_signoff.sql"})
            try (var resource = getClass().getResourceAsStream("/db/migration/" + file)) {
                assertNotNull(resource, file);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false);
                    statement.execute(sql);
                    statement.execute(sql);
                    assertEquals("6", scalar(db, "select count(*) from gate_registry where sign_off_state='SIGNED_OFF' and gate_id in ('"
                        + String.join("','", LITERALS.keySet()) + "')"), file);
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
