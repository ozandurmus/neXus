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
 * AC-3, PO_DECISION_RECORD_2026_09_14C D-1: {@code inventory_collect} is
 * admitted only against {@code ENROLLED}, non-disabled devices -- unlike
 * the enrollment confirm ({@code ConfirmCapabilityAdmissibleAgainstDraftTest}),
 * neither inventory capability is ever admissible against {@code DRAFT};
 * the confirm remains the one and only exception (14B EC-J1).
 */
class InventoryCapabilityAdmissionTest {

    private static Capability inventoryCapability(String capabilityId) {
        CapabilitySpec spec = new CapabilitySpec(capabilityId, "check_point", "cp_gaia_gateway", TransportKind.SSH_EXEC,
                MaturityState.CAP_OFFLINE, List.of(new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                        Optional.empty(), Optional.empty(), Optional.empty())),
                List.of(new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false, Optional.empty(),
                        Optional.empty(), Optional.empty())),
                "UNKNOWN", List.of(), false);
        GateRegistryPort gates = key -> List.of();
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
    void inventoryCollectIsRefusedAgainstADraftDevice() {
        CapabilityRegistry registry = CapabilityRegistry.of(
                List.of(inventoryCapability(InventoryCapabilityIds.CP_INVENTORY_COLLECT)));
        DeviceEnrollmentReadPort draftDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.DRAFT, false));
        JobAdmissionService admission = new JobAdmissionService(registry, draftDevice, new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submit(InventoryCapabilityIds.CP_INVENTORY_COLLECT, "device-draft-1", null,
                "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused, "expected Refused, got " + result);
        assertEquals("DEVICE_NOT_ELIGIBLE", ((AdmissionResult.Refused) result).code());
    }

    @Test
    void inventoryCollectIsAdmittedAgainstAnEnrolledNonDisabledDevice() {
        CapabilityRegistry registry = CapabilityRegistry.of(
                List.of(inventoryCapability(InventoryCapabilityIds.CP_INVENTORY_COLLECT)));
        DeviceEnrollmentReadPort enrolledDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.ENROLLED, false));
        JobAdmissionService admission =
                new JobAdmissionService(registry, enrolledDevice, new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submit(InventoryCapabilityIds.CP_INVENTORY_COLLECT, "device-1", null,
                "actor", "action");

        assertTrue(result instanceof AdmissionResult.Admitted, "expected Admitted, got " + result);
    }

    @Test
    void inventoryCollectIsRefusedAgainstADisabledEnrolledDevice() {
        CapabilityRegistry registry = CapabilityRegistry.of(
                List.of(inventoryCapability(InventoryCapabilityIds.PAN_INVENTORY_COLLECT)));
        DeviceEnrollmentReadPort disabledDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.ENROLLED, true));
        JobAdmissionService admission =
                new JobAdmissionService(registry, disabledDevice, new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submit(InventoryCapabilityIds.PAN_INVENTORY_COLLECT, "device-2", null,
                "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused);
        assertEquals("DEVICE_DISABLED", ((AdmissionResult.Refused) result).code());
    }

    @Test
    void isInventoryCapabilityRecognizesBothIds() {
        assertTrue(InventoryCapabilityIds.isInventoryCapability(InventoryCapabilityIds.CP_INVENTORY_COLLECT));
        assertTrue(InventoryCapabilityIds.isInventoryCapability(InventoryCapabilityIds.PAN_INVENTORY_COLLECT));
        assertEquals(false, InventoryCapabilityIds.isInventoryCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT));
    }
}
