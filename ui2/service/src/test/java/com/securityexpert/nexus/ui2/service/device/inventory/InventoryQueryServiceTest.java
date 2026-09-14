package com.securityexpert.nexus.ui2.service.device.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/** GET /devices/{id}/inventory and GET /clusters/{cluster_member_ref}/inventory (WORKER.md "Service read model"). */
class InventoryQueryServiceTest {

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
        return new DeviceRecord(deviceId, "check_point", "manual_registration", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-ref-1");
    }

    @Test
    void deviceInventoryIsNotFoundForAnUnknownDevice() {
        InventoryQueryService service = new InventoryQueryService(new FakeDeviceRepository(), new FakeJobRecordDao(),
                new InMemoryDeviceInventoryRepository());

        InventoryQueryService.DeviceInventoryOutcome outcome = service.deviceInventory("no-such-device");

        assertTrue(outcome instanceof InventoryQueryService.DeviceInventoryOutcome.NotFound);
    }

    @Test
    void deviceInventoryIsFoundWithNoRunWhenTheDeviceHasNeverBeenCollected() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1"));
        InventoryQueryService service = new InventoryQueryService(devices, new FakeJobRecordDao(),
                new InMemoryDeviceInventoryRepository());

        InventoryQueryService.DeviceInventoryOutcome outcome = service.deviceInventory("device-1");

        assertTrue(outcome instanceof InventoryQueryService.DeviceInventoryOutcome.Found);
        InventoryQueryService.DeviceInventoryOutcome.Found found = (InventoryQueryService.DeviceInventoryOutcome.Found) outcome;
        assertTrue(found.run().isEmpty());
    }

    @Test
    void deviceInventoryReturnsTheLatestRunAndItsJob() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1"));
        InMemoryDeviceInventoryRepository inventoryRepository = new InMemoryDeviceInventoryRepository();
        InventoryRun run = new InventoryRun("run-1", "device-1", "job-1", Instant.parse("2026-09-14T00:00:00Z"),
                List.of());
        inventoryRepository.recordRun(run);
        FakeJobRecordDao jobs = new FakeJobRecordDao();
        jobs.byId.put("job-1", new JobRow("job-1", "cp_inventory_collect", "device-1", "CLASS_0_READ", "COMPLETED",
                null, 0L, "SUCCESS", null));
        InventoryQueryService service = new InventoryQueryService(devices, jobs, inventoryRepository);

        InventoryQueryService.DeviceInventoryOutcome outcome = service.deviceInventory("device-1");

        InventoryQueryService.DeviceInventoryOutcome.Found found = (InventoryQueryService.DeviceInventoryOutcome.Found) outcome;
        assertEquals(run, found.run().orElseThrow());
        assertEquals("job-1", service.jobFor(found.run().get()).orElseThrow().jobId());
    }

    @Test
    void clusterInventoryIsNotFoundWhenNoDeviceCarriesTheRef() {
        InventoryQueryService service = new InventoryQueryService(new FakeDeviceRepository(), new FakeJobRecordDao(),
                new InMemoryDeviceInventoryRepository());

        InventoryQueryService.ClusterInventoryOutcome outcome = service.clusterInventory("cluster-1");

        assertTrue(outcome instanceof InventoryQueryService.ClusterInventoryOutcome.NotFound);
    }

    @Test
    void clusterInventoryMergesEveryMembersLatestRun() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.summaries = List.of(
                new DeviceSummaryRecord("dev-a", "check_point", DeviceEnrollmentState.ENROLLED, Optional.of("member-a"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("cluster-1")),
                new DeviceSummaryRecord("dev-b", "check_point", DeviceEnrollmentState.ENROLLED, Optional.of("member-b"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("cluster-1")),
                new DeviceSummaryRecord("dev-c", "check_point", DeviceEnrollmentState.ENROLLED, Optional.of("other"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        InMemoryDeviceInventoryRepository inventoryRepository = new InMemoryDeviceInventoryRepository();
        InventoryContext context = new InventoryContext(InventoryContext.PHYSICAL, List.of(), List.of());
        inventoryRepository.recordRun(new InventoryRun("run-a", "dev-a", "job-a", Instant.now(), List.of(context)));
        inventoryRepository.recordRun(new InventoryRun("run-b", "dev-b", "job-b", Instant.now(), List.of(context)));
        InventoryQueryService service = new InventoryQueryService(devices, new FakeJobRecordDao(), inventoryRepository);

        InventoryQueryService.ClusterInventoryOutcome outcome = service.clusterInventory("cluster-1");

        InventoryQueryService.ClusterInventoryOutcome.Found found = (InventoryQueryService.ClusterInventoryOutcome.Found) outcome;
        assertEquals(2, found.members().size(), "only devices carrying cluster-1 are members");
        assertEquals(1, found.contexts().size());
    }
}
