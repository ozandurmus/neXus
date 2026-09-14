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
        // device_confirm_check_point.yaml (the enrollment confirm's own committed registry entry,
        // NXS-LOCAL-0158) is the current committed spec fixture whose steps need no gate rows at
        // all -- cp_gaia_inventory.yaml (the earlier B1-5 narrow-subset capability this test used to
        // load) was superseded and removed by NXS-LOCAL-0159's cp_inventory_collect.yaml.
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/device_confirm_check_point.yaml"));
        List<GateRow> gates = GateRegistryFixtureLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));

        assertEquals("device_confirm_check_point", spec.capabilityId());

        GateRegistryPort inMemory = key -> gates.stream()
                .filter(row -> row.key().equals(key))
                .toList();
        Capability capability = new CapabilityRegistryLoader(inMemory).load(spec);

        assertTrue(capability.executionEligible(), "connect/disconnect are the only declared steps and neither "
                + "needs a gate reference, so this capability resolves execution-eligible with no gate rows at all");
    }
}
