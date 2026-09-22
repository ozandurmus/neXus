package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

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
 * AC-1/AC-2/AC-3: the decisive backup executor test, against a scripted
 * {@link ScriptedBackupTransport} and a real {@link FileArtefactStore} (a
 * temp directory, never a shared artefact root) -- the happy path (submit,
 * poll, fetch, verify, delete, in order), a digest mismatch, a
 * never-terminal poll, a failed delete, and an unresolvable software
 * version. Mirrors {@code worker.configuration.
 * ConfigurationJobExecutorEndToEndTest}'s own shape.
 */
class BackupJobExecutorEndToEndTest {

    private static final String JOB_ID = "job-backup-1";
    private static final long LEASE_EPOCH = 7L;
    private static final String DEVICE_ID = "device-pilot-1";
    private static final String ARCHIVE_NAME = "backup_gw-a_20260914.tgz";

    private static ArtefactStore newArtefactStore(Path tempDir) {
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
        return new FileArtefactStore(tempDir, ArtefactStoreCipher.fromBase64Key(base64Key));
    }

    private static HostnameFingerprint testFingerprint() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        return HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    private static String sha256Hex(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static BackupRequest request() {
        return new BackupRequest(new ConnectionTarget("endpoint-1", "192.0.2.10", 22), Optional.of("cred-backup-1"),
                "utils.cp_ssh_trust");
    }

    private static ScriptedBackupTransport happyPathTransportUpTo(String terminalStatus) {
        ScriptedBackupTransport transport = new ScriptedBackupTransport();
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "1000000\n");
        transport.execOutputs.put(BackupReadPlan.CP_ADD_BACKUP_LOCAL, "Backup started: " + ARCHIVE_NAME + "\n");
        transport.statusSequence.add("in progress");
        transport.statusSequence.add(terminalStatus);
        return transport;
    }

    private static final class Harness {
        final ScriptedBackupTransport transport;
        final BackupJobExecutorFakes.FakeLeaseRepository leaseRepo;
        final BackupJobExecutorFakes.FakeStepAttemptRepository attemptRepo;
        final BackupJobExecutorFakes.FakeDeviceRepository deviceRepo;
        final BackupJobExecutorFakes.FakeBackupArtefactManifestRepository manifestRepo;
        final BackupJobExecutorFakes.FakeBackupEndpointEligibilityRepository eligibilityRepo;
        final BackupJobExecutor executor;
        final FakeLedger ledger = new FakeLedger();

        /** V45: in-memory device-side archive ledger. */
        static final class FakeLedger implements com.securityexpert.nexus.ui2.persistence.artefact.DeviceArchiveLedger {
            final java.util.LinkedHashMap<String, Boolean> deletedByName = new java.util.LinkedHashMap<>();

            @Override
            public void record(String deviceId, String archiveName) {
                deletedByName.putIfAbsent(archiveName, false);
            }

            @Override
            public void markDeleted(String deviceId, String archiveName) {
                deletedByName.put(archiveName, true);
            }

            @Override
            public java.util.List<String> pendingFor(String deviceId) {
                return deletedByName.entrySet().stream().filter(e -> !e.getValue()).map(java.util.Map.Entry::getKey).toList();
            }
        }

        Harness(ScriptedBackupTransport transport, ArtefactStore artefactStore, Duration pollInterval,
                Duration runDeadline, Path recoveryVolume) {
            this.transport = transport;
            this.leaseRepo = new BackupJobExecutorFakes.FakeLeaseRepository(JOB_ID, LEASE_EPOCH, JobState.CLAIMED);
            this.attemptRepo = new BackupJobExecutorFakes.FakeStepAttemptRepository();
            var enrollmentPort = new BackupJobExecutorFakes.FakeDeviceEnrollmentReadPort();
            this.deviceRepo = new BackupJobExecutorFakes.FakeDeviceRepository();
            this.deviceRepo.confirmFacts = Optional.of(BackupJobExecutorFakes.confirmFactsWithSoftwareVersion("R81.20"));
            this.manifestRepo = new BackupJobExecutorFakes.FakeBackupArtefactManifestRepository();
            this.eligibilityRepo = new BackupJobExecutorFakes.FakeBackupEndpointEligibilityRepository();
            BackupCapabilityExecutor capabilityExecutor =
                    new BackupCapabilityExecutor(transport, artefactStore, 1L, pollInterval, runDeadline, ledger);
            this.executor = new BackupJobExecutor(leaseRepo, attemptRepo, enrollmentPort, deviceRepo,
                    capabilityExecutor, manifestRepo, eligibilityRepo, testFingerprint(), recoveryVolume.toString());
        }
    }

    /** Measured live on a Gaia R81.20 gateway (2026-09-22) and in the reference trail: a
     * non-interactive "add backup local" only announces "Creating backup package..."; the archive
     * is named -- by full path -- in "show backup status" once it succeeds. The fetch and the digest
     * use that path; "delete backup" takes the bare file name. */
    @Test
    void takesTheArchivePathFromShowBackupStatusWhenTheSubmitDoesNotNameIt(@TempDir Path tempDir) throws Exception {
        String fullPath = "/var/log/CPbackup/backups/" + ARCHIVE_NAME;
        ScriptedBackupTransport transport = new ScriptedBackupTransport();
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "1000000\n");
        transport.execOutputs.put(BackupReadPlan.CP_ADD_BACKUP_LOCAL,
                "Creating backup package. Use the command 'show backup status' to monitor creation progress.\n");
        transport.statusSequence.add("Performing backup\nCreating Compressed Backup File [41%]\n");
        transport.statusSequence.add("Local backup succeeded.\nBackup file location: " + fullPath
                + "\nBackup process finished in 00:04 seconds\nBackup Date: 16-Sep-2026 03:01:05\n");
        transport.fetchedContent = "archive-bytes-content";
        String digestHex = sha256Hex(transport.fetchedContent);
        transport.execOutputs.put(BackupReadPlan.archiveDigestCommand(fullPath), digestHex + "  " + fullPath + "\n");
        transport.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");

        Harness harness = new Harness(transport, newArtefactStore(tempDir), Duration.ofMillis(5), Duration.ofSeconds(5),
                tempDir);
        JobOutcome outcome = harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        assertTrue(harness.transport.commandsIssued.contains(BackupReadPlan.archiveDigestCommand(fullPath)),
                "the digest is taken over the full path the status named");
        assertTrue(harness.transport.commandsIssued.contains(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME)),
                "delete backup takes the bare file name");
    }

    @Test
    void happyPathSubmitsPollsFetchesVerifiesAndDeletesInOrder(@TempDir Path tempDir) throws Exception {
        ScriptedBackupTransport transport = happyPathTransportUpTo("succeeded");
        transport.fetchedContent = "archive-bytes-content";
        String digestHex = sha256Hex(transport.fetchedContent);
        transport.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME), digestHex + "  " + ARCHIVE_NAME + "\n");
        transport.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");

        Harness harness = new Harness(transport, newArtefactStore(tempDir), Duration.ofMillis(5), Duration.ofSeconds(5),
                tempDir);
        JobOutcome outcome = harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        assertEquals(1, harness.manifestRepo.recorded.size());
        assertEquals("backup", harness.manifestRepo.recorded.get(0).artefactClass());
        assertEquals("first", harness.manifestRepo.recorded.get(0).deviationState().orElseThrow());
        assertTrue(harness.transport.commandsIssued.contains(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME)));
        assertFalse(harness.eligibilityRepo.isIneligible(DEVICE_ID));
        assertTrue(harness.transport.disconnectCalled);
        assertEquals(JobState.COMPLETED, harness.leaseRepo.currentState);

        // Order: free space, submit, poll(s), digest, delete -- the digest command runs strictly before the delete command.
        int digestIndex = harness.transport.commandsIssued.indexOf(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME));
        int deleteIndex = harness.transport.commandsIssued.indexOf(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME));
        assertTrue(digestIndex >= 0 && deleteIndex > digestIndex, "the digest must be verified before the delete");
    }

    @Test
    void digestMismatchFailsAndDeletesNothing(@TempDir Path tempDir) {
        ScriptedBackupTransport transport = happyPathTransportUpTo("succeeded");
        transport.fetchedContent = "archive-bytes-content";
        transport.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME), "0".repeat(64) + "  " + ARCHIVE_NAME + "\n");
        transport.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");

        Harness harness = new Harness(transport, newArtefactStore(tempDir), Duration.ofMillis(5), Duration.ofSeconds(5),
                tempDir);
        JobOutcome outcome = harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.Failed, "expected Failed, got " + outcome);
        assertTrue(((JobOutcome.Failed) outcome).terminalReason().contains("digest_mismatch"),
                "the failure must say which side differed: " + ((JobOutcome.Failed) outcome).terminalReason());
        assertFalse(harness.transport.commandsIssued.contains(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME)),
                "nothing is deleted on a digest mismatch");
        assertEquals(0, harness.manifestRepo.recorded.size(), "an unverified artefact is never recorded");
    }

    @Test
    void neverTerminalPollEndsOutcomeUnknownAndDeletesNothing(@TempDir Path tempDir) {
        ScriptedBackupTransport transport = new ScriptedBackupTransport();
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "1000000\n");
        transport.execOutputs.put(BackupReadPlan.CP_ADD_BACKUP_LOCAL, "Backup started: " + ARCHIVE_NAME + "\n");
        transport.statusSequence.add("in progress"); // stays IN_PROGRESS for the whole (short) run deadline

        Harness harness = new Harness(transport, newArtefactStore(tempDir), Duration.ofMillis(5), Duration.ofMillis(30),
                tempDir);
        JobOutcome outcome = harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.OutcomeUnknown, "expected OutcomeUnknown, got " + outcome);
        assertFalse(harness.transport.commandsIssued.contains(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME)));
        assertEquals(0, harness.manifestRepo.recorded.size());
        assertEquals(JobState.OUTCOME_UNKNOWN, harness.leaseRepo.currentState);
    }

    @Test
    void failedDeleteRecordsCleanupFailedAndMarksEndpointIneligible(@TempDir Path tempDir) throws Exception {
        ScriptedBackupTransport transport = happyPathTransportUpTo("succeeded");
        transport.fetchedContent = "archive-bytes-content";
        String digestHex = sha256Hex(transport.fetchedContent);
        transport.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME), digestHex + "  " + ARCHIVE_NAME + "\n");
        transport.execExitStatus.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), 1); // always fails

        Harness harness = new Harness(transport, newArtefactStore(tempDir), Duration.ofMillis(5), Duration.ofSeconds(5),
                tempDir);
        JobOutcome outcome = harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.Failed, "expected Failed, got " + outcome);
        assertTrue(((JobOutcome.Failed) outcome).terminalReason().startsWith("cleanup_failed"));
        assertEquals(1, harness.manifestRepo.recorded.size(), "the backup itself succeeded -- only cleanup failed");
        assertTrue(harness.eligibilityRepo.isIneligible(DEVICE_ID));
        long deleteAttempts = harness.transport.commandsIssued.stream()
                .filter(c -> c.equals(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME))).count();
        assertEquals(2, deleteAttempts, "one attempt plus its one retry (BK-7)");
    }

    @Test
    void unresolvableSoftwareVersionRefusesStoreWithZeroRows(@TempDir Path tempDir) throws Exception {
        ScriptedBackupTransport transport = happyPathTransportUpTo("succeeded");
        transport.fetchedContent = "archive-bytes-content";
        String digestHex = sha256Hex(transport.fetchedContent);
        transport.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME), digestHex + "  " + ARCHIVE_NAME + "\n");
        transport.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");

        Harness harness = new Harness(transport, newArtefactStore(tempDir), Duration.ofMillis(5), Duration.ofSeconds(5),
                tempDir);
        harness.deviceRepo.confirmFacts = Optional.empty(); // no observed software version resolvable

        JobOutcome outcome = harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.Failed, "expected Failed, got " + outcome);
        assertTrue(((JobOutcome.Failed) outcome).terminalReason().contains("backup_artefact_version_unresolvable"));
        assertEquals(0, harness.manifestRepo.recorded.size(), "C7 section 3.3: zero rows on an unresolvable version");
        try (var files = java.nio.file.Files.walk(tempDir)) {
            assertEquals(0, files.filter(p -> p.toString().endsWith(".enc")).count(),
                    "zero rows must also mean zero bytes: the refused run's archive is not left on the volume");
        }
    }

    /** V45: an archive an earlier run left on the device is deleted first, by its recorded name only; the new one is recorded and closed. */
    @Test
    void staleArchivesFromEarlierRunsAreDeletedBeforeTheSubmitAndTheNewOneIsClosed(@TempDir Path tempDir) throws Exception {
        ScriptedBackupTransport transport = happyPathTransportUpTo("succeeded");
        transport.fetchedContent = "archive-bytes-content";
        String digestHex = sha256Hex(transport.fetchedContent);
        transport.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME), digestHex + "  " + ARCHIVE_NAME + "\n");
        transport.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");
        String stale = "backup_--_gw-a_20260921.tgz";
        String vanished = "backup_--_gw-a_20260920.tgz";
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_BACKUPS, "Backup files:\n" + stale + "  2026-09-21\nbackup_--_admin_manual.tgz  2026-09-19\n");
        transport.execOutputs.put(BackupReadPlan.deleteBackupCommand(stale), "");

        Harness harness = new Harness(transport, newArtefactStore(tempDir), Duration.ofMillis(5), Duration.ofSeconds(5), tempDir);
        harness.ledger.record(DEVICE_ID, stale);
        harness.ledger.record(DEVICE_ID, vanished);

        JobOutcome outcome = harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        java.util.List<String> issued = harness.transport.commandsIssued;
        int listIndex = issued.indexOf(BackupReadPlan.CP_SHOW_BACKUPS);
        int staleDelete = issued.indexOf(BackupReadPlan.deleteBackupCommand(stale));
        int submit = issued.indexOf(BackupReadPlan.CP_ADD_BACKUP_LOCAL);
        assertTrue(listIndex >= 0 && staleDelete > listIndex && submit > staleDelete, "list, delete the stale name, then submit: " + issued);
        assertFalse(issued.contains(BackupReadPlan.deleteBackupCommand("backup_--_admin_manual.tgz")), "an archive the product did not create is left alone");
        assertFalse(issued.contains(BackupReadPlan.deleteBackupCommand(vanished)), "a name no longer listed is closed without a delete");
        assertEquals(java.util.Map.of(stale, true, vanished, true, ARCHIVE_NAME, true), harness.ledger.deletedByName,
                "the stale and vanished names are closed; this run's own archive is recorded and closed after its delete");
    }
}
