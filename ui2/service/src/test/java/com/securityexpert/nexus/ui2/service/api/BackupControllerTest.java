package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository.BackupArtefactSummary;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository.PlaintextDigestSummary;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository.RetrievalManifest;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.device.backup.BackupCollectService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class BackupControllerTest {

    private StubManifestRepository manifestRepository;
    private BackupController controller;

    @BeforeEach
    void setUp() {
        StubDeviceRepository deviceRepo = new StubDeviceRepository().put("dev-01", "check_point");
        JobAdmissionService admissionService = admissionService();
        BackupCollectService collectService = new BackupCollectService(
                deviceRepo, admissionService, Set.of("dev-01"), true
        );
        manifestRepository = new StubManifestRepository();
        controller = new BackupController(collectService, manifestRepository);
    }

    @Test
    void collectRejectsMissingActorFingerprintWith403() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        // No actor fingerprint set on request attribute
        var body = new BackupController.CollectRequest("nonce-1", "Valid reason for backup", "backup");
        ResponseEntity<Map<String, Object>> response = controller.collect("dev-01", body, req);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("ACTOR_FINGERPRINT_MISSING", response.getBody().get("code"));
    }

    @Test
    void collectRejectsShortOrMissingReasonWith400() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "fp-actor-123");

        var bodyShort = new BackupController.CollectRequest("nonce-1", "short", "backup");
        ResponseEntity<Map<String, Object>> responseShort = controller.collect("dev-01", bodyShort, req);
        assertEquals(HttpStatus.BAD_REQUEST, responseShort.getStatusCode());
        assertEquals("REASON_TOO_SHORT", responseShort.getBody().get("code"));

        var bodyNull = new BackupController.CollectRequest("nonce-1", null, "backup");
        ResponseEntity<Map<String, Object>> responseNull = controller.collect("dev-01", bodyNull, req);
        assertEquals(HttpStatus.BAD_REQUEST, responseNull.getStatusCode());
        assertEquals("REASON_TOO_SHORT", responseNull.getBody().get("code"));
    }

    @Test
    void collectRejectsInvalidBackupTypeWith400() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "fp-actor-123");

        var body = new BackupController.CollectRequest("nonce-1", "Valid reason for test", "malicious_type");
        ResponseEntity<Map<String, Object>> response = controller.collect("dev-01", body, req);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_BACKUP_TYPE", response.getBody().get("code"));
    }

    @Test
    void collectRejectsPathTraversalInDeviceIdWith400() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "fp-actor-123");

        var body = new BackupController.CollectRequest("nonce-1", "Valid reason for test", "backup");
        ResponseEntity<Map<String, Object>> response = controller.collect("../etc/passwd", body, req);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_DEVICE_ID", response.getBody().get("error"));
    }

    @Test
    void collectAdmitsValidRequestWith202Accepted() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "fp-actor-123");

        var body = new BackupController.CollectRequest("nonce-abc", "Production maintenance pre-check", "snapshot");
        ResponseEntity<Map<String, Object>> response = controller.collect("dev-01", body, req);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody().get("job_id"));
        assertEquals("ACCEPTED", response.getBody().get("status"));
        assertEquals("snapshot", response.getBody().get("backup_type"));
    }

    @Test
    void getBackupPolicyReportsTruthful14DaysAndDepth4() {
        ResponseEntity<Map<String, Object>> response = controller.getBackupPolicy();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(14, response.getBody().get("backup_retention_days"));
        assertEquals(4, response.getBody().get("snapshot_retention_depth"));
        assertEquals("400Gi", response.getBody().get("storage_capacity"));
    }

    @Test
    void updateBackupPolicyRefusesMutationWith405() {
        ResponseEntity<Map<String, Object>> response = controller.updateBackupPolicy(Map.of("backup_retention_days", 60));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals("POLICY_IMMUTABLE", response.getBody().get("error"));
    }

    @Test
    void deviceBackupsRejectsPathTraversal() {
        ResponseEntity<Map<String, Object>> response = controller.deviceBackups("../../../etc/shadow");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void deviceBackupsReturnsOpaqueSummaries() {
        manifestRepository.items = List.of(
            new BackupArtefactSummary(
                "art-uuid-1", "dev-01", Instant.parse("2026-09-20T10:00:00Z"),
                1024L, "a1b2c3d4e5f6070809", "V2", Optional.of("UNCHANGED")
            )
        );

        ResponseEntity<Map<String, Object>> response = controller.deviceBackups("dev-01");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<?> backups = (List<?>) response.getBody().get("backups");
        assertEquals(1, backups.size());
        Map<?, ?> item = (Map<?, ?>) backups.get(0);
        assertEquals("art-uuid-1", item.get("artefact_id"));
        assertEquals("a1b2c3d4e5f6", item.get("digest_prefix"));
    }

    private static JobAdmissionService admissionService() {
        DeviceEnrollmentReadPort enrolledEverywhere = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.ENROLLED, false));
        Capability alwaysEligible = new Capability(BackupCapabilityIds.CP_GAIA_BACKUP_LOCAL, "check_point",
                "cp_gaia_gateway", TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED, List.of(), List.of(), Map.of(),
                true);
        Capability cpSnapshot = new Capability(BackupCapabilityIds.CP_GAIA_SNAPSHOT, "check_point",
                "cp_gaia_gateway", TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED, List.of(), List.of(), Map.of(),
                true);
        Capability panBackup = new Capability(BackupCapabilityIds.PAN_DEVICE_STATE_BACKUP, "palo_alto",
                "pan_os_firewall", TransportKind.PAN_XML_API, MaturityState.CAP_VALIDATED, List.of(), List.of(), Map.of(),
                true);
        return new JobAdmissionService(CapabilityRegistry.of(List.of(alwaysEligible, cpSnapshot, panBackup)), enrolledEverywhere,
                new InMemoryAdmissionRepository());
    }

    private static final class InMemoryAdmissionRepository implements JobAdmissionRepository {
        private final Map<String, String> jobsByIdempotencyKey = new HashMap<>();

        @Override
        public Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
                String targetDeviceId, String actionClassId, String actorFingerprint, String actionId) {
            jobsByIdempotencyKey.put(idempotencyKey, jobId);
            return Optional.of(jobId);
        }

        @Override
        public Optional<String> createRequestedIfAbsentForRun(String jobId, String idempotencyKey,
                String capabilityId, String targetRunId, String actionClassId, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(jobsByIdempotencyKey.get(idempotencyKey));
        }
    }

    private static final class StubDeviceRepository implements DeviceRepository {
        private final Map<String, DeviceRecord> devices = new HashMap<>();

        StubDeviceRepository put(String deviceId, String vendorHint) {
            devices.put(deviceId, new DeviceRecord(deviceId, "gateway", vendorHint, "manual", Instant.now(), false,
                    DeviceEnrollmentState.ENROLLED, false, "cred-collection-1"));
            return this;
        }

        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            return Optional.ofNullable(devices.get(deviceId));
        }

        @Override public Optional<EndpointRecord> findEndpoint(String endpointId) { throw new UnsupportedOperationException(); }
        @Override public Optional<EndpointRecord> findEndpointByDeviceId(String deviceId) { throw new UnsupportedOperationException(); }
        @Override public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) { throw new UnsupportedOperationException(); }
        @Override public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState, DeviceEnrollmentState toState, String actorFingerprint, String actionId) { throw new UnsupportedOperationException(); }
        @Override public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) { throw new UnsupportedOperationException(); }
        @Override public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) { throw new UnsupportedOperationException(); }
        @Override public boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint, String actionId) { throw new UnsupportedOperationException(); }
        @Override public Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId) { throw new UnsupportedOperationException(); }
        @Override public List<DeviceSummaryRecord> listAll() { throw new UnsupportedOperationException(); }
    }

    private static final class StubManifestRepository implements BackupArtefactManifestRepository {
        List<BackupArtefactSummary> items = List.of();

        @Override
        public void record(BackupArtefactManifestRecord manifest, String actorFingerprint, String actionId) {
        }

        @Override
        public Optional<PlaintextDigestSummary> findLatestPlaintextDigest(String deviceId, String artefactClass) {
            return Optional.empty();
        }

        @Override
        public List<BackupArtefactSummary> findByDevice(String deviceId, String artefactClass) {
            return items;
        }

        @Override
        public List<BackupArtefactSummary> findAll(String artefactClass) {
            return items;
        }

        @Override
        public Optional<RetrievalManifest> findForRetrieval(String artefactId) {
            return Optional.empty();
        }
    }
}
