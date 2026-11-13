package com.securityexpert.nexus.ui2.jobs.failover;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md section 1: four exact literals, read-only, inserted DRAFTED
 * (a DRAFTED row never resolves KNOWN, so nothing is sent until the Product Owner approves and a later migration signs
 * them off); the one-time measurement rows of V210 stay as they are.
 */
class AsaFailoverReadinessGatesTest {
    private static final Map<String, String> LITERALS = Map.of(
        "asa_failover_show_failover", "show failover",
        "asa_failover_show_failover_state", "show failover state",
        "asa_failover_show_failover_history", "show failover history",
        "asa_failover_show_conn_count", "show conn count");
    private static final Map<String, Integer> TIMEOUTS = Map.of(
        "asa_failover_show_failover", 30, "asa_failover_show_failover_state", 30,
        "asa_failover_show_failover_history", 60, "asa_failover_show_conn_count", 30);
    private static final List<GateRow> ROWS = GateRegistryFixtureLoader.loadFromStream(
        AsaFailoverReadinessGatesTest.class.getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));

    private static GateRow row(String id) {
        return ROWS.stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id));
    }

    @Test void theFourRowsAreDraftedReadOnlyAndExactLiterals() {
        for (var entry : LITERALS.entrySet()) {
            GateRow r = row(entry.getKey());
            assertEquals(SignOffState.DRAFTED, r.signOffState(), entry.getKey());
            assertEquals(ActionClass.CLASS_0_READ, r.actionClass(), entry.getKey());
            assertEquals("cisco_asa", r.vendor());
            assertEquals("cisco_asa_firewall", r.platformRoleScope());
            assertEquals("privileged_exec", r.shellContext());
            // SSH_INTERACTIVE: the registry holds one row per canonical key, and the V210 measurement rows hold these literals as SSH_EXEC.
            assertEquals("SSH_INTERACTIVE", r.transportKind());
            assertEquals(entry.getValue(), r.canonicalCommandKey());
            assertEquals(TIMEOUTS.get(entry.getKey()), r.timeoutS());
            assertEquals("none", r.retryRule());
            assertTrue(r.sourceDocumentPointer().contains("PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS"));
            assertFalse(r.violatesWriteMarkerDenylist());
        }
        assertTrue(row("asa_failover_show_failover").maxFrequency().contains("twice"));
    }

    @Test void theFourRowsAreExcludedFromDiagnosticReads() {
        assertTrue(DiagnosticRead.DIAGNOSTIC_EXCLUDED.containsAll(LITERALS.keySet()));
        var gates = (com.securityexpert.nexus.ui2.capability.GateRegistryPort) key -> ROWS.stream().filter(r -> r.key().equals(key)).toList();
        for (String role : List.of("gateway", "management_server"))
            assertTrue(DiagnosticRead.commands("cisco_asa", role, null, gates).stream().noneMatch(c -> LITERALS.containsKey(c.gateId())));
    }

    @Test void everyFailoverRelatedCiscoAsaGateRowIsAReadAndTheMeasurementRowsAreUnchanged() {
        List<GateRow> asa = ROWS.stream().filter(r -> "cisco_asa".equals(r.vendor())).toList();
        assertFalse(asa.isEmpty());
        for (GateRow r : asa) {
            if (r.gateId().startsWith("asa_failover_") || r.gateId().startsWith("asa_measure_"))
                assertEquals(ActionClass.CLASS_0_READ, r.actionClass(), r.gateId());
        }
        // The only non-read Cisco ASA rows are the backup archive's own, written long before failover (V78 era).
        assertEquals(java.util.Set.of("asa_backup_archive", "asa_delete_archive"), asa.stream()
            .filter(r -> r.actionClass() != ActionClass.CLASS_0_READ).map(GateRow::gateId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(SignOffState.SIGNED_OFF, row("asa_measure_show_failover").signOffState());
        assertEquals("show failover", row("asa_measure_show_failover").canonicalCommandKey());
        assertEquals("SSH_EXEC", row("asa_measure_show_failover").transportKind());
        assertEquals(ROWS.size(), ROWS.stream().map(GateRow::key).distinct().count(), "one row per canonical key");
    }
}
