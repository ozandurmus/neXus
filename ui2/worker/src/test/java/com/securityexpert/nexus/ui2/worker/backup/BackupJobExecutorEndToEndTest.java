package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;
import java.io.IOException;
import java.io.InputStream;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;

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

    private static JobRecordDao transcriptJobs(AtomicReference<String> ref, AtomicReference<byte[]> key) {
        return (JobRecordDao) Proxy.newProxyInstance(JobRecordDao.class.getClassLoader(), new Class<?>[] {JobRecordDao.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("writeBackupTranscript")) {
                        ref.set((String) args[2]);
                        key.set((byte[]) args[3]);
                        return true;
                    }
                    throw new AssertionError("unexpected DAO call: " + method.getName());
                });
    }

    @Test
    void sparkRoutingAndUnresolvedRevisionNeverReachGaiaPrecheck(@TempDir Path dir) throws Exception {
        for (String model : java.util.List.of("1590", "V1")) {
            var transport = new ScriptedBackupTransport();
            var harness = new Harness(transport, newArtefactStore(dir.resolve(model)),
                    Duration.ofMillis(5), Duration.ofSeconds(5), dir);
            harness.deviceRepo.deviceRecord = Optional.of(new com.securityexpert.nexus.ui2.persistence.device.DeviceRecord(
                    DEVICE_ID, "gateway", "check_point", "synthetic", Instant.EPOCH, true,
                    com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED, false, "credential-1"));
            harness.deviceRepo.confirmFacts = Optional.of(new com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts(
                    Optional.empty(), Optional.of(model), Optional.of("R81.10.10"), Optional.empty(), Optional.empty(),
                    Optional.empty(), "NONE", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    "NONE", Optional.empty()));
            var failed = org.junit.jupiter.api.Assertions.assertInstanceOf(JobOutcome.Failed.class,
                    harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request()));
            assertTrue(failed.terminalReason().contains(model.equals("V1")
                    ? "Spark model unresolved" : "Spark backup executor is not configured"));
            assertTrue(transport.commandsIssued.isEmpty());
        }
    }

    @Test
    void revisionOnlyDeviceWithPersistedSparkPlatformRoutesToSparkBackup(@TempDir Path dir) throws Exception {
        var transport = new ScriptedBackupTransport();
        var harness = new Harness(transport, newArtefactStore(dir), Duration.ofMillis(5), Duration.ofSeconds(5), dir);
        harness.deviceRepo.deviceRecord = Optional.of(new com.securityexpert.nexus.ui2.persistence.device.DeviceRecord(
                DEVICE_ID, "gateway", "check_point", "synthetic", Instant.EPOCH, true,
                com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED, false, "credential-1"));
        var model = com.securityexpert.nexus.ui2.worker.confirm.SparkIdentityParser.model("Unit model: V1");
        assertTrue(model.isEmpty());
        harness.deviceRepo.confirmFacts = Optional.of(new com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts(
                Optional.empty(), model, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), "NONE", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                "NONE", Optional.empty()));
        var platform = org.mockito.Mockito.mock(
                com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository.class);
        org.mockito.Mockito.when(platform.find(DEVICE_ID)).thenReturn(Optional.of(
                new com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts(DEVICE_ID,
                        Optional.empty(), Optional.empty(), Optional.of("gaia_embedded"), java.util.Map.of(),
                        Optional.empty(), "cp_spark_show_diag_software_version", Optional.empty())));
        harness.executor.withPlatformFacts(platform);

        var failed = org.junit.jupiter.api.Assertions.assertInstanceOf(JobOutcome.Failed.class,
                harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request()));
        assertTrue(failed.terminalReason().contains("Spark backup executor is not configured"),
                "the Spark path must be selected even without a model or software version");
        assertTrue(transport.commandsIssued.isEmpty(), "the Gaia precheck must never run");
    }

    @Test
    void anAuthenticationFailureOpensTheCredentialAlertAndOtherConnectFailuresDoNot(@TempDir Path dir) throws Exception {
        var transport = new ScriptedBackupTransport();
        transport.connectFails = true;
        var harness = new Harness(transport, newArtefactStore(dir.resolve("a")), Duration.ofMillis(5), Duration.ofSeconds(5), dir);
        var alerts = new RecordingCredentialAlerts();
        harness.executor.withCredentialAlerts(alerts);
        var failed = org.junit.jupiter.api.Assertions.assertInstanceOf(JobOutcome.Failed.class,
                harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request()));
        assertTrue(failed.terminalReason().contains("authentication_failed"));
        assertEquals(java.util.List.of("cred-backup-1|check_point|" + JOB_ID + "|" + DEVICE_ID), alerts.failures);

        var timeout = new ScriptedBackupTransport();
        timeout.connectOverride = new com.securityexpert.nexus.ui2.jobs.transport.ConnectResult.TimedOut("connect timed out");
        var other = new Harness(timeout, newArtefactStore(dir.resolve("b")), Duration.ofMillis(5), Duration.ofSeconds(5), dir);
        var otherAlerts = new RecordingCredentialAlerts();
        other.executor.withCredentialAlerts(otherAlerts);
        assertTrue(other.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request()) instanceof JobOutcome.Failed);
        assertTrue(otherAlerts.failures.isEmpty(), "a timeout is not a credential failure");
    }

    @Test
    void aCompletedBackupClosesTheOpenCredentialAlert(@TempDir Path tempDir) throws Exception {
        ScriptedBackupTransport transport = happyPathTransportUpTo("succeeded");
        transport.fetchedContent = "archive-bytes-content";
        transport.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME),
                sha256Hex(transport.fetchedContent) + "  " + ARCHIVE_NAME + "\n");
        transport.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");
        Harness harness = new Harness(transport, newArtefactStore(tempDir), Duration.ofMillis(5), Duration.ofSeconds(5), tempDir);
        var alerts = new RecordingCredentialAlerts();
        harness.executor.withCredentialAlerts(alerts);
        assertTrue(harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request()) instanceof JobOutcome.Completed);
        assertEquals(java.util.List.of("cred-backup-1"), alerts.closed);
        assertTrue(alerts.failures.isEmpty());
    }

    @Test void storesTranscriptRefAndPreservesOutcomeOnStoreFailure(@TempDir Path tempDir) throws Exception {
        ScriptedBackupTransport transport = happyPathTransportUpTo("succeeded");
        transport.fetchedContent = "archive-bytes-content";
        transport.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME),
                sha256Hex(transport.fetchedContent) + "  " + ARCHIVE_NAME + "\n");
        transport.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");
        ArtefactStore store = newArtefactStore(tempDir);
        Harness success = new Harness(transport, store, Duration.ofMillis(5), Duration.ofSeconds(5), tempDir);
        AtomicReference<String> ref = new AtomicReference<>();
        AtomicReference<byte[]> key = new AtomicReference<>();
        success.executor.withTranscript(store, transcriptJobs(ref, key));
        assertTrue(success.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request()) instanceof JobOutcome.Completed);
        assertNotNull(ref.get());
        try (InputStream in = store.retrieve(new ArtefactRef(ref.get()), key.get(), true)) {
            String transcript = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(transcript.contains("\"channel\":\"ssh\""));
            assertTrue(transcript.contains("\"kind\":\"command\""));
            assertTrue(transcript.contains("free-space precheck"));
            assertTrue(transcript.contains("poll iteration="));
            assertTrue(transcript.contains("sha256="));
            assertTrue(transcript.contains("COMPLETED"));
        }

        ScriptedBackupTransport next = happyPathTransportUpTo("succeeded");
        next.fetchedContent = "archive-bytes-content";
        next.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME),
                sha256Hex(next.fetchedContent) + "  " + ARCHIVE_NAME + "\n");
        next.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");
        Harness failedStore = new Harness(next, store, Duration.ofMillis(5), Duration.ofSeconds(5), tempDir);
        ArtefactStore broken = new ArtefactStore() {
            @Override public ArtefactHandle open(String deviceId, String jobId, String vendor, boolean gzip) throws IOException {
                throw new IOException("synthetic store failure");
            }
            @Override public InputStream retrieve(ArtefactRef reference, byte[] wrappedKey, boolean gzip) throws IOException {
                throw new IOException("unused");
            }
        };
        failedStore.executor.withTranscript(broken, transcriptJobs(new AtomicReference<>(), new AtomicReference<>()));
        assertTrue(failedStore.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request()) instanceof JobOutcome.Completed);
    }

    @Test void failedGaiaPollStoresTheCommandOutputAndFinalReason(@TempDir Path tempDir) throws Exception {
        var transport = happyPathTransportUpTo("failed: synthetic archive error");
        var store = newArtefactStore(tempDir);
        var harness = new Harness(transport, store, Duration.ofMillis(1), Duration.ofMillis(50), tempDir);
        AtomicReference<String> ref = new AtomicReference<>();
        AtomicReference<byte[]> key = new AtomicReference<>();
        harness.executor.withTranscript(store, transcriptJobs(ref, key));
        assertTrue(harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request()) instanceof JobOutcome.Failed);
        try (var in = store.retrieve(new ArtefactRef(ref.get()), key.get(), true)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("show backup status"));
            assertTrue(text.contains("synthetic archive error"));
            assertTrue(text.contains("status=FAILED"));
            assertTrue(text.contains("FAILED: submit_refused:"));
        }
    }

    @Test void everyBackupCapabilityStoresAFailureTranscriptBeforeTransport(@TempDir Path tempDir) throws Exception {
        for (String capability : java.util.List.of(
                com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.CP_GAIA_BACKUP_LOCAL,
                com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.CP_GAIA_SNAPSHOT,
                com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.CP_MDS_EXPORT,
                com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.CP_SPARK_SFTP_BACKUP,
                com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.ASA_CONFIG_BACKUP,
                com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.FGT_CONFIG_BACKUP,
                com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.HTTPS_VENDOR_BACKUP,
                com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.PAN_DEVICE_STATE_BACKUP,
                com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.RDW_CC_CONFIG_BACKUP)) {
            var transport = new ScriptedBackupTransport();
            var store = newArtefactStore(tempDir);
            var harness = new Harness(transport, store, Duration.ofMillis(1), Duration.ofMillis(5), tempDir);
            AtomicReference<String> ref = new AtomicReference<>();
            AtomicReference<byte[]> key = new AtomicReference<>();
            harness.executor.withTranscript(store, transcriptJobs(ref, key));
            var missingCredential = new BackupRequest(request().connectionTarget(), Optional.empty(), request().trustRuleRef());
            assertTrue(harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, missingCredential, capability) instanceof JobOutcome.Failed);
            assertNotNull(ref.get());
            try (var in = store.retrieve(new ArtefactRef(ref.get()), key.get(), true)) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(text.contains("backup started: " + capability));
                var json = new com.fasterxml.jackson.databind.ObjectMapper();
                for (String line : text.lines().toList()) assertTrue(json.readTree(line).isObject(), capability);
                assertTrue(text.contains("FAILED:"));
                assertTrue(text.contains("\"kind\":\"verdict\""));
            }
            assertTrue(transport.commandsIssued.isEmpty());
            assertEquals(null, com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.current());
        }
    }

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
            this(transport, artefactStore, pollInterval, runDeadline, recoveryVolume, false);
        }

        /** {@code withSnapshotExecutor}: composes the Gaia snapshot executor over the same transport and store, as the worker does. */
        Harness(ScriptedBackupTransport transport, ArtefactStore artefactStore, Duration pollInterval,
                Duration runDeadline, Path recoveryVolume, boolean withSnapshotExecutor) {
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
            this.executor = withSnapshotExecutor
                    ? new BackupJobExecutor(leaseRepo, attemptRepo, enrollmentPort, deviceRepo, capabilityExecutor,
                            new com.securityexpert.nexus.ui2.worker.backup.cp.CheckPointSnapshotExecutor(transport,
                                    artefactStore, 1L, pollInterval, runDeadline),
                            null, null, manifestRepo, eligibilityRepo, testFingerprint(), recoveryVolume.toString())
                    : new BackupJobExecutor(leaseRepo, attemptRepo, enrollmentPort, deviceRepo,
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
        assertEquals("standard", harness.manifestRepo.recorded.get(0).backupType(),
                "an add backup local archive is a standard backup: the age rule prunes it, not the snapshot depth");
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
        assertNotNull(harness.leaseRepo.lastTerminalReason,
                "OUTCOME_UNKNOWN transition must persist the executor's finer reason to terminal_reason");
        assertEquals(((JobOutcome.OutcomeUnknown) outcome).reason(), harness.leaseRepo.lastTerminalReason);
        assertTrue(harness.leaseRepo.lastTerminalReason.contains("stage=poll_backup_status"),
                harness.leaseRepo.lastTerminalReason);
        assertTrue(harness.leaseRepo.lastTerminalReason.contains("transport=completed"),
                harness.leaseRepo.lastTerminalReason);
        assertTrue(harness.leaseRepo.lastTerminalReason.contains("last_status=in progress"),
                harness.leaseRepo.lastTerminalReason);
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

    /**
     * A Gaia snapshot run records backup_type snapshot, so the retention pruner's depth rule (Backups screen: "Gaia
     * snapshots: newest N kept per gateway") applies to it. Before the fix every snapshot was recorded with the column
     * default standard and only the age rule could ever reach it.
     */
    @Test
    void aGaiaSnapshotRunIsRecordedAsASnapshot(@TempDir Path tempDir) throws Exception {
        ScriptedBackupTransport transport = new ScriptedBackupTransport();
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "Free disk space on /var/log: 50000 MB\n");
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_SNAPSHOT_STATUS, "Snapshot completed successfully\n");
        transport.fetchedContent = "snapshot-image-bytes";

        Harness harness = new Harness(transport, newArtefactStore(tempDir), Duration.ofMillis(5), Duration.ofSeconds(5),
                tempDir, true);
        JobOutcome outcome = harness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request(),
                com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds.CP_GAIA_SNAPSHOT);

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        assertFalse(transport.commandsIssued.contains(BackupReadPlan.CP_ADD_BACKUP_LOCAL), "a snapshot run never takes a Gaia backup");
        assertEquals(1, harness.manifestRepo.recorded.size());
        assertEquals("backup", harness.manifestRepo.recorded.get(0).artefactClass());
        assertEquals("snapshot", harness.manifestRepo.recorded.get(0).backupType());
    }

    /** The worker backup writer: a per-store refusal fails the job with the shared reason, leaves no partial file and is audited. */
    @Test
    void perStoreQuotaRefusalFailsTheBackupJobLeavesNoPartialFileAndIsRecordedForTheAudit(@TempDir Path tempDir)
            throws Exception {
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
        String firstArchiveContent = "first-valid-backup-archive-bytes";
        long firstStoredBytes;
        try (ArtefactStore.ArtefactHandle h = new FileArtefactStore(tempDir.resolve("probe"), cipher)
                .open(DEVICE_ID, "probe-job", "check_point", false)) {
            h.sink().write(firstArchiveContent.getBytes(StandardCharsets.UTF_8));
            firstStoredBytes = h.finish().ciphertextBytes();
        }
        Path recoveryDir = tempDir.resolve("recovery");
        java.util.List<com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStoreRefusalRecorder.Refusal> refusals =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        FileArtefactStore store = new FileArtefactStore(recoveryDir, cipher)
                .withStoreQuotas(() -> java.util.Map.of("check_point", firstStoredBytes + 16L))
                .withRefusalRecorder(refusals::add);

        ScriptedBackupTransport firstTransport = happyPathTransportUpTo("succeeded");
        firstTransport.fetchedContent = firstArchiveContent;
        firstTransport.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME),
                sha256Hex(firstArchiveContent) + "  " + ARCHIVE_NAME + "\n");
        firstTransport.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");
        Harness first = new Harness(firstTransport, store, Duration.ofMillis(5), Duration.ofSeconds(5), recoveryDir);
        assertTrue(first.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request()) instanceof JobOutcome.Completed);
        assertTrue(refusals.isEmpty(), "a write within the per-store quota is not a refusal");

        String secondArchiveContent = "second-backup-archive-that-exceeds-remaining-quota-budget";
        ScriptedBackupTransport secondTransport = happyPathTransportUpTo("succeeded");
        secondTransport.fetchedContent = secondArchiveContent;
        secondTransport.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME),
                sha256Hex(secondArchiveContent) + "  " + ARCHIVE_NAME + "\n");
        secondTransport.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");
        Harness second = new Harness(secondTransport, store, Duration.ofMillis(5), Duration.ofSeconds(5), recoveryDir);
        var failed = org.junit.jupiter.api.Assertions.assertInstanceOf(JobOutcome.Failed.class,
                second.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request()));
        assertTrue(failed.terminalReason().contains(FileArtefactStore.QUOTA_EXCEEDED_REASON + ": store=check_point"),
                failed.terminalReason());
        assertEquals(0, second.manifestRepo.recorded.size());
        try (var walk = java.nio.file.Files.walk(recoveryDir)) {
            assertEquals(1, walk.filter(java.nio.file.Files::isRegularFile).count(), "existing backup kept, no partial file");
        }
        assertEquals(1, refusals.size(), "the refusal is recorded once, however many layers saw it");
        assertEquals("check_point", refusals.get(0).store());
        assertEquals("store", refusals.get(0).scope());
        assertEquals(DEVICE_ID, refusals.get(0).deviceId());
    }

    @Test
    void quotaExceededRefusesBackupJobWithNamedReasonLeavesNoPartialFileAndPreservesExistingBackups(@TempDir Path tempDir)
            throws Exception {
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(base64Key);
        String firstArchiveContent = "first-valid-backup-archive-bytes";

        // Measure the encrypted size of the first archive so we can set a tight quota.
        Path probeDir = tempDir.resolve("probe");
        FileArtefactStore probeStore = new FileArtefactStore(probeDir, cipher);
        long firstStoredBytes;
        try (ArtefactStore.ArtefactHandle h = probeStore.open(DEVICE_ID, "probe-job", "check_point", false)) {
            h.sink().write(firstArchiveContent.getBytes(StandardCharsets.UTF_8));
            firstStoredBytes = h.finish().ciphertextBytes();
        }

        // Recovery volume allows the first archive + 16 bytes, so the first backup succeeds and a second backup overflows mid-stream.
        Path recoveryDir = tempDir.resolve("recovery");
        FileArtefactStore quotaStore = FileArtefactStore.withQuota(recoveryDir, cipher, firstStoredBytes + 16L);

        ScriptedBackupTransport firstTransport = happyPathTransportUpTo("succeeded");
        firstTransport.fetchedContent = firstArchiveContent;
        firstTransport.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME),
                sha256Hex(firstArchiveContent) + "  " + ARCHIVE_NAME + "\n");
        firstTransport.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");

        Harness firstHarness = new Harness(firstTransport, quotaStore, Duration.ofMillis(5), Duration.ofSeconds(5), recoveryDir);
        JobOutcome firstOutcome = firstHarness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());
        assertTrue(firstOutcome instanceof JobOutcome.Completed, "first backup within quota must succeed: " + firstOutcome);
        assertEquals(1, firstHarness.manifestRepo.recorded.size());
        var firstManifest = firstHarness.manifestRepo.recorded.get(0);

        // Second backup streams an archive that exceeds the remaining 16-byte quota -> FAILED with named reason, no partial file.
        String secondArchiveContent = "second-backup-archive-that-exceeds-remaining-quota-budget";
        ScriptedBackupTransport secondTransport = happyPathTransportUpTo("succeeded");
        secondTransport.fetchedContent = secondArchiveContent;
        secondTransport.execOutputs.put(BackupReadPlan.archiveDigestCommand(ARCHIVE_NAME),
                sha256Hex(secondArchiveContent) + "  " + ARCHIVE_NAME + "\n");
        secondTransport.execOutputs.put(BackupReadPlan.deleteBackupCommand(ARCHIVE_NAME), "");

        Harness secondHarness = new Harness(secondTransport, quotaStore, Duration.ofMillis(5), Duration.ofSeconds(5), recoveryDir);
        JobOutcome secondOutcome = secondHarness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());
        var secondFailed = org.junit.jupiter.api.Assertions.assertInstanceOf(JobOutcome.Failed.class, secondOutcome);
        assertTrue(secondFailed.terminalReason().startsWith(FileArtefactStore.QUOTA_EXCEEDED_REASON + ":"),
                "expected named quota reason, got: " + secondFailed.terminalReason());
        assertEquals(JobState.FAILED, secondHarness.leaseRepo.currentState);
        assertEquals(0, secondHarness.manifestRepo.recorded.size(), "refused backup must record no manifest row");

        try (var walk = java.nio.file.Files.walk(recoveryDir)) {
            java.util.List<Path> files = walk.filter(java.nio.file.Files::isRegularFile).toList();
            assertEquals(1, files.size(), "existing backup must be kept and zero partial/temp files may remain");
            assertFalse(files.get(0).toString().endsWith(".tmp"));
        }
        try (InputStream in = quotaStore.retrieve(new ArtefactRef(firstManifest.recoveryVolumePath()),
                firstManifest.wrappedDataKey(), false)) {
            assertEquals(firstArchiveContent, new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    "existing backup must remain intact and readable");
        }

        // When the store is already at 100% of quota before the job starts, it refuses before any SSH command is issued.
        FileArtefactStore fullStore = FileArtefactStore.withQuota(recoveryDir, cipher, firstStoredBytes);
        ScriptedBackupTransport thirdTransport = happyPathTransportUpTo("succeeded");
        Harness thirdHarness = new Harness(thirdTransport, fullStore, Duration.ofMillis(5), Duration.ofSeconds(5), recoveryDir);
        JobOutcome thirdOutcome = thirdHarness.executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request());
        var thirdFailed = org.junit.jupiter.api.Assertions.assertInstanceOf(JobOutcome.Failed.class, thirdOutcome);
        assertTrue(thirdFailed.terminalReason().startsWith(FileArtefactStore.QUOTA_EXCEEDED_REASON + ":"),
                "expected named quota reason, got: " + thirdFailed.terminalReason());
        assertTrue(thirdTransport.commandsIssued.isEmpty(), "when already at quota, refuses before contacting the device");
    }
}
