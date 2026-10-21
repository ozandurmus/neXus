package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * PO decision 2026-10-21 (docs/design/PO_DECISION_RECORD_2026_10_21_PAN_SHOW_SYSTEM_INFO_RETIRED.md): no executor may
 * send show system info to a Palo Alto firewall. Proven against the gate set (the firewall row is BLOCKED and resolves
 * UNKNOWN), the committed capability specs (no pan_firewall step sends it) and V171 itself. The Panorama row is out of
 * scope and still resolves KNOWN.
 */
class PanSystemInfoRetiredGateTest {

    private static final String COMMAND = "<show><system><info/></system></show>";

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(PanSystemInfoRetiredGateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    private static GateRegistryPort registry(List<GateRow> rows) {
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    @Test
    void theFirewallRowIsBlockedAndResolvesUnknownForEveryCaller() {
        List<GateRow> rows = fixture();
        GateRow firewall = rows.stream().filter(r -> r.gateId().equals("pan_inventory_show_system_info")).findFirst().orElseThrow();
        assertEquals(SignOffState.BLOCKED, firewall.signOffState());
        assertEquals("pan_firewall", firewall.platformRoleScope());
        assertTrue(firewall.sourceDocumentPointer().contains("PO_DECISION_RECORD_2026_10_21_PAN_SHOW_SYSTEM_INFO_RETIRED.md"));

        GateResolution resolution = GateResolver.resolve(
                new CanonicalCommandKey("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API", COMMAND),
                Optional.of(ActionClass.CLASS_0_READ), registry(rows));
        GateResolution.Unknown unknown = assertInstanceOf(GateResolution.Unknown.class, resolution);
        assertTrue(unknown.reason().contains("BLOCKED"));

        // No other firewall-scoped row carries the same literal under another id (that would reopen the command).
        assertEquals(1, rows.stream().filter(r -> r.canonicalCommandKey().equals(COMMAND)
                && r.platformRoleScope().equals("pan_firewall")).count());
        assertFalse(rows.stream().anyMatch(r -> r.canonicalCommandKey().equals(COMMAND)
                && r.signOffState() == SignOffState.SIGNED_OFF && !r.platformRoleScope().equals("panorama")),
                "the only SIGNED_OFF row with this literal is the Panorama's");
    }

    @Test
    void thePanoramaRowIsOutOfScopeAndStillResolvesKnown() {
        GateResolution resolution = GateResolver.resolve(
                new CanonicalCommandKey("palo_alto", "panorama", "not_applicable", "PAN_XML_API", COMMAND),
                Optional.of(ActionClass.CLASS_0_READ), registry(fixture()));
        GateResolution.Known known = assertInstanceOf(GateResolution.Known.class, resolution);
        assertEquals("pan_panorama_show_system_info", known.gateId());
    }

    @Test
    void noCommittedFirewallCapabilitySpecSendsIt() {
        for (String file : List.of("pan_inventory_collect.yaml", "pan_configuration_collect.yaml",
                "device_confirm_palo_alto.yaml", "pan_discovery_enumerate.yaml", "pan_device_state_backup.yaml",
                "pan_set_config_read.yaml")) {
            CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                    getClass().getClassLoader().getResourceAsStream("capabilities/" + file));
            assertTrue(spec.allSteps().stream().noneMatch(step -> COMMAND.equals(step.commandTemplate())), file);
        }
        // The two specs that carried it are still execution-eligible without it.
        for (String file : List.of("pan_inventory_collect.yaml", "pan_configuration_collect.yaml")) {
            CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                    getClass().getClassLoader().getResourceAsStream("capabilities/" + file));
            assertTrue(new CapabilityRegistryLoader(registry(fixture())).load(spec).executionEligible(), file);
        }
    }

    @Test
    void v171BlocksOnlyTheFirewallRowAndIsIdempotent() throws Exception {
        String sql = Files.readString(Path.of(
                "../service/src/main/resources/db/migration/V171__pan_firewall_command_retirement.sql"));
        String update = sql.substring(sql.indexOf("UPDATE gate_registry"), sql.indexOf("ALTER TABLE device_inventory_run"));
        assertTrue(update.contains("sign_off_state = 'BLOCKED'"));
        assertTrue(update.contains("WHERE gate_id = 'pan_inventory_show_system_info'"));
        assertTrue(update.contains("AND platform_role_scope = 'pan_firewall'"));
        assertTrue(update.contains("AND sign_off_state <> 'BLOCKED'"), "re-running is a no-op");
        assertTrue(update.contains("PO_DECISION_RECORD_2026_10_21_PAN_SHOW_SYSTEM_INFO_RETIRED.md"));
        assertFalse(update.contains("pan_panorama_show_system_info"), "the Panorama row is out of scope");
        assertFalse(sql.contains("DELETE"), "the row is kept as the record of what was sent before");
        assertTrue(sql.contains("set_config('app.actor_fingerprint'"), "the audit trigger gets its context");
        assertFalse(sql.contains("COMMIT;"));
        assertTrue(sql.contains("ALTER TABLE device_inventory_run ADD COLUMN IF NOT EXISTS not_collected TEXT;"),
                "a run records the reads it deliberately did not send");
    }
}
