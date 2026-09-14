package com.securityexpert.nexus.ui2.service.device;

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

/** GET /devices/{id} and GET /devices (WORKER.md "Composition": "GET detail shape with and without facts, GET list shape"). */
class DeviceQueryServiceTest {

    private static final class FakeDeviceRepository implements DeviceRepository {
        final Map<String, DeviceRecord> byId = new HashMap<>();
        final Map<String, DeviceConfirmFacts> factsById = new HashMap<>();
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
            return Optional.ofNullable(factsById.get(deviceId));
        }

        @Override
        public List<DeviceSummaryRecord> listAll() {
            return summaries;
        }
    }

    private static final class FakeJobRecordDao implements JobRecordDao {
        final Map<String, JobRow> mostRecentByDeviceId = new HashMap<>();

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
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<JobRow> findMostRecentByTargetDeviceId(String targetDeviceId) {
            return Optional.ofNullable(mostRecentByDeviceId.get(targetDeviceId));
        }
    }

    private static DeviceRecord draftDevice(String deviceId) {
        return new DeviceRecord(deviceId, "check_point", "manual_registration", Instant.now(), false,
                DeviceEnrollmentState.DRAFT, false, "cred-ref-1");
    }

    private static DeviceConfirmFacts noFactsYet() {
        return new DeviceConfirmFacts(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), DeviceConfirmFacts.IDENTITY_MISMATCH_NONE, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), DeviceConfirmFacts.PEER_FOLLOW_NONE,
                Optional.empty());
    }

    private static DeviceConfirmFacts confirmedFacts() {
        return new DeviceConfirmFacts(Optional.of("fw-01"), Optional.of("model-x"), Optional.of("1.2.3"),
                Optional.of("active"), Optional.of("primary-id"), Optional.empty(),
                DeviceConfirmFacts.IDENTITY_MISMATCH_NONE, Optional.empty(), Optional.empty(),
                Optional.of("cluster-1"), Optional.empty(), DeviceConfirmFacts.PEER_FOLLOW_CORROBORATED,
                Optional.empty());
    }

    @Test
    void deviceDetailReturnsNotFoundForAnUnknownDevice() {
        DeviceQueryService service = new DeviceQueryService(new FakeDeviceRepository(), new FakeJobRecordDao());

        DeviceQueryService.DetailOutcome outcome = service.deviceDetail("no-such-device");

        assertTrue(outcome instanceof DeviceQueryService.DetailOutcome.NotFound);
    }

    @Test
    void deviceDetailWithoutFactsCarriesTheDraftDeviceAndItsMostRecentJob() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", draftDevice("device-1"));
        devices.factsById.put("device-1", noFactsYet());
        FakeJobRecordDao jobs = new FakeJobRecordDao();
        jobs.mostRecentByDeviceId.put("device-1",
                new JobRow("job-1", "device_confirm_check_point", "device-1", "CLASS_0_READ", "REQUESTED", null, 0L,
                        null, null, "device", null));
        DeviceQueryService service = new DeviceQueryService(devices, jobs);

        DeviceQueryService.DetailOutcome outcome = service.deviceDetail("device-1");

        assertTrue(outcome instanceof DeviceQueryService.DetailOutcome.Found, "expected Found, got " + outcome);
        DeviceQueryService.DetailOutcome.Found found = (DeviceQueryService.DetailOutcome.Found) outcome;
        assertEquals("device-1", found.device().deviceId());
        assertTrue(found.facts().observedHostname().isEmpty(), "no confirm has run yet");
        assertTrue(found.job().isPresent());
        assertEquals("job-1", found.job().get().jobId());
    }

    @Test
    void deviceDetailWithFactsCarriesTheObservedFactsAndNoJobWhenNoneExists() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-2", draftDevice("device-2"));
        devices.factsById.put("device-2", confirmedFacts());
        DeviceQueryService service = new DeviceQueryService(devices, new FakeJobRecordDao());

        DeviceQueryService.DetailOutcome outcome = service.deviceDetail("device-2");

        DeviceQueryService.DetailOutcome.Found found = (DeviceQueryService.DetailOutcome.Found) outcome;
        assertEquals(Optional.of("fw-01"), found.facts().observedHostname());
        assertEquals("CORROBORATED", found.facts().peerFollowOutcome());
        assertTrue(found.job().isEmpty(), "no job row exists for this device in this fixture");
    }

    @Test
    void listDevicesReturnsExactlyWhatTheRepositoryListsInOrder() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        DeviceSummaryRecord summary = new DeviceSummaryRecord("device-1", "palo_alto", DeviceEnrollmentState.ENROLLED,
                Optional.of("fw-01"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        devices.summaries = List.of(summary);
        DeviceQueryService service = new DeviceQueryService(devices, new FakeJobRecordDao());

        List<DeviceSummaryRecord> result = service.listDevices();

        assertEquals(List.of(summary), result);
    }
}
