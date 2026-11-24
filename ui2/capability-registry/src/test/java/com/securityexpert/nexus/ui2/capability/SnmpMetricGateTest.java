package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * V269 (SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 4, PO decision 6): exactly the pilot SNMPv3 reads are gated, all
 * as {@code read} under transport kind SNMP_V3, the fixture and the migration carry the same literals, and no SET or
 * write row exists for SNMP anywhere.
 */
class SnmpMetricGateTest {

    /** gate id -> canonical command key, as the design's section 4 table names them. */
    static final Map<String, String> APPROVED = Map.of(
            "cp_snmp_sys_uptime", "GET 1.3.6.1.2.1.1.3.0",
            "cp_snmp_proc_usage", "GET 1.3.6.1.4.1.2620.1.6.7.2.4.0",
            "cp_snmp_mem_total_real64", "GET 1.3.6.1.4.1.2620.1.6.7.4.3.0",
            "cp_snmp_mem_active_real64", "GET 1.3.6.1.4.1.2620.1.6.7.4.4.0",
            "cp_snmp_fw_num_conn", "GET 1.3.6.1.4.1.2620.1.1.25.3.0",
            "pan_snmp_sys_uptime", "GET 1.3.6.1.2.1.1.3.0",
            "pan_snmp_hr_processor_load", "WALK 1.3.6.1.2.1.25.3.3.1.2",
            "pan_snmp_hr_storage_table", "WALK 1.3.6.1.2.1.25.2.3.1",
            "pan_snmp_session_active", "GET 1.3.6.1.4.1.25461.2.1.2.3.3.0");

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(SnmpMetricGateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    @Test
    void theFixtureHasExactlyTheApprovedSnmpRowsAllSignedOffReads() {
        List<GateRow> snmp = fixture().stream().filter(r -> r.transportKind().equals("SNMP_V3")).toList();
        assertEquals(APPROVED.size(), snmp.size());
        for (GateRow row : snmp) {
            assertEquals(APPROVED.get(row.gateId()), row.canonicalCommandKey(), row.gateId());
            assertEquals(ActionClass.CLASS_0_READ, row.actionClass(), row.gateId());
            assertEquals(SignOffState.SIGNED_OFF, row.signOffState(), row.gateId());
            assertEquals("not_applicable", row.shellContext());
            assertEquals(3, row.timeoutS());
            assertEquals("none", row.secretOutputRisk());
            assertTrue(row.canonicalCommandKey().startsWith("GET ") || row.canonicalCommandKey().startsWith("WALK "));
            String scope = row.gateId().startsWith("cp_") ? "cp_gaia_gateway" : "pan_firewall";
            String vendor = row.gateId().startsWith("cp_") ? "check_point" : "palo_alto";
            assertEquals(scope, row.platformRoleScope());
            assertEquals(vendor, row.vendor());
        }
    }

    @Test
    void everyApprovedRowResolvesThroughTheGateResolver() {
        List<GateRow> rows = fixture();
        GateRegistryPort registry = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        for (var e : APPROVED.entrySet()) {
            boolean cp = e.getKey().startsWith("cp_");
            var key = new CanonicalCommandKey(cp ? "check_point" : "palo_alto", cp ? "cp_gaia_gateway" : "pan_firewall",
                    "not_applicable", "SNMP_V3", e.getValue());
            var resolved = GateResolver.resolve(key, java.util.Optional.of(ActionClass.CLASS_0_READ), registry);
            assertTrue(resolved instanceof GateResolution.Known known && known.gateId().equals(e.getKey()), e.getKey());
        }
    }

    @Test
    void v269SeedsTheSameLiteralsAndNoSet() throws Exception {
        String sql = Files.readString(Path.of(
                "../service/src/main/resources/db/migration/V269__snmp_v3_metric_read_gates.sql"));
        for (var e : APPROVED.entrySet()) {
            assertTrue(sql.contains("('" + e.getKey() + "', "), e.getKey());
            assertTrue(sql.contains("'" + e.getValue() + "'"), e.getValue());
        }
        assertEquals(APPROVED.size(), sql.split("'SNMP_V3',", -1).length - 1);
        assertEquals(APPROVED.size(), sql.split("'read', 'SIGNED_OFF', 3,", -1).length - 1);
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains("'SET "));
        assertFalse(fixture().stream().filter(r -> r.transportKind().startsWith("SNMP")).anyMatch(r -> !r.canonicalCommandKey()
                .startsWith("GET ") && !r.canonicalCommandKey().startsWith("WALK ")));
    }
}
