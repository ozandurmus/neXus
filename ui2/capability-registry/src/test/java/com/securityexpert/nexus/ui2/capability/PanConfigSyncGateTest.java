package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/** PO_DECISION_RECORD_2026_10_24_PAN_CONFIG_SYNC.md: the one SIGNED_OFF configuration-write row, exact form only. */
class PanConfigSyncGateTest {
    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(PanConfigSyncGateTest.class
                .getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
    }

    private static GateRow row(String gateId, String scope, String command) {
        return new GateRow(gateId, "palo_alto", scope, "not_applicable", "PAN_XML_API", command,
                ActionClass.CLASS_3_CONFIGURATION_WRITE, SignOffState.SIGNED_OFF, 60, "none", "once per job",
                "one key per member per job", "UNKNOWN", "none", List.of(), "test");
    }

    @Test
    void theFixtureRowResolvesKnownAsTheExactConfigurationWrite() {
        var rows = fixture();
        var key = new CanonicalCommandKey("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API",
                GateRow.PAN_CONFIG_SYNC_COMMAND);
        var resolved = GateResolver.resolve(key, Optional.of(ActionClass.CLASS_3_CONFIGURATION_WRITE),
                k -> rows.stream().filter(r -> r.key().equals(k)).toList());
        var known = assertInstanceOf(GateResolution.Known.class, resolved);
        assertEquals(GateRow.PAN_CONFIG_SYNC_GATE, known.gateId());
        assertEquals(ActionClass.CLASS_3_CONFIGURATION_WRITE, known.actionClass());
        assertEquals(60, known.timeoutS());
        var stored = rows.stream().filter(r -> r.gateId().equals(GateRow.PAN_CONFIG_SYNC_GATE)).toList();
        assertEquals(1, stored.size());
        assertEquals("none", stored.get(0).retryRule());
        assertTrue(stored.get(0).sourceDocumentPointer().contains("PO_DECISION_RECORD_2026_10_24_PAN_CONFIG_SYNC.md"));
        // The only SIGNED_OFF configuration-write row in the whole fixture.
        assertEquals(1, rows.stream().filter(r -> r.actionClass() == ActionClass.CLASS_3_CONFIGURATION_WRITE
                && r.signOffState() == SignOffState.SIGNED_OFF).count());
    }

    @Test
    void theExceptionCoversOnlyTheExactGateLiteralAndScope() {
        assertFalse(row(GateRow.PAN_CONFIG_SYNC_GATE, "pan_firewall", GateRow.PAN_CONFIG_SYNC_COMMAND).violatesWriteMarkerDenylist());
        // A near miss of any field is a denylist violation (a SIGNED_OFF class 3 row nobody approved).
        assertTrue(row(GateRow.PAN_CONFIG_SYNC_GATE, "pan_firewall", GateRow.PAN_CONFIG_SYNC_COMMAND + " ").violatesWriteMarkerDenylist());
        assertTrue(row("pan_failover_config_sync_2", "pan_firewall", GateRow.PAN_CONFIG_SYNC_COMMAND).violatesWriteMarkerDenylist());
        assertTrue(row(GateRow.PAN_CONFIG_SYNC_GATE, "pan_panorama", GateRow.PAN_CONFIG_SYNC_COMMAND).violatesWriteMarkerDenylist());
        assertTrue(row(GateRow.PAN_CONFIG_SYNC_GATE, "pan_firewall",
                "<request><high-availability><sync-to-remote><candidate-config/></sync-to-remote></high-availability></request>")
                .violatesWriteMarkerDenylist());
        var otherClass = new GateRow(GateRow.PAN_CONFIG_SYNC_GATE, "palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API",
                GateRow.PAN_CONFIG_SYNC_COMMAND, ActionClass.CLASS_4_POLICY_DEPLOYMENT, SignOffState.SIGNED_OFF, 60, "none",
                "once", "one key", "UNKNOWN", "none", List.of(), "test");
        assertTrue(otherClass.violatesWriteMarkerDenylist());
    }

    @Test
    void theHaStateReadNamesTheConfigSyncPurpose() {
        var read = fixture().stream().filter(r -> r.gateId().equals("pan_inventory_show_ha_state")).findFirst().orElseThrow();
        assertEquals(ActionClass.CLASS_0_READ, read.actionClass());
        assertTrue(read.maxFrequency().contains("config sync (PO 2026-10-24)"));
    }
}
