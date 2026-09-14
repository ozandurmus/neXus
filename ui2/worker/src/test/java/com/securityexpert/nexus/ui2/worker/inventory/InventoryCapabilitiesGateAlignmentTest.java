package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilitySpecLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.InventoryCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * AC-1/AC-2/AC-4: {@link InventoryCapabilities}' real step literals equal
 * {@link InventoryReadPlan}'s own -- the same closed set 14D CF-3/14E PF-1
 * fix -- and, loaded against the real, committed {@code
 * gate_registry_fixture.yaml} (the same data V15 seeds into the runtime
 * table), both capabilities resolve execution-eligible and
 * {@link JobAdmissionService} admits a job against an {@code ENROLLED}
 * device for each.
 */
class InventoryCapabilitiesGateAlignmentTest {

    private static GateRegistryPort realFixtureGateRegistry() {
        List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(
                InventoryCapabilitiesGateAlignmentTest.class.getClassLoader()
                        .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    @Test
    void checkPointCapabilityStepLiteralsEqualTheReadPlanLiterallyAndResolveEligible() {
        var capability = InventoryCapabilities.checkPoint(realFixtureGateRegistry());
        assertTrue(capability.executionEligible());

        List<String> sends = capability.steps().stream()
                .filter(step -> step.commandTemplate() != null)
                .map(com.securityexpert.nexus.ui2.capability.CapabilityStep::commandTemplate)
                .toList();
        assertEquals(InventoryReadPlan.CHECK_POINT_PHYSICAL_READS, sends);
    }

    @Test
    void paloAltoCapabilityStepLiteralsEqualTheReadPlanLiterallyAndResolveEligible() {
        var capability = InventoryCapabilities.paloAlto(realFixtureGateRegistry());
        assertTrue(capability.executionEligible());

        List<String> sends = capability.steps().stream()
                .filter(step -> step.commandTemplate() != null)
                .map(com.securityexpert.nexus.ui2.capability.CapabilityStep::commandTemplate)
                .toList();
        assertEquals(InventoryReadPlan.PALO_ALTO_BASE_STEPS, sends);
    }

    /** AC-2: the committed capability YAML's own {@code send} literals equal {@link InventoryReadPlan}'s, in order. */
    @Test
    void cpInventoryCollectYamlStepLiteralsEqualTheReadPlanLiterally() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/cp_inventory_collect.yaml"));
        List<String> sends = spec.steps().stream()
                .map(com.securityexpert.nexus.ui2.capability.CapabilityStep::commandTemplate)
                .filter(java.util.Objects::nonNull)
                .toList();
        assertEquals(InventoryReadPlan.CHECK_POINT_PHYSICAL_READS, sends);
    }

    @Test
    void panInventoryCollectYamlStepLiteralsEqualTheReadPlanLiterally() {
        CapabilitySpec spec = CapabilitySpecLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/pan_inventory_collect.yaml"));
        List<String> sends = spec.steps().stream()
                .map(com.securityexpert.nexus.ui2.capability.CapabilityStep::commandTemplate)
                .filter(java.util.Objects::nonNull)
                .toList();
        assertEquals(InventoryReadPlan.PALO_ALTO_BASE_STEPS, sends);
    }

    private static final class InMemoryAdmissionRepository implements JobAdmissionRepository {
        private final Map<String, String> jobsByIdempotencyKey = new HashMap<>();

        @Override
        public Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
                String targetDeviceId, String actionClassId, String actorFingerprint, String actionId) {
            if (jobsByIdempotencyKey.containsKey(idempotencyKey)) {
                return Optional.empty();
            }
            jobsByIdempotencyKey.put(idempotencyKey, jobId);
            return Optional.of(jobId);
        }

        @Override
        public Optional<String> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(jobsByIdempotencyKey.get(idempotencyKey));
        }
    }

    @Test
    void jobAdmissionServiceAdmitsBothInventoryCapabilitiesAgainstAnEnrolledDeviceOnceTheRealGateRegistryIsLoaded() {
        var registry = com.securityexpert.nexus.ui2.capability.CapabilityRegistry.of(List.of(
                InventoryCapabilities.checkPoint(realFixtureGateRegistry()),
                InventoryCapabilities.paloAlto(realFixtureGateRegistry())));
        DeviceEnrollmentReadPort enrolledDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.ENROLLED, false));
        JobAdmissionService admission = new JobAdmissionService(registry, enrolledDevice, new InMemoryAdmissionRepository());

        AdmissionResult cpResult = admission.submit(InventoryCapabilityIds.CP_INVENTORY_COLLECT, "device-1", null,
                "actor", "action");
        AdmissionResult panResult = admission.submit(InventoryCapabilityIds.PAN_INVENTORY_COLLECT, "device-2", null,
                "actor", "action");

        assertTrue(cpResult instanceof AdmissionResult.Admitted, "expected Admitted, got " + cpResult);
        assertTrue(panResult instanceof AdmissionResult.Admitted, "expected Admitted, got " + panResult);
    }
}
