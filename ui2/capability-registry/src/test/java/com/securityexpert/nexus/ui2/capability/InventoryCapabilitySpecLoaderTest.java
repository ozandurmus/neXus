package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * AC-5: the committed {@code cp_inventory_collect.yaml}/{@code
 * pan_inventory_collect.yaml} fixtures parse, stay {@code CAP_OFFLINE},
 * and resolve execution-eligible with no gate rows at all -- 14C §5: no
 * gate row is authored for either capability at this movement's scope.
 */
class InventoryCapabilitySpecLoaderTest {

    @Test
    void checkPointInventoryCapabilitySpecParsesAsCapOfflineAndExecutionEligible() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/cp_inventory_collect.yaml"));

        assertEquals("cp_inventory_collect", spec.capabilityId());
        assertEquals(MaturityState.CAP_OFFLINE, spec.maturityState());
        assertEquals(TransportKind.SSH_EXEC, spec.transportKind());

        Capability capability = new CapabilityRegistryLoader(key -> java.util.List.of()).load(spec);
        assertTrue(capability.executionEligible());
    }

    @Test
    void paloAltoInventoryCapabilitySpecParsesAsCapOfflineAndExecutionEligible() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/pan_inventory_collect.yaml"));

        assertEquals("pan_inventory_collect", spec.capabilityId());
        assertEquals(MaturityState.CAP_OFFLINE, spec.maturityState());
        assertEquals(TransportKind.PAN_XML_API, spec.transportKind());

        Capability capability = new CapabilityRegistryLoader(key -> java.util.List.of()).load(spec);
        assertTrue(capability.executionEligible());
    }
}
