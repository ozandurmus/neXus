package com.securityexpert.nexus.ui2.service.device.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupJobAuthorizationRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupJobAuthorizationRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * WORKER.md "the admission tests (in and out of the allowlist)": 14H BK-1
 * (the pilot allowlist, empty by default refuses every device), the
 * reason-length gate (BK-12), and the missing-backup-credential refusal.
 */
class BackupCollectServiceTest {

    private static final String PILOT_DEVICE = "device-pilot-1";
    private static final String OTHER_DEVICE = "device-not-pilot";
    private static final String VALID_REASON = "operator requested a pre-maintenance backup";

    private static final class StubDeviceRepository implements DeviceRepository {
        private final Map<String, DeviceRecord> devices = new HashMap<>();

        StubDeviceRepository put(String deviceId, String vendorHint) {
            devices.put(deviceId, new DeviceRecord(deviceId, "gateway", vendorHint, "manual", Instant.now(), false,
                    DeviceEnrollmentState.ENROLLED, false, "cred-collection-1"));
            return this;
        }

        StubDeviceRepository putManagementServer(String deviceId, String vendorHint) {
            devices.put(deviceId, new DeviceRecord(deviceId, "management_server", vendorHint, "manual", Instant.now(), false,
                    DeviceEnrollmentState.ENROLLED, false, "cred-collection-1"));
            return this;
        }

        StubDeviceRepository putUnrecognizedRole(String deviceId, String vendorHint, String role) {
            devices.put(deviceId, new DeviceRecord(deviceId, role, vendorHint, "manual", Instant.now(), false,
                    DeviceEnrollmentState.ENROLLED, false, "cred-collection-1"));
            return this;
        }

        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            return Optional.ofNullable(devices.get(deviceId));
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
        public boolean recordConfirmSuccess(String deviceId,
                com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts facts, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts> findConfirmFacts(
                String deviceId) {
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

    private static final class InMemoryAuthorizationRepository implements BackupJobAuthorizationRepository {
        private final Map<String, BackupJobAuthorizationRecord> byJobId = new HashMap<>();

        @Override
        public void record(String jobId, String deviceId, String actorFingerprint, String reason, String actionId) {
            byJobId.put(jobId, new BackupJobAuthorizationRecord(jobId, deviceId, actorFingerprint, reason));
        }

        @Override
        public Optional<BackupJobAuthorizationRecord> find(String jobId) {
            return Optional.ofNullable(byJobId.get(jobId));
        }
    }

    private static JobAdmissionService admissionService() {
        DeviceEnrollmentReadPort enrolledEverywhere = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.ENROLLED, false));
        Capability alwaysEligible = new Capability(BackupCapabilityIds.CP_GAIA_BACKUP_LOCAL, "check_point",
                "cp_gaia_gateway", TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED, List.of(), List.of(), Map.of(),
                true);
        return new JobAdmissionService(CapabilityRegistry.of(List.of(alwaysEligible)), enrolledEverywhere,
                new InMemoryAdmissionRepository());
    }

    @Test
    void refusesAReasonShorterThanEightCharacters() {
        BackupCollectService service = new BackupCollectService(new StubDeviceRepository().put(PILOT_DEVICE, "check_point"),
                admissionService(), Set.of(PILOT_DEVICE), true, new InMemoryAuthorizationRepository());

        BackupCollectService.Outcome outcome = service.requestCollect(PILOT_DEVICE, "actor", "short", Optional.empty());

        assertTrue(outcome instanceof BackupCollectService.Outcome.AdmissionRefused);
        assertEquals("REASON_TOO_SHORT", ((BackupCollectService.Outcome.AdmissionRefused) outcome).code());
    }

    @Test
    void admitsTheAllowlistedPilotDevice() {
        BackupCollectService service = new BackupCollectService(new StubDeviceRepository().put(PILOT_DEVICE, "check_point"),
                admissionService(), Set.of(PILOT_DEVICE), true, new InMemoryAuthorizationRepository());

        BackupCollectService.Outcome outcome =
                service.requestCollect(PILOT_DEVICE, "actor", VALID_REASON, Optional.empty());

        assertTrue(outcome instanceof BackupCollectService.Outcome.Admitted, "expected Admitted, got " + outcome);
    }

    @Test
    void recordsImmutableAuthorizationEvidenceOnAdmission() {
        InMemoryAuthorizationRepository authorizationRepository = new InMemoryAuthorizationRepository();
        BackupCollectService service = new BackupCollectService(new StubDeviceRepository().put(PILOT_DEVICE, "check_point"),
                admissionService(), Set.of(PILOT_DEVICE), true, authorizationRepository);

        BackupCollectService.Outcome outcome =
                service.requestCollect(PILOT_DEVICE, "actor-fingerprint-1", VALID_REASON, Optional.empty());

        assertTrue(outcome instanceof BackupCollectService.Outcome.Admitted, "expected Admitted, got " + outcome);
        String jobId = ((BackupCollectService.Outcome.Admitted) outcome).jobId();
        BackupJobAuthorizationRecord evidence = authorizationRepository.find(jobId)
                .orElseThrow(() -> new AssertionError("expected authorization evidence for job " + jobId));
        assertEquals(PILOT_DEVICE, evidence.deviceId());
        assertEquals("actor-fingerprint-1", evidence.actorFingerprint());
        assertEquals(VALID_REASON, evidence.reason());
    }

    @Test
    void refusesAManagementServerNamingTheMissingGate() {
        BackupCollectService service = new BackupCollectService(new StubDeviceRepository().putManagementServer(PILOT_DEVICE, "check_point"),
                admissionService(), Set.of(PILOT_DEVICE), true, new InMemoryAuthorizationRepository());

        BackupCollectService.Outcome outcome = service.requestCollect(PILOT_DEVICE, "actor", VALID_REASON, Optional.empty());

        assertTrue(outcome instanceof BackupCollectService.Outcome.AdmissionRefused, "expected AdmissionRefused, got " + outcome);
        BackupCollectService.Outcome.AdmissionRefused refused = (BackupCollectService.Outcome.AdmissionRefused) outcome;
        assertEquals("MANAGEMENT_SERVER_UNGATED", refused.code());
        assertTrue(refused.reason().contains("14I MS-2"), "the reason must name the missing gate");
    }

    @Test
    void refusesAnUnrecognisedRoleNamingTheRole() {
        BackupCollectService service = new BackupCollectService(new StubDeviceRepository().putUnrecognizedRole(PILOT_DEVICE, "check_point", "future_role"),
                admissionService(), Set.of(PILOT_DEVICE), true, new InMemoryAuthorizationRepository());

        BackupCollectService.Outcome outcome = service.requestCollect(PILOT_DEVICE, "actor", VALID_REASON, Optional.empty());

        assertTrue(outcome instanceof BackupCollectService.Outcome.AdmissionRefused, "expected AdmissionRefused, got " + outcome);
        BackupCollectService.Outcome.AdmissionRefused refused = (BackupCollectService.Outcome.AdmissionRefused) outcome;
        assertEquals("ROLE_UNRECOGNISED", refused.code());
        assertTrue(refused.reason().contains("future_role"), "the reason must name the unrecognised role");
    }

    @Test
    void refusesADeviceOutsideTheAllowlistNamingTheAllowlist() {
        BackupCollectService service = new BackupCollectService(
                new StubDeviceRepository().put(PILOT_DEVICE, "check_point").put(OTHER_DEVICE, "check_point"),
                admissionService(), Set.of(PILOT_DEVICE), true, new InMemoryAuthorizationRepository());

        BackupCollectService.Outcome outcome = service.requestCollect(OTHER_DEVICE, "actor", VALID_REASON, Optional.empty());

        assertTrue(outcome instanceof BackupCollectService.Outcome.AdmissionRefused, "expected AdmissionRefused, got " + outcome);
        BackupCollectService.Outcome.AdmissionRefused refused = (BackupCollectService.Outcome.AdmissionRefused) outcome;
        assertEquals("DEVICE_NOT_IN_BACKUP_PILOT_ALLOWLIST", refused.code());
        assertTrue(refused.reason().contains("allowlist"), "the reason must name the allowlist");
    }

    @Test
    void anEmptyAllowlistRefusesEveryDevice() {
        BackupCollectService service = new BackupCollectService(new StubDeviceRepository().put(PILOT_DEVICE, "check_point"),
                admissionService(), Set.of(), true, new InMemoryAuthorizationRepository());

        BackupCollectService.Outcome outcome =
                service.requestCollect(PILOT_DEVICE, "actor", VALID_REASON, Optional.empty());

        assertTrue(outcome instanceof BackupCollectService.Outcome.AdmissionRefused);
        assertEquals("DEVICE_NOT_IN_BACKUP_PILOT_ALLOWLIST",
                ((BackupCollectService.Outcome.AdmissionRefused) outcome).code());
    }

    @Test
    void refusesWhenNoBackupCredentialIsConfigured() {
        BackupCollectService service = new BackupCollectService(new StubDeviceRepository().put(PILOT_DEVICE, "check_point"),
                admissionService(), Set.of(PILOT_DEVICE), false, new InMemoryAuthorizationRepository());

        BackupCollectService.Outcome outcome =
                service.requestCollect(PILOT_DEVICE, "actor", VALID_REASON, Optional.empty());

        assertTrue(outcome instanceof BackupCollectService.Outcome.AdmissionRefused);
        assertEquals("BACKUP_CREDENTIAL_NOT_CONFIGURED",
                ((BackupCollectService.Outcome.AdmissionRefused) outcome).code());
    }
}
