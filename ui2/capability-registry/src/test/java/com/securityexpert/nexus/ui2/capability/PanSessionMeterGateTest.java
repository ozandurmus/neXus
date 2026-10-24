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
 * PO decision 2026-10-24 (docs/design/PO_DECISION_RECORD_2026_10_24_PAN_OS12_REPLACEMENT_READS.md): failover check 6 on
 * PAN-OS 12 or later reads show session meter. Proven against the gate set (SIGNED_OFF, read-only, firewall scope only,
 * show session info untouched) and V179 itself.
 */
class PanSessionMeterGateTest {

    private static final String KEY = "<show><session><meter/></session></show>";

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(PanSessionMeterGateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    private static GateRegistryPort registry(List<GateRow> rows) {
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    private static GateRow row(String gateId) {
        return fixture().stream().filter(r -> r.gateId().equals(gateId)).findFirst().orElseThrow();
    }

    private static GateResolution resolve(String scope, String command, ActionClass actionClass) {
        return GateResolver.resolve(new CanonicalCommandKey("palo_alto", scope, "not_applicable", "PAN_XML_API", command),
                Optional.of(actionClass), registry(fixture()));
    }

    @Test
    void theMeterRowIsSignedOffReadOnlyAndCitesTheRatifiedRecord() {
        GateRow row = row("pan_failover_session_meter");
        assertEquals(SignOffState.SIGNED_OFF, row.signOffState());
        assertEquals(ActionClass.CLASS_0_READ, row.actionClass());
        assertEquals("pan_firewall", row.platformRoleScope());
        assertEquals("PAN_XML_API", row.transportKind());
        assertEquals(KEY, row.canonicalCommandKey());
        assertEquals(30, row.timeoutS());
        assertEquals("none", row.retryRule());
        assertTrue(row.sourceDocumentPointer().contains("PO_DECISION_RECORD_2026_10_24_PAN_OS12_REPLACEMENT_READS.md"));
        assertTrue(row.unsupportedBehaviorRef().contains("UNKNOWN"));
    }

    @Test
    void itResolvesKnownForTheFirewallOnlyAndShowSessionInfoIsUnchanged() {
        assertEquals("pan_failover_session_meter", assertInstanceOf(GateResolution.Known.class,
                resolve("pan_firewall", KEY, ActionClass.CLASS_0_READ)).gateId());
        assertInstanceOf(GateResolution.Unknown.class, resolve("panorama", KEY, ActionClass.CLASS_0_READ));
        org.junit.jupiter.api.Assertions.assertThrows(GateActionClassMismatchException.class,
                () -> resolve("pan_firewall", KEY, ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE));
        GateRow info = row("pan_failover_session_info");
        assertEquals(SignOffState.SIGNED_OFF, info.signOffState());
        assertEquals("pan_failover_session_info", assertInstanceOf(GateResolution.Known.class,
                resolve("pan_firewall", "<show><session><info/></session></show>", ActionClass.CLASS_0_READ)).gateId());
        assertEquals(1, fixture().stream().filter(r -> r.canonicalCommandKey().equals(KEY)).count());
    }

    @Test
    void v179InsertsOnlyTheMeterRowIdempotentlyAndMatchesTheFixture() throws Exception {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V179__pan_failover_session_meter.sql"));
        GateRow row = row("pan_failover_session_meter");
        assertTrue(sql.contains("('pan_failover_session_meter', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',"));
        assertTrue(sql.contains("'" + KEY + "', 'read', 'SIGNED_OFF', 30, 'none',"));
        assertTrue(sql.contains("'" + row.maxFrequency() + "'"));
        assertTrue(sql.contains("'" + row.sessionReuseRule() + "'"));
        assertTrue(sql.contains("'" + row.unsupportedBehaviorRef() + "'"));
        assertTrue(sql.contains("'" + row.secretOutputRisk() + "'"));
        assertTrue(sql.contains("'" + row.sourceDocumentPointer() + "'"));
        assertTrue(sql.contains("ON CONFLICT (gate_id) DO NOTHING"));
        assertTrue(sql.contains("set_config('app.actor_fingerprint', 'migration:V179_"));
        assertFalse(sql.contains("UPDATE gate_registry"));
        assertFalse(sql.contains("DELETE"));
        assertFalse(sql.contains("COMMIT;"));
        assertEquals(1, sql.split("\\('pan_", -1).length - 1, "exactly one row");
    }
}
