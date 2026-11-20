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
        assertTrue(rows.stream().filter(r -> r.gateId().equals("cp_inventory_cphaprob_stat_vsenv0")).findFirst().orElseThrow()
                .maxFrequency().contains("failover run of a VSX HA-mode chassis"));
    }

    private static GateRow row(String gateId, String command) {
        return new GateRow(gateId, "check_point", "cp_gaia_gateway", "expert", "SSH_EXEC", command,
                ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE, SignOffState.SIGNED_OFF, 30, "none", "once",
                "one session", "UNKNOWN", "none", List.of(), "test");
    }
}
