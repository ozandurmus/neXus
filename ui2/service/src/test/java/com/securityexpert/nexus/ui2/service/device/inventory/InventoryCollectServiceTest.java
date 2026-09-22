package com.securityexpert.nexus.ui2.service.device.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
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
import com.securityexpert.nexus.ui2.jobs.admission.InventoryCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/** POST /devices/{id}/inventory/collect (WORKER.md "Routes"). */
class InventoryCollectServiceTest {

    private static final class FakeDeviceRepository implements DeviceRepository {
        final Map<String, DeviceRecord> byId = new HashMap<>();

        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            return Optional.ofNullable(byId.get(deviceId));
        }

        @Override
        public Optional<EndpointRecord> findEndpoint(String endpointId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<EndpointRecord> findEndpointByDeviceId(String deviceId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState,
                DeviceEnrollmentState toState, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public List<DeviceSummaryRecord> listAll() {
            throw new UnsupportedOperationException("not used by this test");
        }
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

    private static Capability inventoryCapability(String capabilityId, String vendor) {
        CapabilityStep exec = new CapabilityStep(StepKind.EXEC, "clish", "show configuration", false,
                Optional.of(ActionClass.CLASS_0_READ), Optional.of("^.*$"), Optional.of(30));
        CapabilitySpec spec = new CapabilitySpec(capabilityId, vendor, "cp_gaia_gateway", TransportKind.SSH_EXEC,
                MaturityState.CAP_OFFLINE, List.of(exec), List.of(), "UNKNOWN", List.of(), false);
        GateRegistryPort gates = key -> List.of(new GateRow("gate_ok", vendor, "cp_gaia_gateway", "clish",
                "SSH_EXEC", "show configuration", ActionClass.CLASS_0_READ, SignOffState.SIGNED_OFF, 30, null, null,
                null, null, null, List.of(), "test"));
        return new CapabilityRegistryLoader(gates).load(spec);
    }

    private static DeviceRecord enrolledDevice(String deviceId, String vendorHint) {
        return new DeviceRecord(deviceId, "gateway", vendorHint, "manual_registration", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-ref-1");
    }

    private static DeviceRecord enrolledManagementServer(String deviceId, String vendorHint) {
        return new DeviceRecord(deviceId, "management_server", vendorHint, "manual_registration", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-ref-1");
    }

    private static DeviceRecord enrolledUnrecognizedRole(String deviceId, String vendorHint, String role) {
        return new DeviceRecord(deviceId, role, vendorHint, "manual_registration", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-ref-1");
    }

    private static InventoryCollectService serviceFor(FakeDeviceRepository devices) {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of(
                inventoryCapability(InventoryCapabilityIds.CP_INVENTORY_COLLECT, "check_point"),
                inventoryCapability(InventoryCapabilityIds.PAN_INVENTORY_COLLECT, "palo_alto")));
        DeviceEnrollmentReadPort enrollmentReadPort = deviceId -> Optional.ofNullable(devices.byId.get(deviceId))
                .map(d -> new DeviceEnrollmentSnapshot(deviceId, d.enrollmentState(), d.disabled()));
        JobAdmissionService admissionService =
                new JobAdmissionService(registry, enrollmentReadPort, new InMemoryAdmissionRepository());
        return new InventoryCollectService(devices, admissionService);
    }

    @Test
    void unknownDeviceIsRefused() {
        InventoryCollectService service = serviceFor(new FakeDeviceRepository());

        InventoryCollectService.Outcome outcome = service.requestCollect("no-such-device", "actor", Optional.empty());

        assertTrue(outcome instanceof InventoryCollectService.Outcome.DeviceNotFound);
    }

    @Test
    void anEnrolledDeviceAdmitsTheVendorsInventoryCapabilityAndReturnsAJobId() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", enrolledDevice("device-1", "check_point"));
        InventoryCollectService service = serviceFor(devices);

        InventoryCollectService.Outcome outcome = service.requestCollect("device-1", "actor", Optional.of("nonce-1"));

        assertTrue(outcome instanceof InventoryCollectService.Outcome.Admitted, "expected Admitted, got " + outcome);
    }

    @Test
    void aManagementServerIsRefusedNamingTheMissingGate() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", enrolledManagementServer("device-1", "palo_alto"));
        InventoryCollectService service = serviceFor(devices);

        InventoryCollectService.Outcome outcome = service.requestCollect("device-1", "actor", Optional.empty());

        assertTrue(outcome instanceof InventoryCollectService.Outcome.AdmissionRefused, "expected AdmissionRefused, got " + outcome);
        InventoryCollectService.Outcome.AdmissionRefused refused = (InventoryCollectService.Outcome.AdmissionRefused) outcome;
        assertEquals("MANAGEMENT_SERVER_UNGATED", refused.code());
        assertTrue(refused.reason().contains("14I MS-2"), "the reason must name the missing gate");
    }

    @Test
    void aCheckPointManagementServerIsAdmittedOnTheGaiaReadSet() {
        // PO 2026-09-22: an SMS / MDS is a Gaia host; the gateway path's gated Expert reads apply.
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", enrolledManagementServer("device-1", "check_point"));
        InventoryCollectService service = serviceFor(devices);

        InventoryCollectService.Outcome outcome = service.requestCollect("device-1", "actor", Optional.empty());

        assertTrue(outcome instanceof InventoryCollectService.Outcome.Admitted, "expected Admitted, got " + outcome);
    }

    @Test
    void anUnrecognisedRoleIsRefusedNamingTheRole() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", enrolledUnrecognizedRole("device-1", "check_point", "future_role"));
        InventoryCollectService service = serviceFor(devices);

        InventoryCollectService.Outcome outcome = service.requestCollect("device-1", "actor", Optional.empty());

        assertTrue(outcome instanceof InventoryCollectService.Outcome.AdmissionRefused, "expected AdmissionRefused, got " + outcome);
        InventoryCollectService.Outcome.AdmissionRefused refused = (InventoryCollectService.Outcome.AdmissionRefused) outcome;
        assertEquals("ROLE_UNRECOGNISED", refused.code());
        assertTrue(refused.reason().contains("future_role"), "the reason must name the unrecognised role");
    }

    @Test
    void repeatingTheSameNonceDeduplicatesRatherThanCreatingASecondJob() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", enrolledDevice("device-1", "palo_alto"));
        InventoryCollectService service = serviceFor(devices);

        InventoryCollectService.Outcome first = service.requestCollect("device-1", "actor", Optional.of("nonce-1"));
        InventoryCollectService.Outcome second = service.requestCollect("device-1", "actor", Optional.of("nonce-1"));

        String firstJobId = ((InventoryCollectService.Outcome.Admitted) first).jobId();
        String secondJobId = ((InventoryCollectService.Outcome.Admitted) second).jobId();
        assertEquals(firstJobId, secondJobId);
    }

    @Test
    void aDraftDeviceIsRefused() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", new DeviceRecord("device-1", "gateway", "check_point", "manual_registration", Instant.now(),
                false, DeviceEnrollmentState.DRAFT, false, "cred-ref-1"));
        InventoryCollectService service = serviceFor(devices);

        InventoryCollectService.Outcome outcome = service.requestCollect("device-1", "actor", Optional.empty());

        assertTrue(outcome instanceof InventoryCollectService.Outcome.AdmissionRefused, "expected AdmissionRefused, got " + outcome);
        assertEquals("DEVICE_NOT_ELIGIBLE", ((InventoryCollectService.Outcome.AdmissionRefused) outcome).code());
    }
}
