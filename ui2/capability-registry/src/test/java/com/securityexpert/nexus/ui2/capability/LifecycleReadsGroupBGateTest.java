package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Lifecycle reads, group B (docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md, PO chat approval 2026-11-10,
 * "d2, onayla d3,onayla onayla" and "Evet, dördü de"): exactly the seven approved literals are gated, and V199 seeds the same rows
 * the fixture carries.
 */
class LifecycleReadsGroupBGateTest {

    /** gate id -> vendor, scope, shell, transport, literal, timeout. */
    static final Map<String, List<String>> APPROVED = Map.ofEntries(
            Map.entry("pan_lifecycle_license_info", List.of("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API", "<request><license><info/></license></request>", "60")),
            Map.entry("pan_lifecycle_support_info", List.of("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API", "<request><support><info/></support></request>", "60")),
            Map.entry("fgt_lifecycle_update_info_contract", List.of("fortinet", "fortigate", "top_or_global", "SSH_EXEC", "diagnose test update info contract", "60")),
            Map.entry("fgt_lifecycle_autoupdate_versions", List.of("fortinet", "fortigate", "top_or_global", "SSH_EXEC", "diagnose autoupdate versions", "60")),
            Map.entry("fmg_lifecycle_fmupdate_dbcontract", List.of("fortinet", "fortimanager", "cli", "SSH_EXEC", "diagnose fmupdate dbcontract", "60")),
            Map.entry("bluecoat_mc_command_show_licenses", List.of("bluecoat", "bluecoat_management_center", "config_mode_via_mc", "HTTPS", "PUT /api/devices/{uuid}/command \"show licenses\"", "120")),
            Map.entry("infoblox_upgradestatus_grid", List.of("infoblox", "infoblox_grid_manager", "not_applicable", "HTTPS", "GET /wapi/v<ver>/upgradestatus?type=GRID", "60")));

    private static final String POINTER = "docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md (PO chat approval 2026-11-10)";

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(LifecycleReadsGroupBGateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    private static GateRegistryPort registry(List<GateRow> rows) {
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    @Test
    void everyApprovedLiteralIsOneSignedOffReadWithNoRetry() {
        List<GateRow> rows = fixture();
        assertEquals(7, APPROVED.size());
        APPROVED.forEach((gateId, spec) -> {
            GateRow row = rows.stream().filter(r -> r.gateId().equals(gateId)).findFirst().orElseThrow(() -> new AssertionError(gateId));
            assertEquals(spec.get(0), row.vendor(), gateId);
            assertEquals(spec.get(1), row.platformRoleScope(), gateId);
            assertEquals(spec.get(2), row.shellContext(), gateId);
            assertEquals(spec.get(3), row.transportKind(), gateId);
            assertEquals(spec.get(4), row.canonicalCommandKey(), gateId);
            assertEquals(ActionClass.CLASS_0_READ, row.actionClass(), gateId);
            assertEquals(SignOffState.SIGNED_OFF, row.signOffState(), gateId);
            assertEquals(Integer.parseInt(spec.get(5)), row.timeoutS(), gateId);
            assertEquals("none", row.retryRule(), gateId);
            assertFalse(row.maxFrequency().isBlank(), gateId);
            assertFalse(row.sessionReuseRule().isBlank(), gateId);
            assertFalse(row.unsupportedBehaviorRef().isBlank(), gateId);
            assertFalse(row.secretOutputRisk().isBlank(), gateId);
            assertFalse(row.safeTelemetryFields().isEmpty(), gateId);
            assertEquals(POINTER, row.sourceDocumentPointer(), gateId);
            assertEquals(1, rows.stream().filter(r -> r.key().equals(row.key())).count(), "unique canonical key " + gateId);
            assertEquals(gateId, assertInstanceOf(GateResolution.Known.class,
                    GateResolver.resolve(row.key(), Optional.of(ActionClass.CLASS_0_READ), registry(rows))).gateId());
        });
        assertEquals(APPROVED.keySet(), rows.stream().filter(r -> POINTER.equals(r.sourceDocumentPointer()))
                .map(GateRow::gateId).collect(Collectors.toSet()), "no other row cites the record");
    }

    @Test
    void neverSendFormsHaveNoGate() {
        // The record's own "Never to be sent" list, scoped to this record's rows.
        List<GateRow> rows = fixture().stream().filter(r -> POINTER.equals(r.sourceDocumentPointer())).toList();
        for (String banned : List.of("request license fetch", "request support check", "request batch license refresh", "execute update-now",
                "diagnose debug", "show sources license-key", "licensing update-key", "licensing request-key")) {
            assertTrue(rows.stream().noneMatch(r -> r.canonicalCommandKey().contains(banned)), banned);
        }
    }

    @Test
    void v199SeedsTheSameRowsIdempotently() throws Exception {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V199__lifecycle_reads_group_b_gates.sql"));
        assertTrue(sql.contains("d2, onayla d3,onayla onayla") && sql.contains("Evet, dördü de"));
        assertTrue(sql.contains("set_config('app.actor_fingerprint'") && sql.contains("set_config('app.action_id'"));
        assertTrue(sql.contains("ON CONFLICT (gate_id) DO NOTHING"));
        List<GateRow> rows = fixture();
        APPROVED.forEach((gateId, spec) -> {
            GateRow row = rows.stream().filter(r -> r.gateId().equals(gateId)).findFirst().orElseThrow();
            assertTrue(sql.contains("('" + gateId + "', '" + spec.get(0) + "', '" + spec.get(1) + "', '" + spec.get(2) + "', '"
                    + spec.get(3) + "',\n     '" + spec.get(4).replace("'", "''") + "',\n     'read', 'SIGNED_OFF', " + spec.get(5) + ", 'none', "), gateId);
            for (String field : List.of(row.maxFrequency(), row.sessionReuseRule(), row.unsupportedBehaviorRef(),
                    row.secretOutputRisk(), row.sourceDocumentPointer())) {
                assertTrue(sql.contains("'" + field.replace("'", "''") + "'"), gateId + ": " + field);
            }
        });
        assertEquals(7, sql.split("\\n    \\('", -1).length - 1, "seven rows");
    }
}
