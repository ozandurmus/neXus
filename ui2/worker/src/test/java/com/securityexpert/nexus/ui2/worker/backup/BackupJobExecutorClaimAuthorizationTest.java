package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;

/**
 * NXS-LOCAL-0224 (BK-12/BW-4): the worker's own claim-time re-check of
 * {@code BackupCollectService}'s admission-time evidence -- a role removed
 * after admission, a device dropped from the pilot allowlist after
 * admission, and a job with no (or a malformed) {@code
 * backup_job_authorization} row all refuse the claim before any device
 * transport, never after. Mirrors {@code
 * BackupJobExecutorEndToEndTest}'s own harness shape, but the scripted
 * transport here is never expected to be touched -- {@code commandsIssued}
 * staying empty is itself part of each assertion.
 */
class BackupJobExecutorClaimAuthorizationTest {

    private static final String JOB_ID = "job-backup-claim-auth-1";
    private static final long LEASE_EPOCH = 11L;
    private static final String DEVICE_ID = "device-pilot-1";
    private static final String VALID_REASON = "operator requested a pre-maintenance backup";

    private static HostnameFingerprint testFingerprint() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        return HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    private static BackupRequest request() {
        return new BackupRequest(new ConnectionTarget("endpoint-1", "192.0.2.10", 22), Optional.of("cred-backup-1"),
                "utils.cp_ssh_trust");
    }

    private static final class Harness {
        final ScriptedBackupTransport transport = new ScriptedBackupTransport();
        final BackupJobExecutorFakes.FakeLeaseRepository leaseRepo =
                new BackupJobExecutorFakes.FakeLeaseRepository(JOB_ID, LEASE_EPOCH, JobState.CLAIMED);
        final BackupJobExecutorFakes.FakeStepAttemptRepository attemptRepo =
                new BackupJobExecutorFakes.FakeStepAttemptRepository();
        final BackupJobExecutorFakes.FakeDeviceEnrollmentReadPort enrollmentPort =
                new BackupJobExecutorFakes.FakeDeviceEnrollmentReadPort();
        final BackupJobExecutorFakes.FakeDeviceRepository deviceRepo = new BackupJobExecutorFakes.FakeDeviceRepository();
        final BackupJobExecutorFakes.FakeBackupArtefactManifestRepository manifestRepo =
                new BackupJobExecutorFakes.FakeBackupArtefactManifestRepository();
        final BackupJobExecutorFakes.FakeBackupEndpointEligibilityRepository eligibilityRepo =
                new BackupJobExecutorFakes.FakeBackupEndpointEligibilityRepository();
        final BackupJobExecutorFakes.FakeBackupJobAuthorizationRepository authorizationRepo =
                new BackupJobExecutorFakes.FakeBackupJobAuthorizationRepository();
        final BackupJobExecutorFakes.FakeRoleBindingRepository roleBindingRepo =
                new BackupJobExecutorFakes.FakeRoleBindingRepository();
        Set<String> pilotAllowlist = Set.of(DEVICE_ID);

        BackupJobExecutor executor(Path tempDir) {
            String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
            ArtefactStore artefactStore = new FileArtefactStore(tempDir, ArtefactStoreCipher.fromBase64Key(base64Key));
            BackupCapabilityExecutor capabilityExecutor = new BackupCapabilityExecutor(transport, artefactStore, 1L,
                    Duration.ofMillis(5), Duration.ofSeconds(5));
            return new BackupJobExecutor(leaseRepo, attemptRepo, enrollmentPort, deviceRepo, capabilityExecutor,
                    manifestRepo, eligibilityRepo, authorizationRepo, roleBindingRepo, pilotAllowlist,
                    testFingerprint(), tempDir.toString());
        }
    }

    @Test
    void aRevokedBackupAdminRoleRefusesTheClaimBeforeDeviceContact(@TempDir Path tempDir) {
        Harness harness = new Harness();
        harness.authorizationRepo.record(JOB_ID, DEVICE_ID, "actor-fingerprint-1", VALID_REASON,
                "backup_job_authorization_recorded");
        harness.roleBindingRepo.backupAdminBound = false; // role_binding_revoke happened after admission

        JobOutcome outcome = harness.executor(tempDir).execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.Rejected, "expected Rejected, got " + outcome);
        assertTrue(((JobOutcome.Rejected) outcome).reason().startsWith("BACKUP_AUTHORIZATION_STALE_AT_CLAIM_TIME"));
        assertTrue(harness.transport.commandsIssued.isEmpty(), "no device command is ever issued");
        assertEquals(JobState.REJECTED, harness.leaseRepo.currentState);
        assertTrue(harness.leaseRepo.transitions.contains("CLAIMED->REJECTED"));
        assertFalse(harness.leaseRepo.transitions.contains("CLAIMED->EXECUTING"), "never reaches EXECUTING");
        assertEquals(0, harness.manifestRepo.recorded.size());
    }

    @Test
    void aDeviceRemovedFromThePilotAllowlistRefusesTheClaimBeforeDeviceContact(@TempDir Path tempDir) {
        Harness harness = new Harness();
        harness.authorizationRepo.record(JOB_ID, DEVICE_ID, "actor-fingerprint-1", VALID_REASON,
                "backup_job_authorization_recorded");
        harness.pilotAllowlist = Set.of(); // the Product Owner emptied the pilot allowlist after admission

        JobOutcome outcome = harness.executor(tempDir).execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.Rejected, "expected Rejected, got " + outcome);
        assertTrue(((JobOutcome.Rejected) outcome).reason().startsWith("BACKUP_AUTHORIZATION_STALE_AT_CLAIM_TIME"));
        assertTrue(harness.transport.commandsIssued.isEmpty(), "no device command is ever issued");
        assertEquals(JobState.REJECTED, harness.leaseRepo.currentState);
        assertFalse(harness.leaseRepo.transitions.contains("CLAIMED->EXECUTING"), "never reaches EXECUTING");
    }

    @Test
    void aJobWithNoPersistedAuthorizationEvidenceRefusesTheClaim(@TempDir Path tempDir) {
        // A legacy row predating V23 (or a defect that admitted without recording evidence):
        // authorizationRepo is left empty for JOB_ID on purpose.
        Harness harness = new Harness();

        JobOutcome outcome = harness.executor(tempDir).execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.Rejected, "expected Rejected, got " + outcome);
        assertTrue(((JobOutcome.Rejected) outcome).reason().contains("no backup_job_authorization row"));
        assertTrue(harness.transport.commandsIssued.isEmpty(), "no device command is ever issued");
        assertEquals(JobState.REJECTED, harness.leaseRepo.currentState);
        assertFalse(harness.leaseRepo.transitions.contains("CLAIMED->EXECUTING"), "never reaches EXECUTING");
    }

    @Test
    void aMalformedReasonInThePersistedEvidenceRefusesTheClaim(@TempDir Path tempDir) {
        Harness harness = new Harness();
        harness.authorizationRepo.record(JOB_ID, DEVICE_ID, "actor-fingerprint-1", "short",
                "backup_job_authorization_recorded");

        JobOutcome outcome = harness.executor(tempDir).execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.Rejected, "expected Rejected, got " + outcome);
        assertTrue(((JobOutcome.Rejected) outcome).reason().contains("no reason of at least eight characters"));
        assertTrue(harness.transport.commandsIssued.isEmpty(), "no device command is ever issued");
        assertEquals(JobState.REJECTED, harness.leaseRepo.currentState);
    }

    @Test
    void aBlankActorFingerprintInThePersistedEvidenceRefusesTheClaim(@TempDir Path tempDir) {
        Harness harness = new Harness();
        harness.authorizationRepo.record(JOB_ID, DEVICE_ID, "  ", VALID_REASON, "backup_job_authorization_recorded");

        JobOutcome outcome = harness.executor(tempDir).execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.Rejected, "expected Rejected, got " + outcome);
        assertTrue(((JobOutcome.Rejected) outcome).reason().contains("no actor_fingerprint"));
        assertTrue(harness.transport.commandsIssued.isEmpty(), "no device command is ever issued");
    }

    @Test
    void validPersistedEvidenceProceedsPastTheAuthorizationCheck(@TempDir Path tempDir) {
        Harness harness = new Harness();
        harness.authorizationRepo.record(JOB_ID, DEVICE_ID, "actor-fingerprint-1", VALID_REASON,
                "backup_job_authorization_recorded");
        // No exec outputs are scripted, so the run fails downstream (at the
        // first device command) rather than completing -- the assertion
        // that matters here is only that the authorization gate itself let
        // the claim proceed to CLAIMED->EXECUTING and to device contact.

        harness.executor(tempDir).execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(harness.leaseRepo.transitions.contains("CLAIMED->EXECUTING"),
                "valid evidence must not be refused at the authorization gate");
        assertFalse(harness.transport.commandsIssued.isEmpty(), "device contact was attempted");
    }
}
