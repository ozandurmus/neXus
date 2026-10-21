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
 * PO decision 2026-10-21 (docs/design/PO_DECISION_RECORD_2026_10_21_PAN_TYPE_VERSION_AND_CHECK7.md): a firewall's serial,
 * model and PAN-OS version are read with the documented XML API request type=version, for every PAN-OS version. Proven
 * against the gate set (the new row is SIGNED_OFF, resolves KNOWN for the firewall scope only), the committed capability
 * specs (inventory and configuration carry the step and stay execution-eligible) and V172 itself.
 */
class PanTypeVersionGateTest {

    private static final String KEY = "type=version";

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(PanTypeVersionGateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    private static GateRegistryPort registry(List<GateRow> rows) {
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    private static GateRow row() {
        return fixture().stream().filter(r -> r.gateId().equals("pan_identity_version")).findFirst().orElseThrow();
    }

    @Test
    void theFirewallRowIsSignedOffReadOnlyAndCitesTheRatifiedRecord() {
        GateRow row = row();
        assertEquals(SignOffState.SIGNED_OFF, row.signOffState());
        assertEquals(ActionClass.CLASS_0_READ, row.actionClass());
        assertEquals("palo_alto", row.vendor());
        assertEquals("pan_firewall", row.platformRoleScope());
        assertEquals("PAN_XML_API", row.transportKind());
        assertEquals("not_applicable", row.shellContext());
        assertEquals(KEY, row.canonicalCommandKey());
        assertEquals(30, row.timeoutS());
        assertEquals("none", row.retryRule());
        assertTrue(row.sourceDocumentPointer().contains("PO_DECISION_RECORD_2026_10_21_PAN_TYPE_VERSION_AND_CHECK7.md"));
        assertTrue(row.unsupportedBehaviorRef().contains("NOT_EVALUABLE"));
    }

    @Test
    void itResolvesKnownForTheFirewallScopeAndForNoOtherScope() {
        List<GateRow> rows = fixture();
        GateResolution firewall = GateResolver.resolve(
                new CanonicalCommandKey("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API", KEY),
                Optional.of(ActionClass.CLASS_0_READ), registry(rows));
        assertEquals("pan_identity_version", assertInstanceOf(GateResolution.Known.class, firewall).gateId());
        // The Panorama keeps its own system info read (out of scope): type=version has no Panorama row.
        assertInstanceOf(GateResolution.Unknown.class, GateResolver.resolve(
                new CanonicalCommandKey("palo_alto", "panorama", "not_applicable", "PAN_XML_API", KEY),
                Optional.of(ActionClass.CLASS_0_READ), registry(rows)));
        // Not a write: a step declaring a write class against it is a spec defect, never a runtime override.
        org.junit.jupiter.api.Assertions.assertThrows(GateActionClassMismatchException.class, () -> GateResolver.resolve(
                new CanonicalCommandKey("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API", KEY),
                Optional.of(ActionClass.CLASS_3_CONFIGURATION_WRITE), registry(rows)));
        assertEquals(1, rows.stream().filter(r -> r.canonicalCommandKey().equals(KEY)).count(), "one row carries the request");
    }

    @Test
    void showSystemInfoStaysRetiredForFirewallsAndSignedOffForThePanorama() {
        String command = "<show><system><info/></system></show>";
        assertInstanceOf(GateResolution.Unknown.class, GateResolver.resolve(
                new CanonicalCommandKey("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API", command),
                Optional.of(ActionClass.CLASS_0_READ), registry(fixture())));
        assertInstanceOf(GateResolution.Known.class, GateResolver.resolve(
                new CanonicalCommandKey("palo_alto", "panorama", "not_applicable", "PAN_XML_API", command),
                Optional.of(ActionClass.CLASS_0_READ), registry(fixture())));
    }

    @Test
    void inventoryAndConfigurationCarryTheStepAndStayExecutionEligible() {
        for (String file : List.of("pan_inventory_collect.yaml", "pan_configuration_collect.yaml")) {
            CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                    getClass().getClassLoader().getResourceAsStream("capabilities/" + file));
            assertEquals(1, spec.allSteps().stream().filter(step -> KEY.equals(step.commandTemplate())).count(), file);
            try (var yaml = getClass().getClassLoader().getResourceAsStream("capabilities/" + file)) {
                assertTrue(new String(yaml.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .contains("send: \"type=version\"\n    gate_reference: pan_identity_version"), file);
            } catch (java.io.IOException unreadable) {
                throw new IllegalStateException(unreadable);
            }
            assertTrue(new CapabilityRegistryLoader(registry(fixture())).load(spec).executionEligible(), file);
        }
        // Withdrawn rows would make the capability ineligible: without the new row the step cannot resolve.
        List<GateRow> without = fixture().stream().filter(r -> !r.gateId().equals("pan_identity_version")).toList();
        for (String file : List.of("pan_inventory_collect.yaml", "pan_configuration_collect.yaml")) {
            CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                    getClass().getClassLoader().getResourceAsStream("capabilities/" + file));
            assertFalse(new CapabilityRegistryLoader(registry(without)).load(spec).executionEligible(), file);
        }
    }

    @Test
    void v172InsertsOnlyThePanFirewallRowIdempotentlyAndMatchesTheFixture() throws Exception {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V172__pan_firewall_type_version_identity.sql"));
        GateRow row = row();
        assertTrue(sql.contains("INSERT INTO gate_registry"));
        assertTrue(sql.contains("('pan_identity_version', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',"));
        assertTrue(sql.contains("'type=version', 'read', 'SIGNED_OFF', 30, 'none',"));
        assertTrue(sql.contains("'" + row.maxFrequency() + "'"));
        assertTrue(sql.contains("'" + row.sessionReuseRule() + "'"));
        assertTrue(sql.contains("'" + row.unsupportedBehaviorRef() + "'"));
        assertTrue(sql.contains("'" + row.secretOutputRisk() + "'"));
        assertTrue(sql.contains("'" + row.sourceDocumentPointer() + "'"));
        assertTrue(sql.contains("ON CONFLICT (gate_id) DO NOTHING"), "re-running is a no-op");
        assertTrue(sql.contains("set_config('app.actor_fingerprint', 'migration:V172_"), "the audit trigger gets its context");
        assertTrue(sql.contains("set_config('app.action_id'"));
        assertFalse(sql.contains("UPDATE gate_registry"), "no other row is touched (V171's withdrawal stands)");
        assertFalse(sql.contains("DELETE"));
        assertFalse(sql.contains("COMMIT;"));
        assertEquals(1, sql.split("\\('pan_", -1).length - 1, "exactly one row");
    }
}
