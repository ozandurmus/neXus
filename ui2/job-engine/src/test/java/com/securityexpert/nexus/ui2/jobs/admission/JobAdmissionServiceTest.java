package com.securityexpert.nexus.ui2.jobs.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * Adjudication F4 (admission runs before REQUESTED) and F6 (a DRAFT target
 * is refused at admission -- the claim-time re-check is {@link
 * com.securityexpert.nexus.ui2.jobs.executor.StepExecutor}'s own concern,
 * exercised separately). Evaluation order asserted here matches {@link
 * JobAdmissionService}'s own javadoc: capability eligibility, then device
 * enrollment, then idempotency.
 */
class JobAdmissionServiceTest {

    private static Capability eligibleReadCapability() {
        CapabilityStep exec = new CapabilityStep(StepKind.EXEC, "clish", "show version", false,
                Optional.of(ActionClass.CLASS_0_READ), Optional.of("^.*$"), Optional.of(30));
        CapabilitySpec spec = new CapabilitySpec("cap_ok", "check_point", "cp_gaia_gateway", TransportKind.SSH_EXEC,
                MaturityState.CAP_OFFLINE, List.of(exec), List.of(), "UNKNOWN", List.of(), false);
        GateRegistryPort gates = key -> List.of(new GateRow("gate_ok", "check_point", "cp_gaia_gateway", "clish",
                "SSH_EXEC", "show version", ActionClass.CLASS_0_READ, SignOffState.SIGNED_OFF, 30, null, null, null,
                null, null, List.of(), "test"));
        return new CapabilityRegistryLoader(gates).load(spec);
    }

    private static final class InMemoryAdmissionRepository implements JobAdmissionRepository {
        private final Map<String, String> jobsByIdempotencyKey = new HashMap<>();
        private int createCalls = 0;

        @Override
        public Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
                String targetDeviceId, String actionClassId, String actorFingerprint, String actionId) {
            createCalls++;
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
    void unknownCapabilityIsRefusedBeforeAnyDeviceOrIdempotencyCheck() {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of());
        DeviceEnrollmentReadPort neverCalled = deviceId -> {
            throw new AssertionError("device check must not run when the capability is unknown");
        };
        JobAdmissionService admission = new JobAdmissionService(registry, neverCalled,
                new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submit("does-not-exist", "device-1", null, "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused);
        assertEquals("CAPABILITY_UNKNOWN", ((AdmissionResult.Refused) result).code());
    }

    @Test
    void draftDeviceIsRefusedAtAdmission() {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of(eligibleReadCapability()));
        DeviceEnrollmentReadPort draftDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.DRAFT, false));
        JobAdmissionService admission = new JobAdmissionService(registry, draftDevice,
                new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submit("cap_ok", "device-draft", null, "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused);
        assertEquals("DEVICE_NOT_ELIGIBLE", ((AdmissionResult.Refused) result).code());
    }

    @Test
    void disabledDeviceIsRefusedAtAdmissionRegardlessOfEnrollmentState() {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of(eligibleReadCapability()));
        DeviceEnrollmentReadPort disabledDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.ENROLLED, true));
        JobAdmissionService admission = new JobAdmissionService(registry, disabledDevice,
                new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submit("cap_ok", "device-disabled", null, "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused);
        assertEquals("DEVICE_DISABLED", ((AdmissionResult.Refused) result).code());
    }

    @Test
    void anEligibleCapabilityAndEnrolledDeviceIsAdmitted() {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of(eligibleReadCapability()));
        DeviceEnrollmentReadPort enrolledDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.ENROLLED, false));
        JobAdmissionService admission = new JobAdmissionService(registry, enrolledDevice,
                new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submit("cap_ok", "device-1", null, "actor", "action");

        assertTrue(result instanceof AdmissionResult.Admitted, "expected Admitted, got " + result);
    }

    @Test
    void aRepeatedClientSuppliedIdempotencyKeyNeverCreatesASecondJobRow() {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of(eligibleReadCapability()));
        DeviceEnrollmentReadPort enrolledDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.ENROLLED, false));
        InMemoryAdmissionRepository repository = new InMemoryAdmissionRepository();
        JobAdmissionService admission = new JobAdmissionService(registry, enrolledDevice, repository);

        AdmissionResult first = admission.submit("cap_ok", "device-1", "client-key-1", "actor", "action");
        AdmissionResult second = admission.submit("cap_ok", "device-1", "client-key-1", "actor", "action");

        assertTrue(first instanceof AdmissionResult.Admitted);
        assertTrue(second instanceof AdmissionResult.Deduplicated, "expected Deduplicated, got " + second);
        assertEquals(((AdmissionResult.Admitted) first).jobId(), ((AdmissionResult.Deduplicated) second).jobId());
        assertEquals(2, repository.createCalls, "the repository is asked to create twice, but the second "
                + "insert is a no-op de-duplication (C2 §2.3), never a second row");
    }
}
