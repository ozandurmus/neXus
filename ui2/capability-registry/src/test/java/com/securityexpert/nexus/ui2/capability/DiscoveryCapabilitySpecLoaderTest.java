package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 14F DR-1: the committed {@code cp_discovery_enumerate.yaml}/{@code
 * pan_discovery_enumerate.yaml} fixtures parse, stay {@code CAP_OFFLINE}
 * (the registry's {@link MaturityState} enum has no state expressing
 * "command gate approved, field bindings UNVERIFIED" -- see the YAML
 * files' own comments), and resolve execution-eligible with no gate rows
 * at all, mirroring {@code InventoryCapabilitySpecLoaderTest}.
 */
class DiscoveryCapabilitySpecLoaderTest {

    @Test
    void checkPointDiscoveryCapabilitySpecParsesAsCapOfflineAndExecutionEligible() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/cp_discovery_enumerate.yaml"));

        assertEquals("cp_discovery_enumerate", spec.capabilityId());
        assertEquals(MaturityState.CAP_OFFLINE, spec.maturityState());
        assertEquals(TransportKind.SSH_EXEC, spec.transportKind());

        Capability capability = new CapabilityRegistryLoader(key -> java.util.List.of()).load(spec);
        assertTrue(capability.executionEligible());
    }

    @Test
    void paloAltoDiscoveryCapabilitySpecParsesAsCapOfflineAndExecutionEligible() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/pan_discovery_enumerate.yaml"));

        assertEquals("pan_discovery_enumerate", spec.capabilityId());
        assertEquals(MaturityState.CAP_OFFLINE, spec.maturityState());
        assertEquals(TransportKind.PAN_XML_API, spec.transportKind());

        Capability capability = new CapabilityRegistryLoader(key -> java.util.List.of()).load(spec);
        assertTrue(capability.executionEligible());
    }
}
