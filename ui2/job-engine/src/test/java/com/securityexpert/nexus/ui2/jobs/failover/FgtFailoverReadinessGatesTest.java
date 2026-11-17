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
 * PO_DECISION_RECORD_2026_11_17_FGT_FAILOVER_READINESS_READS.md: three exact literals, read-only, inserted SIGNED_OFF by
 * V247 on the PO's chat approval of 2026-11-17. The reads that were approved earlier are reused unchanged.
 */
class FgtFailoverReadinessGatesTest {
    private static final Map<String, String> LITERALS = Map.of(
        "fgt_failover_checksum_cluster", "diagnose sys ha checksum cluster",
        "fgt_failover_dump_by_group", "diagnose sys ha dump-by group",
        "fgt_failover_ha_failover_status", "execute ha failover status");
    private static final List<GateRow> ROWS = GateRegistryFixtureLoader.loadFromStream(
        FgtFailoverReadinessGatesTest.class.getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));

    private static GateRow row(String id) {
        return ROWS.stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id));
    }

    @Test void theThreeRowsAreSignedOffReadOnlyThirtySecondsNoRetryAndExactLiterals() {
        for (var entry : LITERALS.entrySet()) {
            GateRow r = row(entry.getKey());
            assertEquals(SignOffState.SIGNED_OFF, r.signOffState(), entry.getKey());
            assertEquals(ActionClass.CLASS_0_READ, r.actionClass(), entry.getKey());
            assertEquals("fortinet", r.vendor());
            assertEquals("fortigate", r.platformRoleScope());
            assertEquals(entry.getValue(), r.canonicalCommandKey());
            assertEquals(30, r.timeoutS());
            assertEquals("none", r.retryRule());
            assertTrue(r.maxFrequency().startsWith("once per run"));
            assertTrue(r.sourceDocumentPointer().contains("PO_DECISION_RECORD_2026_11_17_FGT_FAILOVER_READINESS_READS"));
            assertTrue(r.sourceDocumentPointer().contains("PO chat approval 2026-11-17"));
            assertFalse(r.violatesWriteMarkerDenylist());
        }
    }

    @Test void sessionStatIsNotAddedAndNoFortigateFailoverRowIsAWrite() {
        assertTrue(ROWS.stream().noneMatch(r -> r.canonicalCommandKey().contains("session stat")));
        assertTrue(ROWS.stream().noneMatch(r -> r.canonicalCommandKey().contains("reset-uptime")));
        for (GateRow r : ROWS) if (r.gateId().startsWith("fgt_failover_")) assertEquals(ActionClass.CLASS_0_READ, r.actionClass(), r.gateId());
        assertEquals(ROWS.size(), ROWS.stream().map(GateRow::key).distinct().count(), "one row per canonical key");
    }

    @Test void theThreeRowsAreExcludedFromDiagnosticReads() {
        assertTrue(DiagnosticRead.DIAGNOSTIC_EXCLUDED.containsAll(LITERALS.keySet()));
        var gates = (com.securityexpert.nexus.ui2.capability.GateRegistryPort) key -> ROWS.stream().filter(r -> r.key().equals(key)).toList();
        assertTrue(DiagnosticRead.commands("fortinet", "gateway", null, gates).stream().noneMatch(c -> LITERALS.containsKey(c.gateId())));
    }

    @Test void theSwitchIsOffUnlessExactlyOn() {
        assertFalse(FgtFailoverReadinessSwitch.fromValue(null).enabled());
        assertFalse(FgtFailoverReadinessSwitch.fromValue("true").enabled());
        assertTrue(FgtFailoverReadinessSwitch.fromValue(" on ").enabled());
    }
}
