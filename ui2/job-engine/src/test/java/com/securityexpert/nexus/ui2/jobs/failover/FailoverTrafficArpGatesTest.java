package com.securityexpert.nexus.ui2.jobs.failover;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * The "Traffic and ARP (information)" reads (V248, PO chat 2026-11-17): six exact literals, read-only, inserted DRAFTED and
 * signed off by V249 ("Evet, onaylıyorum", PO 2026-11-17). Each resolves to its own row and nothing else.
 */
class FailoverTrafficArpGatesTest {
    private static final Map<String, String> LITERALS = Map.of(
        "asa_failover_show_traffic", "show traffic",
        "asa_failover_show_arp_statistics", "show arp statistics",
        "fgt_failover_performance_status", "get system performance status",
        "fgt_failover_arp", "get system arp",
        "pan_failover_traffic_counters", "<show><counter><interface>all</interface></counter></show>",
        "pan_failover_arp_table", "<show><arp><entry name = 'all'/></arp></show>");
    private static final List<GateRow> ROWS = GateRegistryFixtureLoader.loadFromStream(
        FailoverTrafficArpGatesTest.class.getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));

    private static GateRow row(String id) {
        return ROWS.stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id));
    }

    @Test void sixRowsExactLiteralsReadOnlyAndSignedOffByTheProductOwner() {
        for (var entry : LITERALS.entrySet()) {
            GateRow r = row(entry.getKey());
            assertEquals(entry.getValue(), r.canonicalCommandKey(), entry.getKey());
            assertEquals(ActionClass.CLASS_0_READ, r.actionClass(), entry.getKey());
            assertEquals(SignOffState.SIGNED_OFF, r.signOffState(), entry.getKey());
            assertTrue(r.sourceDocumentPointer().contains("PO_DECISION_RECORD_2026_11_17_FAILOVER_TRAFFIC_ARP_READS.md"), entry.getKey());
            assertEquals(30, r.timeoutS());
            assertEquals("none", r.retryRule());
            assertFalse(r.violatesWriteMarkerDenylist());
            assertTrue(r.maxFrequency().contains("once") || r.maxFrequency().contains("twice"));
        }
        assertEquals("cisco_asa", row("asa_failover_show_traffic").vendor());
        assertEquals("fortinet", row("fgt_failover_arp").vendor());
        assertEquals("palo_alto", row("pan_failover_traffic_counters").vendor());
        assertEquals("SSH_EXEC", row("fgt_failover_performance_status").transportKind());
        assertEquals("SSH_INTERACTIVE", row("asa_failover_show_arp_statistics").transportKind());
        assertEquals("PAN_XML_API", row("pan_failover_arp_table").transportKind());
    }

    @Test void eachRowResolvesToItselfAndEveryKeyIsUnique() {
        var registry = (com.securityexpert.nexus.ui2.capability.GateRegistryPort) key -> ROWS.stream().filter(r -> r.key().equals(key)).toList();
        for (String id : LITERALS.keySet()) {
            var resolved = GateResolver.resolve(row(id).key(), java.util.Optional.of(ActionClass.CLASS_0_READ), registry);
            assertInstanceOf(GateResolution.Known.class, resolved, id);
            assertEquals(id, ((GateResolution.Known) resolved).gateId());
        }
        // The VDOM list at a FortiGate readiness run reuses the approved inventory read; only its stated frequency widened.
        GateRow interfaces = row("fgt_show_system_interface");
        assertEquals(SignOffState.SIGNED_OFF, interfaces.signOffState());
        assertEquals("show system interface", interfaces.canonicalCommandKey());
        assertTrue(interfaces.maxFrequency().contains("readiness run"));
        assertEquals(ROWS.size(), ROWS.stream().map(GateRow::key).distinct().count(), "one row per canonical key");
        // The FortiGate support-bundle row for the same command has another transport and purpose and stays SIGNED_OFF.
        assertEquals(SignOffState.SIGNED_OFF, row("fgt_sb_performance_status").signOffState());
        assertNotEquals(row("fgt_sb_performance_status").key(), row("fgt_failover_performance_status").key());
    }

    @Test void theRowsAreExcludedFromDiagnosticReads() {
        assertTrue(DiagnosticRead.DIAGNOSTIC_EXCLUDED.containsAll(LITERALS.keySet()));
        var gates = (com.securityexpert.nexus.ui2.capability.GateRegistryPort) key -> ROWS.stream().filter(r -> r.key().equals(key)).toList();
        for (String vendor : List.of("cisco_asa", "fortinet", "palo_alto"))
            assertTrue(DiagnosticRead.commands(vendor, "gateway", null, gates).stream().noneMatch(c -> LITERALS.containsKey(c.gateId())), vendor);
    }
}
