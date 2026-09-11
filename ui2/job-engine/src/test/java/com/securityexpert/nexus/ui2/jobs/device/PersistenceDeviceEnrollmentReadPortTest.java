package com.securityexpert.nexus.ui2.jobs.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;

/**
 * B1-4b contract §8 test 1 ({@code draft_device_never_collected}), the
 * half provable without B1-4's {@code DeviceTransport}/job-admission code
 * (which does not exist yet, adjudication implementation order §3): a
 * fake {@code connect()} stands in for it, driven only by what {@link
 * DeviceEnrollmentReadPort} reports. If the read port ever reported a
 * DRAFT device as collectible, a real admission/claim check built on top
 * of it (B1-4's own) would let the fake connect proceed -- this test
 * proves that can never happen from this port's own side.
 */
class PersistenceDeviceEnrollmentReadPortTest {

    private static final class FakeDeviceRepository implements DeviceRepository {
        private final Map<String, DeviceRecord> byId = new HashMap<>();

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
    }

    /** Stands in for B1-4's {@code DeviceTransport.connect} for this test only. */
    private static final class FakeDeviceTransport {
        int connectCalls = 0;

        void connect() {
            connectCalls++;
        }
    }

    /** A minimal admission gate, shaped like the check F4/F6 describe, built only on this port. */
    private static void admitAndConnectIfPermitted(DeviceEnrollmentReadPort port, String deviceId,
            FakeDeviceTransport transport) {
        Optional<DeviceEnrollmentSnapshot> snapshot = port.findEnrollment(deviceId);
        if (snapshot.isPresent() && snapshot.get().permitsReadCollection()) {
            transport.connect();
        }
    }

    private static DeviceRecord deviceWith(String deviceId, DeviceEnrollmentState state, boolean disabled) {
        return new DeviceRecord(deviceId, "vendor-hint-synthetic", "manual_registration", Instant.now(), false,
                state, disabled, "cred-ref-synthetic");
    }

    @Test
    void draftDeviceNeverReachesConnect() {
        FakeDeviceRepository repository = new FakeDeviceRepository();
        repository.put(deviceWith("dev-1", DeviceEnrollmentState.DRAFT, false));
        DeviceEnrollmentReadPort port = new PersistenceDeviceEnrollmentReadPort(repository);
        FakeDeviceTransport transport = new FakeDeviceTransport();

        admitAndConnectIfPermitted(port, "dev-1", transport);

        assertEquals(0, transport.connectCalls, "a DRAFT device must never reach DeviceTransport.connect");
    }

    @Test
    void disabledEnrolledDeviceAlsoNeverReachesConnect() {
        FakeDeviceRepository repository = new FakeDeviceRepository();
        repository.put(deviceWith("dev-2", DeviceEnrollmentState.ENROLLED, true));
        DeviceEnrollmentReadPort port = new PersistenceDeviceEnrollmentReadPort(repository);
        FakeDeviceTransport transport = new FakeDeviceTransport();

        admitAndConnectIfPermitted(port, "dev-2", transport);

        assertEquals(0, transport.connectCalls, "a disabled device must never reach DeviceTransport.connect");
    }

    @Test
    void enrolledNonDisabledDeviceDoesReachConnect() {
        FakeDeviceRepository repository = new FakeDeviceRepository();
        repository.put(deviceWith("dev-3", DeviceEnrollmentState.ENROLLED, false));
        DeviceEnrollmentReadPort port = new PersistenceDeviceEnrollmentReadPort(repository);
        FakeDeviceTransport transport = new FakeDeviceTransport();

        admitAndConnectIfPermitted(port, "dev-3", transport);

        assertEquals(1, transport.connectCalls, "a sanity check: this harness itself is not vacuously refusing everything");
    }

    @Test
    void unknownDeviceIdNeverReachesConnect() {
        FakeDeviceRepository repository = new FakeDeviceRepository();
        DeviceEnrollmentReadPort port = new PersistenceDeviceEnrollmentReadPort(repository);
        FakeDeviceTransport transport = new FakeDeviceTransport();

        admitAndConnectIfPermitted(port, "no-such-device", transport);

        assertEquals(0, transport.connectCalls);
        assertTrue(port.findEnrollment("no-such-device").isEmpty());
        assertFalse(new DeviceEnrollmentSnapshot("x", DeviceEnrollmentState.DRAFT, false).permitsReadCollection());
    }
}
