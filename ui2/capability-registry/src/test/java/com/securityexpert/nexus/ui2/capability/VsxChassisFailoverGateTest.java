package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * PO_DECISION_RECORD_2026_11_19_VSX_CHASSIS_FAILOVER.md §7 (RATIFIED): the two VS0 chassis writes, exact literals only,
 * in the fixture and in V255 alike.
 */
class VsxChassisFailoverGateTest {
    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(VsxChassisFailoverGateTest.class
                .getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
    }

    private static GateResolution resolve(List<GateRow> rows, String command) {
        return GateResolver.resolve(new CanonicalCommandKey("check_point", "cp_gaia_gateway", "expert", "SSH_EXEC", command),
                Optional.of(ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE), k -> rows.stream().filter(r -> r.key().equals(k)).toList());
    }

    @Test
    void bothChassisWritesResolveKnownToTheirOwnRows() {
        var rows = fixture();
        var down = assertInstanceOf(GateResolution.Known.class, resolve(rows, GateRow.CP_CHASSIS_DOWN_COMMAND));
        assertEquals(GateRow.CP_CHASSIS_DOWN_GATE, down.gateId());
        var up = assertInstanceOf(GateResolution.Known.class, resolve(rows, GateRow.CP_CHASSIS_UP_COMMAND));
        assertEquals(GateRow.CP_CHASSIS_UP_GATE, up.gateId());
        for (var known : List.of(down, up)) {
            assertEquals(ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE, known.actionClass());
            assertEquals(30, known.timeoutS());
        }
        for (String id : List.of(GateRow.CP_CHASSIS_DOWN_GATE, GateRow.CP_CHASSIS_UP_GATE)) {
            var row = rows.stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow();
            assertEquals("none", row.retryRule());
            assertTrue(row.sourceDocumentPointer().contains("PO_DECISION_RECORD_2026_11_19_VSX_CHASSIS_FAILOVER.md"));
        }
    }

    @Test
    void nearMissesOfTheChassisFormsStayDenied() {
        assertFalse(row(GateRow.CP_CHASSIS_DOWN_GATE, GateRow.CP_CHASSIS_DOWN_COMMAND).violatesWriteMarkerDenylist());
        assertFalse(row(GateRow.CP_CHASSIS_UP_GATE, GateRow.CP_CHASSIS_UP_COMMAND).violatesWriteMarkerDenylist());
        assertTrue(row(GateRow.CP_CHASSIS_DOWN_GATE, GateRow.CP_CHASSIS_UP_COMMAND).violatesWriteMarkerDenylist());
        assertTrue(row(GateRow.CP_CHASSIS_DOWN_GATE, "bash -lc 'vsenv 1 && clusterXL_admin down'").violatesWriteMarkerDenylist());
        assertTrue(row(GateRow.CP_CHASSIS_DOWN_GATE, "bash -lc 'vsenv 0 && clusterXL_admin down -p'").violatesWriteMarkerDenylist());
        assertTrue(row("cp_failover_down_vsenv0_2", GateRow.CP_CHASSIS_DOWN_COMMAND).violatesWriteMarkerDenylist());
        // The bare and the vsenv <VSID> forms do not resolve to a chassis row.
        assertFalse(resolve(fixture(), "bash -lc 'clusterXL_admin down'") instanceof GateResolution.Known);
    }

    @Test
    void v255SeedsExactlyTheFixtureLiteralsAndIsGuarded() throws IOException {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V255__vsx_chassis_failover_gates.sql"));
        assertTrue(sql.contains("'cp_failover_down_vsenv0'") && sql.contains("'bash -lc ''vsenv 0 && clusterXL_admin down'''"));
        assertTrue(sql.contains("'cp_failover_up_vsenv0'") && sql.contains("'bash -lc ''vsenv 0 && clusterXL_admin up'''"));
        assertTrue(sql.contains("ON CONFLICT (gate_id) DO NOTHING"));
        assertEquals(3, sql.split("NOT LIKE").length - 1, "every amendment is guarded");
        // Each frequency amendment of V255 is also in the fixture.
        var rows = fixture();
        assertTrue(rows.stream().filter(r -> r.gateId().equals("cp_inventory_vsid_cphaprob_stat")).findFirst().orElseThrow()
                .maxFrequency().contains("VS-moved verification of a VSX HA-mode chassis"));
        // V265 rewrote the HA-only purpose clause of cp_inventory_cphaprob_stat_vsenv0 to the VS0 run in HA or VSLS mode.
        assertTrue(rows.stream().filter(r -> r.gateId().equals("cp_inventory_cphaprob_stat_vsenv0")).findFirst().orElseThrow()
                .maxFrequency().contains(GateRow.CP_VS0_RUN_READ_PURPOSE));
    }

    /** The VS0 reads of a VS0 failover run (V177 rows, the two inventory VS0 rows); V265 widens each one's purpose clause. */
    private static final List<String> VS0_RUN_READS = List.of("cp_inventory_cphaprob_stat_vsenv0",
            "cp_inventory_cphaprob_a_if_vsenv0", "cp_failover_tablestat_vsenv0", "cp_failover_arp_vsenv0",
            "cp_failover_traffic_vsenv0", "cp_failover_syncstat_vsenv0", "cp_failover_fw_stat_vsenv0",
            "cp_failover_pnotes_vsenv0", "cp_failover_bonds_vsenv0", "cp_failover_bond_members_vsenv0",
            "cp_failover_last_event_vsenv0", "cp_failover_routing_vsenv0", "cp_policy_install_cpstat_vsenv0");

    /** PO 2026-11-23 (PO_DECISION_RECORD_2026_11_23_VSX_VS0_INDEPENDENT.md): VS0 is its own unit in HA and VSLS mode. */
    @Test
    void fixtureCarriesTheWidenedVs0RunTextAndNoHaOnlyClause() {
        var rows = fixture();
        assertEquals(GateRow.CP_CHASSIS_DOWN_MAX_FREQUENCY, rows.stream()
                .filter(r -> r.gateId().equals(GateRow.CP_CHASSIS_DOWN_GATE)).findFirst().orElseThrow().maxFrequency());
        assertEquals(GateRow.CP_CHASSIS_UP_MAX_FREQUENCY, rows.stream()
                .filter(r -> r.gateId().equals(GateRow.CP_CHASSIS_UP_GATE)).findFirst().orElseThrow().maxFrequency());
        for (String id : VS0_RUN_READS) {
            var row = rows.stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow();
            assertEquals(1, row.maxFrequency().split(java.util.regex.Pattern.quote(GateRow.CP_VS0_RUN_READ_PURPOSE), -1).length - 1, id);
            assertFalse(row.maxFrequency().contains("VSX HA-mode chassis"), id);
            assertTrue(row.canonicalCommandKey().startsWith("bash -lc 'vsenv 0 && "), id);
        }
        for (String id : List.of(GateRow.CP_CHASSIS_DOWN_GATE, GateRow.CP_CHASSIS_UP_GATE))
            assertFalse(rows.stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow().maxFrequency()
                    .contains("never on a VSLS chassis"), id);
    }

    @Test
    void v265WidensOnlyTheFrequencyTextOfExactlyTheVs0RowsAndIsGuarded() throws IOException {
        String sql = Files.readString(Path.of(
                "../service/src/main/resources/db/migration/V265__vsx_vs0_independent_failover_gates.sql"));
        String body = sql.replaceAll("(?m)^--.*$", "");
        assertTrue(body.contains("'" + GateRow.CP_CHASSIS_DOWN_MAX_FREQUENCY + "'"));
        assertTrue(body.contains("'" + GateRow.CP_CHASSIS_UP_MAX_FREQUENCY + "'"));
        assertTrue(body.contains("'" + GateRow.CP_VS0_RUN_READ_PURPOSE + "'"));
        // Only max_frequency is set; no insert, delete or other column, and every update is guarded.
        assertFalse(body.toUpperCase(java.util.Locale.ROOT).contains("INSERT"));
        assertFalse(body.toUpperCase(java.util.Locale.ROOT).contains("DELETE"));
        assertEquals(3, body.split("SET max_frequency").length - 1);
        assertEquals(3, body.split("UPDATE gate_registry").length - 1);
        assertEquals(2, body.split("NOT LIKE").length - 1);
        assertTrue(body.contains("LIKE '%failover run of a VSX HA-mode chassis (PO 2026-11-19)%'"));
        // Every row is matched by its exact fixture command as well as its id: no command changes.
        var rows = fixture();
        for (String id : new java.util.ArrayList<String>() {{ addAll(VS0_RUN_READS);
                add(GateRow.CP_CHASSIS_DOWN_GATE); add(GateRow.CP_CHASSIS_UP_GATE); }}) {
            String command = rows.stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow().canonicalCommandKey();
            assertTrue(body.contains("'" + command.replace("'", "''") + "'"), id + " matched by its exact command");
        }
        assertFalse(body.contains("cp_inventory_vsid_cphaprob_stat"), "per-VS rows are untouched");
    }

    private static GateRow row(String gateId, String command) {
        return new GateRow(gateId, "check_point", "cp_gaia_gateway", "expert", "SSH_EXEC", command,
                ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE, SignOffState.SIGNED_OFF, 30, "none", "once",
                "one session", "UNKNOWN", "none", List.of(), "test");
    }
}
