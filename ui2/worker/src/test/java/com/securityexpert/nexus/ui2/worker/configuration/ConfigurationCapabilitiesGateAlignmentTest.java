package com.securityexpert.nexus.ui2.worker.configuration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.ConfigurationCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * AC-3: against the real, committed {@code gate_registry_fixture.yaml}
 * (the same data V16 seeds into the runtime table), both configuration
 * capabilities resolve execution-eligible, and {@link JobAdmissionService}
 * admits a job against an {@code ENROLLED} device for each but refuses one
 * against a {@code DRAFT} device (14B EC-J2: configuration collection is
 * never the enrollment-confirm exception). Mirrors {@code
 * worker.inventory.InventoryCapabilitiesGateAlignmentTest} exactly.
 */
class ConfigurationCapabilitiesGateAlignmentTest {

    private static GateRegistryPort realFixtureGateRegistry() {
        List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(
                ConfigurationCapabilitiesGateAlignmentTest.class.getClassLoader()
                        .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    @Test
    void checkPointCapabilityResolvesEligible() {
        var capability = ConfigurationCapabilities.checkPoint(realFixtureGateRegistry());
        assertTrue(capability.executionEligible());
    }

    @Test
    void paloAltoCapabilityResolvesEligible() {
        var capability = ConfigurationCapabilities.paloAlto(realFixtureGateRegistry());
        assertTrue(capability.executionEligible());
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
        public Optional<String> createRequestedIfAbsentForRun(String jobId, String idempotencyKey,
                String capabilityId, String targetRunId, String actionClassId, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public Optional<String> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(jobsByIdempotencyKey.get(idempotencyKey));
        }
    }

    private static JobAdmissionService admissionServiceWithBothCapabilities(DeviceEnrollmentReadPort deviceReadPort) {
        var registry = com.securityexpert.nexus.ui2.capability.CapabilityRegistry.of(List.of(
                ConfigurationCapabilities.checkPoint(realFixtureGateRegistry()),
                ConfigurationCapabilities.paloAlto(realFixtureGateRegistry())));
        return new JobAdmissionService(registry, deviceReadPort, new InMemoryAdmissionRepository());
    }

    @Test
    void admitsBothConfigurationCapabilitiesAgainstAnEnrolledDevice() {
        DeviceEnrollmentReadPort enrolledDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.ENROLLED, false));
        JobAdmissionService admission = admissionServiceWithBothCapabilities(enrolledDevice);

        AdmissionResult cpResult = admission.submit(ConfigurationCapabilityIds.CP_CONFIGURATION_COLLECT, "device-1",
                null, "actor", "action");
        AdmissionResult panResult = admission.submit(ConfigurationCapabilityIds.PAN_CONFIGURATION_COLLECT, "device-2",
                null, "actor", "action");

        assertTrue(cpResult instanceof AdmissionResult.Admitted, "expected Admitted, got " + cpResult);
        assertTrue(panResult instanceof AdmissionResult.Admitted, "expected Admitted, got " + panResult);
    }

    /** 14B EC-J2: every capability except the enrollment confirm keeps the unconditional DRAFT refusal. */
    @Test
    void refusesConfigurationCollectAgainstADraftDevice() {
        DeviceEnrollmentReadPort draftDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.DRAFT, false));
        JobAdmissionService admission = admissionServiceWithBothCapabilities(draftDevice);

        AdmissionResult result = admission.submit(ConfigurationCapabilityIds.CP_CONFIGURATION_COLLECT, "device-1",
                null, "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused, "expected Refused against DRAFT, got " + result);
        assertTrue(((AdmissionResult.Refused) result).code().equals("DEVICE_NOT_ELIGIBLE"));
    }
}
