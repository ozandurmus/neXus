package com.securityexpert.nexus.ui2.service.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRecord;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;

/**
 * B1-4b contract §8 tests 6, 8 and 9 -- the decision-logic half provable
 * without a database, mirroring {@code RoleBindingAdminServiceTest}'s
 * fake-repository style. Tests 2/3 (role gating) are proved at the
 * {@code GateChain}/{@code RbacEvaluator} layer
 * ({@link com.securityexpert.nexus.ui2.service.security.DeviceRegistrationGateTest}),
 * because the role check itself is E4's, not this class's.
 */
class DeviceRegistrationServiceTest {

    private static final String SYNTHETIC_ADDRESS_REF = "synthetic-mgmt-host.invalid";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final class FakeDeviceRepository implements DeviceRepository {
        final List<DeviceDraft> registered = new ArrayList<>();
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
        public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
            registered.add(draft);
            byId.put(draft.deviceId(), new DeviceRecord(draft.deviceId(), draft.vendorHint(),
                    draft.registrationSource(), Instant.now(), draft.isTestTarget(), DeviceEnrollmentState.DRAFT,
                    false, draft.credentialReferenceId()));
            return draft.deviceId();
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

    private static final class FakeCredentialReferenceRepository implements CredentialReferenceRepository {
        private final java.util.Set<String> existing = new java.util.HashSet<>();

        void addExisting(String id) {
            existing.add(id);
        }

        @Override
        public boolean exists(String credentialReferenceId) {
            return existing.contains(credentialReferenceId);
        }

        @Override
        public Optional<CredentialReferenceRecord> find(String credentialReferenceId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    @Test
    void registrationWritesOnlyToDevicesAndEndpointsAndNoOtherTable() {
        // Test 6 (registration_grants_no_write_class): the fake repository
        // records only devices/endpoints writes -- FakeDeviceRepository
        // exposes no method this service could call to reach a capability
        // registry / pilot allowlist / gate table (none is injected).
        FakeDeviceRepository devices = new FakeDeviceRepository();
        FakeCredentialReferenceRepository credentials = new FakeCredentialReferenceRepository();
        credentials.addExisting("cred-ref-1");
        DeviceRegistrationService service = new DeviceRegistrationService(devices, credentials);

        DeviceRegistrationService.Outcome outcome = service.register("actor-onboarding-1", "vendor-hint-synthetic",
                "ssh_exec", SYNTHETIC_ADDRESS_REF, "cred-ref-1", false);

        assertTrue(outcome instanceof DeviceRegistrationService.Outcome.Registered);
        assertEquals(1, devices.registered.size());
        DeviceRecord stored = devices.byId.get(((DeviceRegistrationService.Outcome.Registered) outcome).deviceId());
        assertEquals(DeviceEnrollmentState.DRAFT, stored.enrollmentState(),
                "every registration starts DRAFT (contract §3) and grants nothing beyond that row");
    }

    @Test
    void isTestTargetHasNoEffectOnValidationOrWriteOutcome() {
        // Test 8 (test_target_flag_is_not_a_write_bypass): the only thing
        // this service does with isTestTarget is pass it through to the
        // draft unchanged -- it never gates validation or credential
        // lookup on it.
        FakeDeviceRepository devices = new FakeDeviceRepository();
        FakeCredentialReferenceRepository credentials = new FakeCredentialReferenceRepository();
        credentials.addExisting("cred-ref-1");
        DeviceRegistrationService service = new DeviceRegistrationService(devices, credentials);

        service.register("actor-onboarding-1", "vendor-hint-synthetic", "ssh_exec", SYNTHETIC_ADDRESS_REF,
                "cred-ref-1", true);
        service.register("actor-onboarding-1", "vendor-hint-synthetic", "ssh_exec", SYNTHETIC_ADDRESS_REF,
                "cred-ref-1", false);

        assertEquals(2, devices.registered.size(), "is_test_target must not change whether registration succeeds");
        assertEquals(DeviceEnrollmentState.DRAFT, devices.byId.values().iterator().next().enrollmentState());
    }

    @Test
    void unknownCredentialReferenceIsRefusedBeforeAnyWrite() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        FakeCredentialReferenceRepository credentials = new FakeCredentialReferenceRepository();
        DeviceRegistrationService service = new DeviceRegistrationService(devices, credentials);

        DeviceRegistrationService.Outcome outcome = service.register("actor-onboarding-1", "vendor-hint-synthetic",
                "ssh_exec", SYNTHETIC_ADDRESS_REF, "no-such-credential-reference", false);

        assertTrue(outcome instanceof DeviceRegistrationService.Outcome.ValidationFailed);
        assertEquals(DeviceRegistrationService.REASON_CREDENTIAL_REFERENCE_NOT_FOUND,
                ((DeviceRegistrationService.Outcome.ValidationFailed) outcome).reasonCode());
        assertTrue(devices.registered.isEmpty(), "no devices/endpoints row on validation failure");
    }

    @Test
    void unimplementedTransportKindIsRefused() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        FakeCredentialReferenceRepository credentials = new FakeCredentialReferenceRepository();
        credentials.addExisting("cred-ref-1");
        DeviceRegistrationService service = new DeviceRegistrationService(devices, credentials);

        DeviceRegistrationService.Outcome outcome = service.register("actor-onboarding-1", "vendor-hint-synthetic",
                "some_future_transport", SYNTHETIC_ADDRESS_REF, "cred-ref-1", false);

        assertTrue(outcome instanceof DeviceRegistrationService.Outcome.ValidationFailed);
        assertEquals(DeviceRegistrationService.REASON_UNSUPPORTED_TRANSPORT,
                ((DeviceRegistrationService.Outcome.ValidationFailed) outcome).reasonCode());
        assertTrue(devices.registered.isEmpty());
    }

    @Test
    void generatedIdentifiersAreOpaqueUuidsNeverDerivedFromAddressOrVendor() {
        // Test 9 (opaque_identifiers_only).
        FakeDeviceRepository devices = new FakeDeviceRepository();
        FakeCredentialReferenceRepository credentials = new FakeCredentialReferenceRepository();
        credentials.addExisting("cred-ref-1");
        DeviceRegistrationService service = new DeviceRegistrationService(devices, credentials);

        DeviceRegistrationService.Outcome outcome = service.register("actor-onboarding-1", "vendor-hint-synthetic",
                "ssh_exec", SYNTHETIC_ADDRESS_REF, "cred-ref-1", false);
        var registered = (DeviceRegistrationService.Outcome.Registered) outcome;

        assertTrue(UUID_PATTERN.matcher(registered.deviceId()).matches(), "device_id must be an opaque UUID");
        assertTrue(UUID_PATTERN.matcher(registered.endpointId()).matches(), "endpoint_id must be an opaque UUID");
        assertNotEquals(registered.deviceId(), registered.endpointId());
        assertFalse(registered.deviceId().contains(SYNTHETIC_ADDRESS_REF));
        assertFalse(registered.deviceId().contains("vendor-hint-synthetic"));
        assertFalse(registered.endpointId().contains(SYNTHETIC_ADDRESS_REF));
    }

    @Test
    void blankVendorHintIsRefused() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        FakeCredentialReferenceRepository credentials = new FakeCredentialReferenceRepository();
        credentials.addExisting("cred-ref-1");
        DeviceRegistrationService service = new DeviceRegistrationService(devices, credentials);

        DeviceRegistrationService.Outcome outcome =
                service.register("actor-onboarding-1", "  ", "ssh_exec", SYNTHETIC_ADDRESS_REF, "cred-ref-1", false);

        assertTrue(outcome instanceof DeviceRegistrationService.Outcome.ValidationFailed);
        assertTrue(devices.registered.isEmpty());
    }
}
