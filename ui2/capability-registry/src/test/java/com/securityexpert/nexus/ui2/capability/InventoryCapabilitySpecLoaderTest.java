package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * AC-1/AC-5: the committed {@code cp_inventory_collect.yaml}/{@code
 * pan_inventory_collect.yaml} specs parse, declare {@code CAP_VALIDATED},
 * and resolve execution-eligible against the real, committed {@code
 * gate_registry_fixture.yaml} -- the same fixture {@code
 * GateRegistrySeeder}/{@code V15} seed the runtime table from -- with no
 * {@code AmbiguousGateResolutionException}. This is what "C4 execution
 * eligibility is computed from gate resolution instead of a connect-only
 * placeholder" means in code: unlike the pre-NXS-LOCAL-0164 revision of
 * this test, an <em>empty</em> gate registry now leaves both capabilities
 * execution-<b>ineligible</b>, because their steps carry real command
 * literals that must resolve against real, {@code SIGNED_OFF} rows.
 */
class InventoryCapabilitySpecLoaderTest {

    private static GateRegistryPort realFixtureGateRegistry() {
        List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(
                InventoryCapabilitySpecLoaderTest.class.getClassLoader()
                        .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    @Test
    void checkPointInventoryCapabilitySpecParsesAsCapValidatedAndResolvesEligibleAgainstTheRealGateFixture() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/cp_inventory_collect.yaml"));

        assertEquals("cp_inventory_collect", spec.capabilityId());
        assertEquals(MaturityState.CAP_VALIDATED, spec.maturityState());
        assertEquals(TransportKind.SSH_EXEC, spec.transportKind());
        assertEquals(8, spec.allSteps().size(), "connect + six CF-3 physical reads + disconnect");

        Capability capability = new CapabilityRegistryLoader(realFixtureGateRegistry()).load(spec);
        assertTrue(capability.executionEligible(), "every CF-3 physical read literal has a SIGNED_OFF gate row");
    }

    @Test
    void checkPointInventoryCapabilityIsExecutionIneligibleAgainstAnEmptyGateRegistry() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/cp_inventory_collect.yaml"));

        Capability capability = new CapabilityRegistryLoader(key -> List.of()).load(spec);
        assertTrue(!capability.executionEligible(),
                "unlike a connect-only placeholder, real exec steps require real gate rows to resolve KNOWN");
    }

    @Test
    void paloAltoInventoryCapabilitySpecParsesAsCapValidatedAndResolvesEligibleAgainstTheRealGateFixture() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/pan_inventory_collect.yaml"));

        assertEquals("pan_inventory_collect", spec.capabilityId());
        assertEquals(MaturityState.CAP_VALIDATED, spec.maturityState());
        assertEquals(TransportKind.PAN_XML_API, spec.transportKind());
        assertEquals(6, spec.allSteps().size(), "connect + PF-1's four requests + disconnect");

        Capability capability = new CapabilityRegistryLoader(realFixtureGateRegistry()).load(spec);
        assertTrue(capability.executionEligible(), "every PF-1 request literal has a SIGNED_OFF gate row");
    }

    @Test
    void paloAltoInventoryCapabilityIsExecutionIneligibleAgainstAnEmptyGateRegistry() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/pan_inventory_collect.yaml"));

        Capability capability = new CapabilityRegistryLoader(key -> List.of()).load(spec);
        assertTrue(!capability.executionEligible(),
                "unlike a connect-only placeholder, real xml_api_call steps require real gate rows to resolve KNOWN");
    }
}
