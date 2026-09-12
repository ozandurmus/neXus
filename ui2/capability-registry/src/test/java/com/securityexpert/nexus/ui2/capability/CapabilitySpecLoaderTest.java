package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Adjudication §2 answer 5: "a spec is validated against the C6 field set
 * at load; an unknown field is an error, never a silent ignore."
 */
class CapabilitySpecLoaderTest {

    private static final String VALID_SPEC = """
            capability_id: test_cap
            vendor: check_point
            platform_role_scope: cp_gaia_gateway
            maturity_state: CAP_OFFLINE
            transport:
              kind: ssh_exec
              trust_rule_ref: utils.cp_ssh_trust
            steps:
              - kind: connect
              - kind: exec
                shell_context: clish
                send: "show version"
                timeout_s: 30
                expect:
                  regex: "^Product version.*$"
            finally_steps:
              - kind: disconnect
            """;

    @Test
    void aValidSpecParsesWithAllDeclaredSteps() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromYaml(VALID_SPEC);

        assertEquals("test_cap", spec.capabilityId());
        assertEquals(TransportKind.SSH_EXEC, spec.transportKind());
        assertEquals(2, spec.steps().size());
        assertEquals(1, spec.finallySteps().size());
        assertEquals(StepKind.EXEC, spec.steps().get(1).kind());
        assertEquals("show version", spec.steps().get(1).commandTemplate());
    }

    @Test
    void anUnknownTopLevelFieldIsAnErrorNeverASilentIgnore() {
        String withUnknownField = VALID_SPEC + "unexpected_field: surprise\n";
        CapabilityValidationException e = assertThrows(CapabilityValidationException.class,
                () -> CapabilitySpecLoader.loadFromYaml(withUnknownField));
        assertEquals(CapabilityValidationException.UNKNOWN_SPEC_FIELD, e.code());
        assertTrue(e.getMessage().contains("unexpected_field"));
    }

    @Test
    void anUnknownStepFieldIsAlsoAnError() {
        String withUnknownStepField = """
                capability_id: test_cap
                vendor: check_point
                platform_role_scope: cp_gaia_gateway
                maturity_state: CAP_OFFLINE
                transport:
                  kind: ssh_exec
                  trust_rule_ref: utils.cp_ssh_trust
                steps:
                  - kind: connect
                    made_up_field: nope
                """;
        CapabilityValidationException e = assertThrows(CapabilityValidationException.class,
                () -> CapabilitySpecLoader.loadFromYaml(withUnknownStepField));
        assertEquals(CapabilityValidationException.UNKNOWN_SPEC_FIELD, e.code());
    }

    @Test
    void aMissingRequiredFieldFailsClosed() {
        String missingCapabilityId = """
                vendor: check_point
                platform_role_scope: cp_gaia_gateway
                maturity_state: CAP_OFFLINE
                transport:
                  kind: ssh_exec
                  trust_rule_ref: utils.cp_ssh_trust
                steps:
                  - kind: connect
                """;
        assertThrows(CapabilityValidationException.class, () -> CapabilitySpecLoader.loadFromYaml(missingCapabilityId));
    }

    @Test
    void theCommittedFixtureFilesParseAndTheCapabilityIsExecutionEligibleAgainstThem() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/cp_gaia_inventory.yaml"));
        List<GateRow> gates = GateRegistryFixtureLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));

        assertEquals("cp_gaia_inventory_show_version_ha_state", spec.capabilityId());
        assertTrue(gates.size() >= 2, "expected at least the two cp_gaia_* rows this capability resolves against");

        GateRegistryPort inMemory = key -> gates.stream()
                .filter(row -> row.key().equals(key))
                .toList();
        Capability capability = new CapabilityRegistryLoader(inMemory).load(spec);

        assertTrue(capability.executionEligible(), "the committed fixture backs every gate reference this "
                + "capability's spec declares, and both cp_gaia_* gate rows are SIGNED_OFF");
    }
}
