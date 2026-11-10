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
 * Lifecycle reads, group A (docs/design/LIFECYCLE_READS_GROUP_A_COMMAND_GATE_ENTRIES.md, PO chat approval 2026-11-10,
 * "Yalnız A grubu"): exactly the eleven approved literals are gated, and V198 seeds the same rows the fixture carries.
 */
class LifecycleReadsGroupAGateTest {

    /** gate id -> vendor, scope, shell, transport, literal. */
    static final Map<String, List<String>> APPROVED = Map.ofEntries(
            Map.entry("cp_lifecycle_cplic_print", List.of("check_point", "cp_gaia_gateway", "expert", "SSH_EXEC", "bash -lc 'cplic print'")),
            Map.entry("cp_lifecycle_cplic_print_vsx", List.of("check_point", "cp_gaia_gateway", "expert", "SSH_EXEC", "bash -lc 'vsenv 0 && cplic print'")),
            Map.entry("cp_lifecycle_cplic_print_mds", List.of("check_point", "cp_multi_domain_server", "expert", "SSH_EXEC", "cplic print")),
            Map.entry("cp_lifecycle_cplp_list", List.of("check_point", "cp_gaia_gateway", "expert", "SSH_EXEC", "bash -lc 'cplp list'")),
            Map.entry("cp_lifecycle_cplp_list_vsx", List.of("check_point", "cp_gaia_gateway", "expert", "SSH_EXEC", "bash -lc 'vsenv 0 && cplp list'")),
            Map.entry("cp_lifecycle_cplp_list_mds", List.of("check_point", "cp_multi_domain_server", "expert", "SSH_EXEC", "cplp list")),
            Map.entry("cp_lifecycle_spark_show_license", List.of("check_point", "gaia_embedded", "clish", "SSH_EXEC", "show license")),
            Map.entry("asa_lifecycle_show_license_all", List.of("cisco_asa", "cisco_asa_firewall", "privileged_exec", "SSH_EXEC", "show license all")),
            Map.entry("bluecoat_mc_device_license", List.of("bluecoat", "bluecoat_management_center", "not_applicable", "HTTPS", "GET /api/devices/{uuid}/license")),
            Map.entry("bluecoat_mc_system_version", List.of("bluecoat", "bluecoat_management_center", "not_applicable", "HTTPS", "GET /api/system/version")),
            Map.entry("infoblox_license_gridwide", List.of("infoblox", "infoblox_grid_manager", "not_applicable", "HTTPS", "GET /wapi/v<ver>/license:gridwide")));

    private static final String POINTER = "docs/design/LIFECYCLE_READS_GROUP_A_COMMAND_GATE_ENTRIES.md (PO chat approval 2026-11-10)";

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(LifecycleReadsGroupAGateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    private static GateRegistryPort registry(List<GateRow> rows) {
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    @Test
    void everyApprovedLiteralIsOneSignedOffSixtySecondReadWithNoRetry() {
        List<GateRow> rows = fixture();
        assertEquals(11, APPROVED.size());
        APPROVED.forEach((gateId, spec) -> {
            GateRow row = rows.stream().filter(r -> r.gateId().equals(gateId)).findFirst().orElseThrow(() -> new AssertionError(gateId));
            assertEquals(spec.get(0), row.vendor(), gateId);
            assertEquals(spec.get(1), row.platformRoleScope(), gateId);
            assertEquals(spec.get(2), row.shellContext(), gateId);
            assertEquals(spec.get(3), row.transportKind(), gateId);
            assertEquals(spec.get(4), row.canonicalCommandKey(), gateId);
            assertEquals(ActionClass.CLASS_0_READ, row.actionClass(), gateId);
            assertEquals(SignOffState.SIGNED_OFF, row.signOffState(), gateId);
            assertEquals(60, row.timeoutS(), gateId);
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
        // Scoped to this record's rows: the backup path has its own, older gated "cplic print -x" row.
        List<GateRow> rows = fixture().stream().filter(r -> POINTER.equals(r.sourceDocumentPointer())).toList();
        for (String banned : List.of("cplic print -x", "bash -lc 'cplic print -x'", "cplic print -F", "fetch license", "show sources license-key",
                "licensing update-key", "licensing request-key")) {
            assertTrue(rows.stream().noneMatch(r -> r.canonicalCommandKey().contains(banned)), banned);
        }
        assertTrue(rows.stream().noneMatch(r -> r.canonicalCommandKey().contains("license:gridwide") && r.canonicalCommandKey().contains("key")));
    }

    @Test
    void v198SeedsTheSameRowsIdempotently() throws Exception {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V198__lifecycle_reads_group_a_gates.sql"));
        assertTrue(sql.contains("Yalnız A grubu"));
        assertTrue(sql.contains("set_config('app.actor_fingerprint'") && sql.contains("set_config('app.action_id'"));
        assertTrue(sql.contains("ON CONFLICT (gate_id) DO NOTHING"));
        List<GateRow> rows = fixture();
        APPROVED.forEach((gateId, spec) -> {
            GateRow row = rows.stream().filter(r -> r.gateId().equals(gateId)).findFirst().orElseThrow();
            assertTrue(sql.contains("('" + gateId + "', '" + spec.get(0) + "', '" + spec.get(1) + "', '" + spec.get(2) + "', '"
                    + spec.get(3) + "',\n     '" + spec.get(4).replace("'", "''") + "',\n     'read', 'SIGNED_OFF', 60, 'none', "), gateId);
            for (String field : List.of(row.maxFrequency(), row.sessionReuseRule(), row.unsupportedBehaviorRef(),
                    row.secretOutputRisk(), row.sourceDocumentPointer())) {
                assertTrue(sql.contains("'" + field.replace("'", "''") + "'"), gateId + ": " + field);
            }
        });
        assertEquals(11, sql.split("\\n    \\('", -1).length - 1, "eleven rows");
    }
}
