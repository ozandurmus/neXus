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
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * PO_DECISION_RECORD_2026_09_14B EC-J1/EC-J2: proves both sides of the one
 * narrow exception to F4's {@code DRAFT} refusal in the same test class --
 * the confirm capability is admitted against {@code DRAFT}, while every
 * other capability (including a {@code DRAFT} target with the same device
 * id) keeps the unconditional refusal {@link JobAdmissionServiceTest}
 * already exercises for a non-confirm capability.
 */
class ConfirmCapabilityAdmissibleAgainstDraftTest {

    private static Capability confirmCapability(String capabilityId) {
        // A placeholder step list on purpose: the confirm capability's real device
        // contact runs through worker.confirm.ConfirmJobExecutor/ConfirmCapabilityExecutor
        // (the closed four-read set, DeviceFirstContactCommandSet), never through this
        // Capability's own steps -- see ConfirmJobExecutor's javadoc for why StepExecutor's
        // single-connectionTarget execute() cannot express the confirm's own two-phase
        // (self, then conditionally peer) shape. This registry entry exists only so
        // JobAdmissionService's step 1 (capability exists and is execution-eligible) has
        // something to resolve capabilityId against.
        CapabilitySpec spec = new CapabilitySpec(capabilityId, "check_point", "cp_gaia_gateway", TransportKind.SSH_EXEC,
                MaturityState.CAP_OFFLINE, List.of(new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                        Optional.empty(), Optional.empty(), Optional.empty())),
                List.of(new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false, Optional.empty(),
                        Optional.empty(), Optional.empty())),
                "UNKNOWN", List.of(), false);
        GateRegistryPort gates = key -> List.of();
        return new CapabilityRegistryLoader(gates).load(spec);
    }

    private static Capability nonConfirmReadCapability() {
        CapabilityStep exec = new CapabilityStep(StepKind.EXEC, "clish", "show version", false,
                Optional.of(com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ), Optional.of("^.*$"),
                Optional.of(30));
        CapabilitySpec spec = new CapabilitySpec("cap_ok", "check_point", "cp_gaia_gateway", TransportKind.SSH_EXEC,
                MaturityState.CAP_OFFLINE, List.of(exec), List.of(), "UNKNOWN", List.of(), false);
        GateRegistryPort gates = key -> List.of(new com.securityexpert.nexus.ui2.capability.GateRow("gate_ok",
                "check_point", "cp_gaia_gateway", "clish", "SSH_EXEC", "show version",
                com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ,
                com.securityexpert.nexus.ui2.capability.SignOffState.SIGNED_OFF, 30, null, null, null, null, null,
                List.of(), "test"));
        return new CapabilityRegistryLoader(gates).load(spec);
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
    void theEnrollmentConfirmIsAdmittedAgainstADraftDevice() {
        CapabilityRegistry registry = CapabilityRegistry.of(
                List.of(confirmCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT)));
        DeviceEnrollmentReadPort draftDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.DRAFT, false));
        JobAdmissionService admission = new JobAdmissionService(registry, draftDevice, new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submit(ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT, "device-draft-1",
                null, "actor", "action");

        assertTrue(result instanceof AdmissionResult.Admitted, "expected Admitted, got " + result);
    }

    @Test
    void everyOtherCapabilityKeepsTheDraftRefusalUnchanged() {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of(nonConfirmReadCapability()));
        DeviceEnrollmentReadPort draftDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.DRAFT, false));
        JobAdmissionService admission = new JobAdmissionService(registry, draftDevice, new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submit("cap_ok", "device-draft-1", null, "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused, "expected Refused, got " + result);
        assertEquals("DEVICE_NOT_ELIGIBLE", ((AdmissionResult.Refused) result).code());
    }

    @Test
    void theConfirmCapabilityIsStillRefusedAgainstADisabledDraftDevice() {
        // EC-J1's exception is only ever "DRAFT" -- disabled still refuses unconditionally,
        // for the confirm capability exactly as for every other one.
        CapabilityRegistry registry = CapabilityRegistry.of(
                List.of(confirmCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT)));
        DeviceEnrollmentReadPort disabledDraftDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.DRAFT, true));
        JobAdmissionService admission =
                new JobAdmissionService(registry, disabledDraftDevice, new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submit(ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT, "device-draft-2",
                null, "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused);
        assertEquals("DEVICE_DISABLED", ((AdmissionResult.Refused) result).code());
    }
}
