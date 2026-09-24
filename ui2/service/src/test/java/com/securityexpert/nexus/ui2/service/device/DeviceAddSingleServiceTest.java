package com.securityexpert.nexus.ui2.service.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.ConfirmCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRecord;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * AC-1: add-single writes one DRAFT device and admits the vendor's own
 * confirm capability in one transaction; a refused admission leaves no
 * device row. {@link RollbackAwareTransactionBoundary} simulates jOOQ's
 * real nested-{@code transactionResult}-as-savepoint rollback (proved
 * against a real database only in integration-tests, out of this suite's
 * reach) so this fake-repository-only test can still prove the "no orphan
 * DRAFT row" half of AC-1 without one.
 */
class DeviceAddSingleServiceTest {

    private static final String CREDENTIAL_REF = "cred-ref-1";
    private static final String ADDRESS = "10.0.0.5";
    private static final String ACTOR = "actor-onboarding-1";

    private static final class DirectTransactionBoundary implements TransactionBoundary {
        @Override
        public <T> T inTransaction(Function<DSLContext, T> work) {
            return work.apply(null);
        }
    }

    /** Snapshots {@link FakeDeviceRepository}'s state before {@code work} and restores it if {@code work} throws. */
    private static final class RollbackAwareTransactionBoundary implements TransactionBoundary {
        private final FakeDeviceRepository devices;

        RollbackAwareTransactionBoundary(FakeDeviceRepository devices) {
            this.devices = devices;
        }

        @Override
        public <T> T inTransaction(Function<DSLContext, T> work) {
            List<DeviceDraft> registeredSnapshot = new ArrayList<>(devices.registered);
            Map<String, DeviceRecord> byIdSnapshot = new HashMap<>(devices.byId);
            try {
                return work.apply(null);
            } catch (RuntimeException e) {
                devices.registered.clear();
                devices.registered.addAll(registeredSnapshot);
                devices.byId.clear();
                devices.byId.putAll(byIdSnapshot);
                throw e;
            }
        }
    }

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
        public Optional<EndpointRecord> findEndpointByDeviceId(String deviceId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
            registered.add(draft);
            byId.put(draft.deviceId(), new DeviceRecord(draft.deviceId(), "gateway", draft.vendorHint(),
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

    private static final class FakeCredentialReferenceRepository implements CredentialReferenceRepository {
        @Override
        public boolean exists(String credentialReferenceId) {
            return CREDENTIAL_REF.equals(credentialReferenceId);
        }

        @Override
        public Optional<CredentialReferenceRecord> find(String credentialReferenceId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class InMemoryJobAdmissionRepository implements JobAdmissionRepository {
        final Map<String, String> jobsByIdempotencyKey = new HashMap<>();

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

    private static Capability confirmCapability(String capabilityId, String vendor, TransportKind transportKind) {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec(capabilityId, vendor, "role", transportKind,
                MaturityState.CAP_OFFLINE, List.of(connect), List.of(disconnect), "UNKNOWN", List.of(), false);
        return new CapabilityRegistryLoader(key -> List.of()).load(spec);
    }

    private static CapabilityRegistry confirmCapabilityRegistry() {
        return CapabilityRegistry.of(List.of(
                confirmCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT, "check_point",
                        TransportKind.SSH_EXEC),
                confirmCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_PALO_ALTO, "palo_alto",
                        TransportKind.PAN_XML_API)));
    }

    private static DeviceEnrollmentReadPort readPortOver(FakeDeviceRepository devices) {
        return deviceId -> Optional.ofNullable(devices.byId.get(deviceId))
                .map(d -> new DeviceEnrollmentSnapshot(d.deviceId(), d.enrollmentState(), d.disabled()));
    }

    @Test
    void checkPointHappyPathWritesOneDraftAndAdmitsTheCheckPointCapability() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        DeviceRegistrationService registrationService =
                new DeviceRegistrationService(devices, new FakeCredentialReferenceRepository());
        JobAdmissionService jobAdmissionService = new JobAdmissionService(confirmCapabilityRegistry(),
                readPortOver(devices), new InMemoryJobAdmissionRepository());
        DeviceAddSingleService service =
                new DeviceAddSingleService(new DirectTransactionBoundary(), registrationService, jobAdmissionService);

        DeviceAddSingleService.Outcome outcome = service.addSingle(ACTOR, "gateway", ADDRESS, "check_point", CREDENTIAL_REF);

        assertTrue(outcome instanceof DeviceAddSingleService.Outcome.Admitted, "expected Admitted, got " + outcome);
        DeviceAddSingleService.Outcome.Admitted admitted = (DeviceAddSingleService.Outcome.Admitted) outcome;
        assertEquals(1, devices.registered.size());
        assertEquals(admitted.deviceId(), devices.registered.get(0).deviceId());
        assertEquals("ssh_exec", devices.registered.get(0).transportKind());
    }

    @Test
    void paloAltoHappyPathWritesOneDraftAndAdmitsThePaloAltoCapability() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        DeviceRegistrationService registrationService =
                new DeviceRegistrationService(devices, new FakeCredentialReferenceRepository());
        JobAdmissionService jobAdmissionService = new JobAdmissionService(confirmCapabilityRegistry(),
                readPortOver(devices), new InMemoryJobAdmissionRepository());
        DeviceAddSingleService service =
                new DeviceAddSingleService(new DirectTransactionBoundary(), registrationService, jobAdmissionService);

        DeviceAddSingleService.Outcome outcome = service.addSingle(ACTOR, "gateway", ADDRESS, "palo_alto", CREDENTIAL_REF);

        assertTrue(outcome instanceof DeviceAddSingleService.Outcome.Admitted, "expected Admitted, got " + outcome);
        assertEquals(1, devices.registered.size());
        assertEquals("pan_xml_api", devices.registered.get(0).transportKind());
    }

    @Test
    void unknownVendorIsRefusedBeforeAnyWrite() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        DeviceRegistrationService registrationService =
                new DeviceRegistrationService(devices, new FakeCredentialReferenceRepository());
        DeviceEnrollmentReadPort neverCalled = deviceId -> {
            throw new AssertionError("device enrollment must never be consulted for an unknown vendor");
        };
        JobAdmissionService jobAdmissionService = new JobAdmissionService(confirmCapabilityRegistry(),
                neverCalled, new InMemoryJobAdmissionRepository());
        DeviceAddSingleService service =
                new DeviceAddSingleService(new DirectTransactionBoundary(), registrationService, jobAdmissionService);

        DeviceAddSingleService.Outcome outcome = service.addSingle(ACTOR, "gateway", ADDRESS, "cisco", CREDENTIAL_REF);

        assertTrue(outcome instanceof DeviceAddSingleService.Outcome.ValidationFailed,
                "expected ValidationFailed, got " + outcome);
        assertEquals(DeviceRegistrationService.REASON_VENDOR_HINT_INVALID,
                ((DeviceAddSingleService.Outcome.ValidationFailed) outcome).reasonCode());
        assertTrue(devices.registered.isEmpty(), "no draft is written for an unknown vendor");
    }

    @Test
    void aRefusedAdmissionRollsBackTheAlreadyWrittenDraft() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        DeviceRegistrationService registrationService =
                new DeviceRegistrationService(devices, new FakeCredentialReferenceRepository());
        // Every device this port reports is disabled: JobAdmissionService's
        // own F6 check refuses DEVICE_DISABLED for any device_id, including
        // the one DeviceRegistrationService just wrote inside this same
        // transaction.
        DeviceEnrollmentReadPort alwaysDisabled =
                deviceId -> Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.DRAFT, true));
        JobAdmissionService jobAdmissionService = new JobAdmissionService(confirmCapabilityRegistry(),
                alwaysDisabled, new InMemoryJobAdmissionRepository());
        DeviceAddSingleService service = new DeviceAddSingleService(new RollbackAwareTransactionBoundary(devices),
                registrationService, jobAdmissionService);

        DeviceAddSingleService.Outcome outcome = service.addSingle(ACTOR, "gateway", ADDRESS, "check_point", CREDENTIAL_REF);

        assertTrue(outcome instanceof DeviceAddSingleService.Outcome.AdmissionRefused,
                "expected AdmissionRefused, got " + outcome);
        assertEquals("DEVICE_DISABLED", ((DeviceAddSingleService.Outcome.AdmissionRefused) outcome).code());
        assertTrue(devices.registered.isEmpty(), "a refused admission must leave no orphan DRAFT row (AC-1)");
        assertTrue(devices.byId.isEmpty(), "a refused admission must leave no orphan DRAFT row (AC-1)");
    }

    @Test
    void aRadwareDeviceWithoutItsExportPassphraseIsRefusedBeforeAnyWrite() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        DeviceRegistrationService registrationService =
                new DeviceRegistrationService(devices, new FakeCredentialReferenceRepository());
        JobAdmissionService jobAdmissionService = new JobAdmissionService(confirmCapabilityRegistry(),
                readPortOver(devices), new InMemoryJobAdmissionRepository());
        DeviceAddSingleService service =
                new DeviceAddSingleService(new DirectTransactionBoundary(), registrationService, jobAdmissionService);

        DeviceAddSingleService.Outcome outcome = service.addSingle(ACTOR, "appliance", ADDRESS, "radware", CREDENTIAL_REF, Optional.empty());

        assertEquals(DeviceAddSingleService.REASON_EXPORT_PASSPHRASE_REQUIRED,
                ((DeviceAddSingleService.Outcome.ValidationFailed) outcome).reasonCode());
        assertTrue(devices.registered.isEmpty(), "no half-added device (PO, 2026-09-24)");
    }

    @Test
    void anExportPassphraseIsRefusedForAVendorThatTakesNone() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        DeviceRegistrationService registrationService =
                new DeviceRegistrationService(devices, new FakeCredentialReferenceRepository());
        JobAdmissionService jobAdmissionService = new JobAdmissionService(confirmCapabilityRegistry(),
                readPortOver(devices), new InMemoryJobAdmissionRepository());
        DeviceAddSingleService service =
                new DeviceAddSingleService(new DirectTransactionBoundary(), registrationService, jobAdmissionService);

        DeviceAddSingleService.Outcome outcome = service.addSingle(ACTOR, "gateway", ADDRESS, "check_point", CREDENTIAL_REF, Optional.of("cred-ref-2"));

        assertTrue(outcome instanceof DeviceAddSingleService.Outcome.ValidationFailed, "expected ValidationFailed, got " + outcome);
        assertTrue(devices.registered.isEmpty());
    }
}
