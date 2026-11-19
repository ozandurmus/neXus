package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Operations static route -- Phase R0 gate tests
 * ({@code docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md}, PO approval 2026-11-18: "go komutlar ok").
 * Verifies the seven NEW SIGNED_OFF CLASS_0 read rows, the nine widened reused read rows, the absence of R0-CP-5 and
 * FortiGate rows, and the total absence of any static-route write gate row in the fixture and V252 migration.
 */
class StaticRouteR0GateTest {

    static final Map<String, List<String>> NEW_R0_GATES = Map.of(
            "cp_route_show_route_static", List.of("check_point", "cp_gaia_gateway", "expert", "SSH_EXEC",
                    "clish -c 'show route static'", "R0-CP-1"),
            "cp_route_ip_route_get", List.of("check_point", "cp_gaia_gateway", "expert", "SSH_EXEC",
                    "ip -4 route get <DST>", "R0-CP-2"),
            "pan_route_show_vr_static_route", List.of("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API",
                    "type=config&action=show&xpath=/config/devices/entry[@name='localhost.localdomain']/network/virtual-router/entry[@name='<VR>']/routing-table/ip/static-route",
                    "R0-PAN-1"),
            "pan_route_check_pending_changes", List.of("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API",
                    "type=op&cmd=<check><pending-changes/></check>", "R0-PAN-3"),
            "asa_route_changeto_context", List.of("cisco_asa", "cisco_asa_firewall", "context", "SSH_INTERACTIVE",
                    "changeto context <CTX_NAME>", "R0-ASA-3"),
            "asa_route_show_running_route", List.of("cisco_asa", "cisco_asa_firewall", "privileged_exec", "SSH_INTERACTIVE",
                    "show running-config route", "R0-ASA-4"),
            "asa_route_show_route_host", List.of("cisco_asa", "cisco_asa_firewall", "privileged_exec", "SSH_INTERACTIVE",
                    "show route <HOST>", "R0-ASA-6"));

    static final Set<String> REUSED_R0_GATES = Set.of(
            "cp_inventory_ip_route_show_table_all",
            "cp_inventory_cphaprob_stat",
            "path_validation_pan_fib_lookup",
            "pan_configuration_effective_running",
            "pan_inventory_show_ha_state",
            "pan_inventory_show_routing_route",
            "asa_show_mode",
            "asa_show_curpriv",
            "asa_show_route");

    static final Set<String> FORBIDDEN_WRITE_GATE_IDS = Set.of(
            "cp_route_set_static_route_on",
            "cp_route_set_static_route_comment",
            "cp_route_set_static_route_nexthop_off",
            "cp_route_save_config",
            "pan_route_set_vr_static_route",
            "pan_route_delete_vr_static_route",
            "pan_route_revert_config",
            "pan_route_commit_partial_admin",
            "pan_route_show_job_id",
            "asa_route_add_static",
            "asa_route_delete_static",
            "asa_route_write_memory",
            "fgt_route_check_central_mgmt_and_vdoms",
            "fgt_route_show_router_static",
            "fgt_route_add_static_edit0",
            "fgt_route_delete_static_seq");

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(StaticRouteR0GateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    private static GateRegistryPort registry(List<GateRow> rows) {
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    @Test
    void sevenNewR0ReadGatesAreSignedOffClass0ReadsAndResolveUniquely() {
        List<GateRow> rows = fixture();
        Set<String> routeGates = rows.stream()
                .map(GateRow::gateId)
                .filter(id -> id.startsWith("cp_route_") || id.startsWith("pan_route_")
                        || id.startsWith("asa_route_") || id.startsWith("fgt_route_"))
                .collect(Collectors.toSet());
        assertEquals(NEW_R0_GATES.keySet(), routeGates, "only the 7 approved R0 read gates may exist under *_route_*");

        NEW_R0_GATES.forEach((gateId, spec) -> {
            GateRow row = rows.stream().filter(r -> r.gateId().equals(gateId)).findFirst().orElseThrow();
            assertEquals(spec.get(0), row.vendor(), gateId);
            assertEquals(spec.get(1), row.platformRoleScope(), gateId);
            assertEquals(spec.get(2), row.shellContext(), gateId);
            assertEquals(spec.get(3), row.transportKind(), gateId);
            assertEquals(spec.get(4), row.canonicalCommandKey(), gateId);
            assertEquals(ActionClass.CLASS_0_READ, row.actionClass(), gateId);
            assertEquals(SignOffState.SIGNED_OFF, row.signOffState(), gateId);
            assertEquals(30, row.timeoutS(), gateId);
            assertEquals("none", row.retryRule(), gateId);
            assertFalse(row.canonicalCommandKey().contains("bash -lc"), gateId + " must not use bash -lc");
            assertFalse(row.violatesWriteMarkerDenylist(), gateId);
            assertTrue(row.sourceDocumentPointer().contains("OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md " + spec.get(5)), gateId);
            assertTrue(row.sourceDocumentPointer().contains("go komutlar ok"), gateId);
            assertEquals(1, rows.stream().filter(r -> r.key().equals(row.key())).count(), "unique key: " + gateId);
            var resolved = GateResolver.resolve(row.key(), Optional.of(ActionClass.CLASS_0_READ), registry(rows));
            assertEquals(gateId, assertInstanceOf(GateResolution.Known.class, resolved).gateId());
            assertThrows(GateActionClassMismatchException.class, () -> GateResolver.resolve(
                    row.key(), Optional.of(ActionClass.CLASS_3_CONFIGURATION_WRITE), registry(rows)));
        });
    }

    @Test
    void nineReusedGatesCarryWidenedRoutePreviewFrequencyText() {
        List<GateRow> rows = fixture();
        for (String gateId : REUSED_R0_GATES) {
            GateRow row = rows.stream().filter(r -> r.gateId().equals(gateId)).findFirst().orElseThrow(() -> new AssertionError(gateId));
            assertEquals(ActionClass.CLASS_0_READ, row.actionClass(), gateId);
            assertEquals(SignOffState.SIGNED_OFF, row.signOffState(), gateId);
            assertTrue(row.maxFrequency().contains("static-route preview"), gateId + " frequency not widened: " + row.maxFrequency());
        }
    }

    @Test
    void noWriteGateOrFortiGateRouteGateOrAntiSpoofingGateExistsInFixtureOrMigration() throws Exception {
        List<GateRow> rows = fixture();
        for (String forbidden : FORBIDDEN_WRITE_GATE_IDS) {
            assertTrue(rows.stream().noneMatch(r -> r.gateId().equals(forbidden)), "forbidden gate present: " + forbidden);
        }
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V252__static_route_r0_preview.sql"));
        assertTrue(sql.contains("OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md"));
        assertTrue(sql.contains("go komutlar ok"));
        assertTrue(sql.contains("CHECK (mutation_possible = FALSE)"));
        for (String forbidden : FORBIDDEN_WRITE_GATE_IDS) {
            assertFalse(sql.contains("'" + forbidden + "'"), "V252 must not contain write/FGT gate " + forbidden);
        }
        assertFalse(sql.contains("'configuration-write'"), "V252 must not register any configuration-write row");
    }
}
