package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;

class DiagnosticCapabilityGateTest {
    @Test
    void diagnosticCapabilityResolvesItsExactSignedOffCommand() {
        List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        var capabilities = new DeviceCompositionConfiguration().deviceCapabilityRegistry(key -> rows.stream()
                .filter(row -> row.key().equals(key)).toList());
        assertTrue(capabilities.find(JobAdmissionService.FMG_INTERFACE_DETAIL).orElseThrow().executionEligible());
    }
}
