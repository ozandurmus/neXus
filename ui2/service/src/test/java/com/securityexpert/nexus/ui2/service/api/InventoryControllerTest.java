package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.device.inventory.FakeDeviceInventoryRepository;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryCollectService;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryQueryService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/** WORKER.md AC-1: "Both GET routes return exactly the shared contract shapes (tests with fakes)... POST collect... returns 202 {job_id}." */
class InventoryControllerTest {

    @Test
    void resolvesVirtualSystemNamesForNumericContexts() {
        Map<String, String> mapped = InventoryController.resolveVsNames(
                List.of("physical", "2", "3"),
                List.of("GarantiPosAA", "GarantiWebAA"));
        assertEquals("GarantiPosAA", mapped.get("2"));
        assertEquals("GarantiWebAA", mapped.get("3"));
        assertEquals(null, mapped.get("physical"));
    }

    private static final class FakeDeviceRepository implements DeviceRepository {
        final Map<String, DeviceRecord> byId = new HashMap<>();
        List<DeviceSummaryRecord> summaries = List.of();

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
            return summaries;
        }
    }

    private static final class FakeJobRecordDao implements JobRecordDao {
        final Map<String, JobRow> byId = new HashMap<>();

        @Override
        public Optional<String> insertRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
                String targetDeviceId, String actionClass, String jobType, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<String> insertRequestedIfAbsentForRun(String jobId, String idempotencyKey,
                String capabilityId, String targetRunId, String actionClass, String jobType,
                String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<String> findJobIdByIdempotencyKey(String idempotencyKey) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<JobRow> find(String jobId) {
            return Optional.ofNullable(byId.get(jobId));
        }

        @Override
        public Optional<JobRow> findMostRecentByTargetDeviceId(String targetDeviceId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static DeviceRecord device(String deviceId) {
        return new DeviceRecord(deviceId, "gateway", "check_point", "manual_registration", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-ref-1");
    }

    @Test
    void getDeviceInventoryReturns404ForAnUnknownDevice() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        InventoryQueryService queryService =
                new InventoryQueryService(devices, new FakeJobRecordDao(), new FakeDeviceInventoryRepository());
        InventoryController controller = new InventoryController(queryService, unusedCollectService());

        ResponseEntity<Map<String, Object>> response = controller.getDeviceInventory("no-such-device");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("NOT_FOUND", response.getBody().get("error"));
    }

    @Test
    void getDeviceInventoryReturns200WithNullCollectedAtAndEmptyContextsWhenNeverCollected() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1"));
        InventoryQueryService queryService =
                new InventoryQueryService(devices, new FakeJobRecordDao(), new FakeDeviceInventoryRepository());
        InventoryController controller = new InventoryController(queryService, unusedCollectService());

        ResponseEntity<Map<String, Object>> response = controller.getDeviceInventory("device-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals("device-1", body.get("device_id"));
        assertEquals(null, body.get("collected_at"));
        assertEquals(null, body.get("job"));
        assertEquals(List.of(), body.get("contexts"));
    }

    @Test
    void getDeviceInventoryReturnsTheLatestRunShapedExactlyAsTheContract() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1"));
        FakeDeviceInventoryRepository inventoryRepository = new FakeDeviceInventoryRepository();
        InventoryInterface iface = new InventoryInterface("if-1", "eth0", Optional.empty(),
                InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP,
                List.of(new InventoryAddress("addr-1", "198.51.100.5/24", InventoryAddress.FAMILY_IPV4,
                        InventoryAddress.ROLE_MEMBER)));
        InventoryRoute route = new InventoryRoute("route-1", "0.0.0.0/0", Optional.of("198.51.100.1"),
                Optional.of("eth0"), InventoryRoute.PROTOCOL_DEFAULT, Optional.empty());
        InventoryContext context = new InventoryContext(InventoryContext.PHYSICAL, List.of(iface), List.of(route));
        Instant collectedAt = Instant.parse("2026-09-14T12:00:00Z");
        inventoryRepository.recordRun(new InventoryRun("run-1", "device-1", "job-1", collectedAt, 1, List.of(context)),
                "actor", "action-1");
        FakeJobRecordDao jobs = new FakeJobRecordDao();
        jobs.byId.put("job-1", new JobRow("job-1", "cp_inventory_collect", "device-1", "CLASS_0_READ", "COMPLETED",
                null, 0L, "SUCCESS", null, "device", null));
        InventoryQueryService queryService = new InventoryQueryService(devices, jobs, inventoryRepository);
        InventoryController controller = new InventoryController(queryService, unusedCollectService());

        ResponseEntity<Map<String, Object>> response = controller.getDeviceInventory("device-1");

        Map<String, Object> body = response.getBody();
        assertEquals("2026-09-14T12:00:00Z", body.get("collected_at"));
        @SuppressWarnings("unchecked")
        Map<String, Object> job = (Map<String, Object>) body.get("job");
        assertEquals("job-1", job.get("job_id"));
        assertEquals("COMPLETED", job.get("state"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contexts = (List<Map<String, Object>>) body.get("contexts");
        assertEquals(1, contexts.size());
        assertEquals("physical", contexts.get(0).get("context"));
    }

    /** AC-4: {@code vlan_id} on an interface and {@code ha} on its context, per migration V17. */
    @Test
    void getDeviceInventoryReturnsHaRoleAndVlanIdWhenTheRunRecordedThem() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1"));
        FakeDeviceInventoryRepository inventoryRepository = new FakeDeviceInventoryRepository();
        InventoryInterface iface = new InventoryInterface("if-1", "eth0.100", Optional.empty(),
                InventoryInterface.KIND_VLAN, InventoryInterface.STATE_UP, List.of(), Optional.of(100));
        InventoryContext context = new InventoryContext(InventoryContext.PHYSICAL, List.of(iface), List.of());
        InventoryHaFact haFact = new InventoryHaFact("ha-1", InventoryContext.PHYSICAL, "ACTIVE",
                Optional.of("High Availability"), InventoryHaFact.SOURCE_CP_CPHAPROB_STAT);
        Instant collectedAt = Instant.parse("2026-09-14T12:00:00Z");
        inventoryRepository.recordRun(
                new InventoryRun("run-1", "device-1", "job-1", collectedAt, 1, List.of(context), List.of(haFact)),
                "actor", "action-1");
        InventoryQueryService queryService =
                new InventoryQueryService(devices, new FakeJobRecordDao(), inventoryRepository);
        InventoryController controller = new InventoryController(queryService, unusedCollectService());

        ResponseEntity<Map<String, Object>> response = controller.getDeviceInventory("device-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contexts = (List<Map<String, Object>>) response.getBody().get("contexts");
        Map<String, Object> physicalContext = contexts.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> ha = (Map<String, Object>) physicalContext.get("ha");
        assertEquals("ACTIVE", ha.get("role"));
        assertEquals("High Availability", ha.get("cluster_mode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> interfaces = (List<Map<String, Object>>) physicalContext.get("interfaces");
        assertEquals(100, interfaces.get(0).get("vlan_id"));
    }

    @Test
    void getClusterInventoryReturns404WhenNoDeviceCarriesTheRef() {
        InventoryQueryService queryService = new InventoryQueryService(new FakeDeviceRepository(),
                new FakeJobRecordDao(), new FakeDeviceInventoryRepository());
        InventoryController controller = new InventoryController(queryService, unusedCollectService());

        ResponseEntity<Map<String, Object>> response = controller.getClusterInventory("no-such-cluster");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void collectReturns202WithJobIdOnAdmission() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1"));
        InventoryController controller = controllerWithRealCollectService(devices);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1");

        ResponseEntity<Map<String, Object>> response =
                controller.collect("device-1", new InventoryController.CollectRequest("nonce-1"), servletRequest);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(true, response.getBody().containsKey("job_id"));
    }

    @Test
    void collectReturns409AdmissionRefusedForAnUnknownDevice() {
        InventoryController controller = controllerWithRealCollectService(new FakeDeviceRepository());
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1");

        ResponseEntity<Map<String, Object>> response =
                controller.collect("no-such-device", null, servletRequest);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("ADMISSION_REFUSED", response.getBody().get("error"));
    }

    /** Satisfies {@link InventoryController}'s constructor for the GET-only tests below, which never invoke it. */
    private static InventoryCollectService unusedCollectService() {
        com.securityexpert.nexus.ui2.capability.CapabilityRegistry emptyRegistry =
                com.securityexpert.nexus.ui2.capability.CapabilityRegistry.of(List.of());
        com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService admissionService =
                new com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService(emptyRegistry,
                        deviceId -> Optional.empty(), new InMemoryAdmissionRepository());
        return new InventoryCollectService(new FakeDeviceRepository(), admissionService);
    }

    private static InventoryController controllerWithRealCollectService(FakeDeviceRepository devices) {
        com.securityexpert.nexus.ui2.capability.CapabilityRegistry registry =
                com.securityexpert.nexus.ui2.capability.CapabilityRegistry.of(List.of(
                        inventoryCapability(
                                com.securityexpert.nexus.ui2.jobs.admission.InventoryCapabilityIds.CP_INVENTORY_COLLECT,
                                "check_point")));
        com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort enrollmentReadPort = deviceId ->
                devices.find(deviceId).map(d -> new com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot(
                        deviceId, d.enrollmentState(), d.disabled()));
        com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService admissionService =
                new com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService(registry, enrollmentReadPort,
                        new InMemoryAdmissionRepository());
        InventoryCollectService collectService = new InventoryCollectService(devices, admissionService);
        InventoryQueryService queryService =
                new InventoryQueryService(devices, new FakeJobRecordDao(), new FakeDeviceInventoryRepository());
        return new InventoryController(queryService, collectService);
    }

    private static com.securityexpert.nexus.ui2.capability.Capability inventoryCapability(String capabilityId,
            String vendor) {
        com.securityexpert.nexus.ui2.capability.CapabilityStep exec =
                new com.securityexpert.nexus.ui2.capability.CapabilityStep(
                        com.securityexpert.nexus.ui2.capability.StepKind.EXEC, "clish", "show configuration", false,
                        Optional.of(com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ),
                        Optional.of("^.*$"), Optional.of(30));
        com.securityexpert.nexus.ui2.capability.CapabilitySpec spec =
                new com.securityexpert.nexus.ui2.capability.CapabilitySpec(capabilityId, vendor, "cp_gaia_gateway",
                        com.securityexpert.nexus.ui2.capability.TransportKind.SSH_EXEC,
                        com.securityexpert.nexus.ui2.capability.MaturityState.CAP_OFFLINE, List.of(exec), List.of(),
                        "UNKNOWN", List.of(), false);
        com.securityexpert.nexus.ui2.capability.GateRegistryPort gates = key -> List.of(
                new com.securityexpert.nexus.ui2.capability.GateRow("gate_ok", vendor, "cp_gaia_gateway", "clish",
                        "SSH_EXEC", "show configuration",
                        com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ,
                        com.securityexpert.nexus.ui2.capability.SignOffState.SIGNED_OFF, 30, null, null, null, null,
                        null, List.of(), "test"));
        return new com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader(gates).load(spec);
    }

    private static final class InMemoryAdmissionRepository
            implements com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository {
        private final Map<String, String> jobsByIdempotencyKey = new HashMap<>();

        @Override
        public Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
                String targetDeviceId, String actionClassId, String actorFingerprint, String actionId) {
            if (jobsByIdempotencyKey.containsKey(idempotencyKey)) {
                return Optional.empty();
            }
            jobsByIdempotencyKey.put(idempotencyKey, jobId);
            return Optional.of("job-" + jobId);
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
}
