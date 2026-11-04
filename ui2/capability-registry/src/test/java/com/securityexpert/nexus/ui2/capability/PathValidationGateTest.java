package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Path analysis stage A6 (PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md rows 2-5, RATIFIED 2026-11-04): exactly the approved
 * literals are gated, as CLASS_0 SIGNED_OFF reads with the device-command gate fields filled, and V188 inserts the same
 * rows the fixture carries.
 */
class PathValidationGateTest {

    /** gate id -> (scope, shell, transport, literal, proposal row). */
    static final Map<String, List<String>> APPROVED = Map.of(
            "path_validation_cp_route_get", List.of("check_point", "cp_gaia_gateway", "expert", "SSH_EXEC",
                    "bash -lc 'ip -4 route get <DST>'", "row 2"),
            "path_validation_cp_route_get_vsx", List.of("check_point", "cp_gaia_gateway", "expert", "SSH_EXEC",
                    "bash -lc 'vsenv <VSID> && ip -4 route get <DST>'", "row 2"),
            "path_validation_pan_fib_lookup", List.of("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API",
                    "<test><routing><fib-lookup><virtual-router>VR</virtual-router><ip>DST</ip></fib-lookup></routing></test>",
                    "row 3"),
            "path_validation_pan_security_policy_match", List.of("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API",
                    "<test><security-policy-match><from>Z1</from><to>Z2</to><source>SRC</source><destination>DST</destination>"
                            + "<protocol>6</protocol><destination-port>PORT</destination-port></security-policy-match></test>",
                    "row 4"),
            "path_validation_pan_nat_policy_match", List.of("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API",
                    "<test><nat-policy-match><from>Z1</from><to>Z2</to><source>SRC</source><destination>DST</destination>"
                            + "<protocol>6</protocol><destination-port>PORT</destination-port></nat-policy-match></test>",
                    "row 5"));

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(PathValidationGateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    private static GateRegistryPort registry(List<GateRow> rows) {
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    @Test
    void everyApprovedLiteralIsOneSignedOffReadWithTheGateFieldsFilled() {
        List<GateRow> rows = fixture();
        assertEquals(APPROVED.keySet(), rows.stream().map(GateRow::gateId).filter(id -> id.startsWith("path_validation_"))
                .collect(java.util.stream.Collectors.toSet()), "no other path_validation gate exists");
        APPROVED.forEach((gateId, spec) -> {
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
            assertTrue(row.maxFrequency().contains("PO-chosen sample") && row.maxFrequency().contains("never polled"), gateId);
            assertTrue(row.sessionReuseRule().contains("no retry"), gateId);
            assertTrue(row.unsupportedBehaviorRef().contains("NOT_EVALUABLE, never a MISMATCH"), gateId);
            assertTrue(row.secretOutputRisk().contains("never stored"), gateId);
            assertEquals(List.of("result", "reason"), row.safeTelemetryFields(), gateId);
            assertTrue(row.sourceDocumentPointer().startsWith("docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md " + spec.get(5)
                    + " (RATIFIED"), gateId);
            assertEquals(1, rows.stream().filter(r -> r.key().equals(row.key())).count(), "unique canonical key " + gateId);
            var key = row.key();
            assertEquals(gateId, assertInstanceOf(GateResolution.Known.class,
                    GateResolver.resolve(key, Optional.of(ActionClass.CLASS_0_READ), registry(rows))).gateId());
            assertThrows(GateActionClassMismatchException.class, () -> GateResolver.resolve(key,
                    Optional.of(ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE), registry(rows)));
        });
    }

    @Test
    void aUdpOrOtherwiseAlteredLiteralHasNoGate() {
        List<GateRow> rows = fixture();
        String udp = APPROVED.get("path_validation_pan_security_policy_match").get(4).replace("<protocol>6<", "<protocol>17<");
        assertInstanceOf(GateResolution.Unknown.class, GateResolver.resolve(new CanonicalCommandKey("palo_alto",
                "pan_firewall", "not_applicable", "PAN_XML_API", udp), Optional.of(ActionClass.CLASS_0_READ), registry(rows)));
        assertInstanceOf(GateResolution.Unknown.class, GateResolver.resolve(new CanonicalCommandKey("palo_alto",
                "panorama", "not_applicable", "PAN_XML_API", APPROVED.get("path_validation_pan_fib_lookup").get(4)),
                Optional.of(ActionClass.CLASS_0_READ), registry(rows)));
        assertInstanceOf(GateResolution.Unknown.class, GateResolver.resolve(new CanonicalCommandKey("check_point",
                "cp_gaia_gateway", "expert", "SSH_EXEC", "bash -lc 'ip route get <DST>'"),
                Optional.of(ActionClass.CLASS_0_READ), registry(rows)));
    }

    @Test
    void v188InsertsTheSameRowsIdempotentlyAndMapsTheModule() throws Exception {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V188__path_analysis_validation.sql"));
        List<GateRow> rows = fixture();
        APPROVED.forEach((gateId, spec) -> {
            GateRow row = rows.stream().filter(r -> r.gateId().equals(gateId)).findFirst().orElseThrow();
            assertTrue(sql.contains("('" + gateId + "', '" + spec.get(0) + "', '" + spec.get(1) + "', '" + spec.get(2)
                    + "', '" + spec.get(3) + "',\n     '" + spec.get(4).replace("'", "''") + "', 'read', 'SIGNED_OFF', 30, 'none',"),
                    gateId);
            for (String field : List.of(row.maxFrequency(), row.sessionReuseRule(), row.unsupportedBehaviorRef(),
                    row.secretOutputRisk(), row.sourceDocumentPointer()))
                assertTrue(sql.contains("'" + field.replace("'", "''") + "'"), gateId + ": " + field);
        });
        assertEquals(5, java.util.regex.Pattern.compile("\\('path_validation_[a-z_]+', '(check_point|palo_alto)'")
                .matcher(sql).results().count(), "exactly five gate rows");
        assertTrue(sql.contains("ON CONFLICT (gate_id) DO NOTHING"));
        assertTrue(sql.contains("set_config('app.actor_fingerprint', 'migration:V188_"));
        assertTrue(sql.contains("'cp_spike_layout_review','path_validation') THEN 'diagnostics'"));
        assertFalse(sql.contains("UPDATE gate_registry"));
        assertFalse(sql.contains("DELETE FROM"));
        assertFalse(sql.contains("COMMIT;"));
    }
}
