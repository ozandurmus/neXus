package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * V178 (PO_DECISION_RECORD_2026_10_24_PAN_CONFIG_SYNC.md, RATIFIED): the one SIGNED_OFF configuration-write gate row with
 * its exact literal, the HA-state read's extended purpose, the job's module, the audited record table with its guard,
 * and a migration that can be run twice.
 */
class PanConfigSyncSchemaTest {
    private static final String LITERAL =
        "<request><high-availability><sync-to-remote><running-config/></sync-to-remote></high-availability></request>";

    @Test
    void theGateRowTheModuleAndTheRecordTableAreInPlaceAndTheMigrationIsRepeatable() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("pan_config_sync")) {
            fixture.runFlyway();
            try (var db = fixture.migrateConnection()) {
                assertEquals("SIGNED_OFF", scalar(db, "select sign_off_state from gate_registry where gate_id='pan_failover_config_sync'"));
                assertEquals("configuration-write", scalar(db, "select action_class from gate_registry where gate_id='pan_failover_config_sync'"));
                assertEquals(LITERAL, scalar(db, "select canonical_command_key from gate_registry where gate_id='pan_failover_config_sync'"));
                assertEquals("PAN_XML_API", scalar(db, "select transport_kind from gate_registry where gate_id='pan_failover_config_sync'"));
                assertEquals("pan_firewall", scalar(db, "select platform_role_scope from gate_registry where gate_id='pan_failover_config_sync'"));
                assertEquals("none", scalar(db, "select retry_rule from gate_registry where gate_id='pan_failover_config_sync'"));
                assertTrue(scalar(db, "select source_document_pointer from gate_registry where gate_id='pan_failover_config_sync'")
                    .contains("PO_DECISION_RECORD_2026_10_24_PAN_CONFIG_SYNC.md"));
                assertEquals("1", scalar(db, "select count(*) from gate_registry where action_class='configuration-write' "
                    + "and sign_off_state='SIGNED_OFF'"));
                assertTrue(scalar(db, "select max_frequency from gate_registry where gate_id='pan_inventory_show_ha_state'")
                    .contains("config sync (PO 2026-10-24)"));
                assertEquals("failover", scalar(db, "select ui2_job_module('pan_failover_config_sync')"));
                assertEquals("failover", scalar(db, "select ui2_job_module('pan_failover_readiness')"));
                assertEquals("diagnostics", scalar(db, "select ui2_job_module('cp_spike_layout_review')"));
                assertEquals("1", scalar(db, "select count(*) from pg_trigger where tgname='trg_audit_pan_config_sync'"));
                assertEquals("1", scalar(db, "select count(*) from pg_trigger where tgname='pan_config_sync_guard'"));
                assertEquals("1", scalar(db, "select count(*) from pg_trigger where tgname='pan_config_sync_job_end'"));
                assertEquals("1", scalar(db, "select count(*) from pg_indexes where indexname='uq_pan_config_sync_open'"));
                // Argon P0-1: the per-endpoint purpose exists; the settled release is part of the sweep.
                assertTrue(scalar(db, "select pg_get_constraintdef(oid) from pg_constraint "
                    + "where conname='endpoint_admission_purpose_class_check'").contains("CONFIGURATION_WRITE"));
                assertTrue(scalar(db, "select prosrc from pg_proc where proname='ui2_release_orphan_quarantines'")
                    .contains("ui2_release_settled_config_sync_leases"));
                assertTrue(scalar(db, "select prosrc from pg_proc where proname='ui2_release_orphan_quarantines'")
                    .contains("ui2_release_settled_failover_leases(NULL)"));
            }
            try (var resource = getClass().getResourceAsStream("/db/migration/V178__pan_config_sync_remediation.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false);
                    statement.execute(sql);
                    statement.execute(sql);
                    assertEquals("1", scalar(db, "select count(*) from gate_registry where gate_id='pan_failover_config_sync'"));
                    assertEquals("1", scalar(db, "select count(*) from gate_registry where gate_id='pan_inventory_show_ha_state' "
                        + "and max_frequency like '%config sync (PO 2026-10-24)%' "
                        + "and max_frequency not like '%config sync (PO 2026-10-24)%config sync (PO 2026-10-24)%'"));
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
