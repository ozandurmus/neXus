package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds;

/**
 * 2026-11-18: cp_mds_export answered 409 CAPABILITY_NOT_EXECUTION_ELIGIBLE because this configuration's own copy of the
 * MDS literals still carried the pre-V206 "bash -lc" spellings, so those steps resolved UNKNOWN against the rewritten gate
 * rows. Every capability the service registers must resolve fully against the signed-off fixture.
 */
class CapabilityEligibilityAgainstFixtureTest {

    private static List<Capability> registered() {
        List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(CapabilityEligibilityAgainstFixtureTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        return new DeviceCompositionConfiguration().deviceCapabilityRegistry(key -> rows.stream()
                .filter(row -> row.key().equals(key)).toList()).all();
    }

    @Test
    void cpMdsExportIsExecutionEligible() {
        assertTrue(registered().stream().filter(c -> c.id().equals(BackupCapabilityIds.CP_MDS_EXPORT)).findFirst().orElseThrow()
                .executionEligible());
    }

    @Test
    void noRegisteredBackupCapabilityIsSilentlyIneligible() {
        String ineligible = registered().stream().filter(c -> BackupCapabilityIds.isBackupCapability(c.id()))
                .filter(c -> !c.executionEligible()).map(Capability::id).collect(Collectors.joining(","));
        assertTrue(ineligible.isEmpty(), "ineligible: " + ineligible);
    }
}
