package com.securityexpert.nexus.ui2.persistence.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.Result;

/**
 * Adjudication F7's device-state port: a device state change never aborts
 * a run already {@code EXECUTING} (this port is called only after that
 * run's own attempt record is written, by contract, not enforced by this
 * class); this class's own job is the legal-transition guard, proved here
 * without a database.
 */
class JooqDeviceStatePortTest {

    private static final class FakeDeviceRepository implements DeviceRepository {
        final Map<String, DeviceRecord> byId = new HashMap<>();
        int transitionCalls = 0;
        boolean nextTransitionSucceeds = true;

        void put(DeviceRecord record) {
            byId.put(record.deviceId(), record);
        }

        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            return Optional.ofNullable(byId.get(deviceId));
        }

        @Override
        public Optional<EndpointRecord> findEndpoint(String endpointId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState,
                DeviceEnrollmentState toState, String actorFingerprint, String actionId) {
            transitionCalls++;
            if (nextTransitionSucceeds) {
                DeviceRecord existing = byId.get(deviceId);
                byId.put(deviceId, new DeviceRecord(existing.deviceId(), existing.vendorHint(),
                        existing.registrationSource(), existing.createdAt(), existing.isTestTarget(), toState,
                        existing.disabled(), existing.credentialReferenceId()));
            }
            return nextTransitionSucceeds;
        }

        @Override
        public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static DeviceRecord deviceWith(String deviceId, DeviceEnrollmentState state) {
        return new DeviceRecord(deviceId, "vendor-hint-synthetic", "manual_registration", Instant.now(), false,
                state, false, "cred-ref-synthetic");
    }

    @Test
    void failedContactMovesEnrolledToUnreachable() {
        FakeDeviceRepository repository = new FakeDeviceRepository();
        repository.put(deviceWith("dev-1", DeviceEnrollmentState.ENROLLED));
        JooqDeviceStatePort port = new JooqDeviceStatePort(repository);

        Result<DeviceEnrollmentState> result = port.recordContactOutcome("dev-1", false, "system:worker",
                "worker_contact_attempt");

        assertTrue(result.isOk());
        assertEquals(DeviceEnrollmentState.UNREACHABLE, ((Result.Ok<DeviceEnrollmentState>) result).value());
        assertEquals(1, repository.transitionCalls);
    }

    @Test
    void successfulContactMovesUnreachableToEnrolled() {
        FakeDeviceRepository repository = new FakeDeviceRepository();
        repository.put(deviceWith("dev-2", DeviceEnrollmentState.UNREACHABLE));
        JooqDeviceStatePort port = new JooqDeviceStatePort(repository);

        Result<DeviceEnrollmentState> result = port.recordContactOutcome("dev-2", true, "system:worker",
                "worker_contact_attempt");

        assertTrue(result.isOk());
        assertEquals(DeviceEnrollmentState.ENROLLED, ((Result.Ok<DeviceEnrollmentState>) result).value());
    }

    @Test
    void aDraftDeviceIsRefusedNeverSilentlyTransitioned() {
        // F6/§3: this port must never be the second path by which a DRAFT
        // device quietly gains ENROLLED/UNREACHABLE.
        FakeDeviceRepository repository = new FakeDeviceRepository();
        repository.put(deviceWith("dev-3", DeviceEnrollmentState.DRAFT));
        JooqDeviceStatePort port = new JooqDeviceStatePort(repository);

        Result<DeviceEnrollmentState> failure = port.recordContactOutcome("dev-3", true, "system:worker",
                "worker_contact_attempt");
        Result<DeviceEnrollmentState> failureOnFailedContact = port.recordContactOutcome("dev-3", false,
                "system:worker", "worker_contact_attempt");

        assertTrue(failure instanceof Result.Err);
        assertTrue(failureOnFailedContact instanceof Result.Err);
        assertEquals(0, repository.transitionCalls, "no write is attempted for an illegal transition");
        assertEquals(DeviceEnrollmentState.DRAFT, repository.find("dev-3").orElseThrow().enrollmentState());
    }

    @Test
    void unknownDeviceIdIsRefused() {
        FakeDeviceRepository repository = new FakeDeviceRepository();
        JooqDeviceStatePort port = new JooqDeviceStatePort(repository);

        Result<DeviceEnrollmentState> result = port.recordContactOutcome("no-such-device", true, "system:worker",
                "worker_contact_attempt");

        assertTrue(result instanceof Result.Err);
        assertEquals(0, repository.transitionCalls);
    }

    @Test
    void aLostRaceIsReportedNotSilentlyDropped() {
        FakeDeviceRepository repository = new FakeDeviceRepository();
        repository.put(deviceWith("dev-4", DeviceEnrollmentState.ENROLLED));
        repository.nextTransitionSucceeds = false;
        JooqDeviceStatePort port = new JooqDeviceStatePort(repository);

        Result<DeviceEnrollmentState> result = port.recordContactOutcome("dev-4", false, "system:worker",
                "worker_contact_attempt");

        assertTrue(result instanceof Result.Err);
        assertEquals(1, repository.transitionCalls);
    }
}
