package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * AC-5: against the real, committed {@code gate_registry_fixture.yaml}
 * (the same data V18 seeds into the runtime table), {@code cp_gateway_backup}
 * resolves execution-eligible, and {@link JobAdmissionService} admits a job
 * against an {@code ENROLLED} device but refuses one against a {@code DRAFT}
 * device. Mirrors {@code worker.configuration.
 * ConfigurationCapabilitiesGateAlignmentTest} exactly. The pilot-device
 * allowlist itself is not a {@link JobAdmissionService} concern (see {@code
 * worker.backup} / {@code service.device.backup} admission tests) -- this
 * test only proves the gate/capability layer resolves eligible.
 */
class BackupCapabilitiesGateAlignmentTest {

    private static GateRegistryPort realFixtureGateRegistry() {
        List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(
                BackupCapabilitiesGateAlignmentTest.class.getClassLoader()
                        .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    @Test
    void checkPointBackupCapabilityResolvesEligible() {
        var capability = BackupCapabilities.checkPoint(realFixtureGateRegistry());
        assertTrue(capability.executionEligible());
    }

    /** Measured live (2026-09-22): "Run Fleet Backup" with two Palo Alto targets issued nothing --
     * the capability id was known to admission but never registered (BackupCapabilities.all returned
     * Check Point only) and had no gate rows, so every PAN request was refused as unknown. */
    @Test
    void paloAltoBackupCapabilityResolvesEligibleAndIsRegistered() {
        var registry = realFixtureGateRegistry();
        assertTrue(BackupCapabilities.paloAlto(registry).executionEligible());
        assertTrue(BackupCapabilities.all(registry).stream()
                .anyMatch(c -> c.id().equals(com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.PAN_DEVICE_STATE_BACKUP)));
    }

    @Test
    void checkPointMdsExportCapabilityResolvesEligibleAndIsRegistered() {
        var registry = realFixtureGateRegistry();
        assertTrue(BackupCapabilities.checkPointMdsExport(registry).executionEligible());
        assertTrue(BackupCapabilities.all(registry).stream()
                .anyMatch(c -> c.id().equals(com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.CP_MDS_EXPORT)));
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

    private static JobAdmissionService admissionService(DeviceEnrollmentReadPort deviceReadPort) {
        var registry = CapabilityRegistry.of(List.of(BackupCapabilities.checkPoint(realFixtureGateRegistry())));
        return new JobAdmissionService(registry, deviceReadPort, new InMemoryAdmissionRepository());
    }

    @Test
    void admitsBackupCapabilityAgainstAnEnrolledDevice() {
        DeviceEnrollmentReadPort enrolledDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.ENROLLED, false));
        JobAdmissionService admission = admissionService(enrolledDevice);

        AdmissionResult result = admission.submit(BackupCapabilityIds.CP_GAIA_BACKUP_LOCAL, "device-1", null,
                "actor", "action");

        assertTrue(result instanceof AdmissionResult.Admitted, "expected Admitted, got " + result);
    }

    @Test
    void refusesBackupCapabilityAgainstADraftDevice() {
        DeviceEnrollmentReadPort draftDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.DRAFT, false));
        JobAdmissionService admission = admissionService(draftDevice);

        AdmissionResult result = admission.submit(BackupCapabilityIds.CP_GAIA_BACKUP_LOCAL, "device-1", null,
                "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused, "expected Refused against DRAFT, got " + result);
        assertTrue(((AdmissionResult.Refused) result).code().equals("DEVICE_NOT_ELIGIBLE"));
    }
}
