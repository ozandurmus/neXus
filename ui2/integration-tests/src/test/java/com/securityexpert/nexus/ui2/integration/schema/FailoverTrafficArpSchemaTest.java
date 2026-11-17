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
 * DRAFTED, with the exact literals; the same migration run twice is a no-op; the earlier-run query runs on the schema.
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
    void sixDraftedReadRowsWithExactLiteralsAndAnIdempotentMigration() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("failover_traffic_arp")) {
            fixture.runFlyway();
            try (var db = fixture.migrateConnection()) {
                for (var entry : LITERALS.entrySet()) {
                    String where = " from gate_registry where gate_id='" + entry.getKey() + "'";
                    assertEquals("DRAFTED", scalar(db, "select sign_off_state" + where), entry.getKey());
                    assertEquals("read", scalar(db, "select action_class" + where), entry.getKey());
                    assertEquals(entry.getValue(), scalar(db, "select canonical_command_key" + where), entry.getKey());
                    assertEquals("30", scalar(db, "select timeout_s" + where));
                    assertEquals("none", scalar(db, "select retry_rule" + where));
                    assertTrue(scalar(db, "select source_document_pointer" + where).contains("awaiting PO approval"));
                }
                // The FortiGate support-bundle row for the same command is another row (transport and purpose) and stays signed off.
                assertEquals("SIGNED_OFF", scalar(db, "select sign_off_state from gate_registry where gate_id='fgt_sb_performance_status'"));
                // The earlier-run query is valid SQL on the real schema (no run: nothing found).
                assertEquals("0", scalar(db, "select count(*) from failover_check_result c join failover_run r on r.run_id=c.run_id "
                    + "where c.check_no=10 and c.phase='pre' and jsonb_typeof(c.derived->'trafficBytesPerSecond')='number'"));
            }
            try (var resource = getClass().getResourceAsStream("/db/migration/V248__failover_traffic_arp_reads.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false);
                    statement.execute(sql);
                    statement.execute(sql);
                    assertEquals("6", scalar(db, "select count(*) from gate_registry where gate_id in ('"
                        + String.join("','", LITERALS.keySet()) + "')"));
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
