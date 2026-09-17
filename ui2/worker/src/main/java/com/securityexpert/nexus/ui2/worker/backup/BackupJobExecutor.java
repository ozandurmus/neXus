package com.securityexpert.nexus.ui2.worker.backup;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactValidation;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupEndpointEligibilityRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.platform.WorkerActor;

/**
 * The {@code cp_gateway_backup} job's own C2-compliant executor -- copies
 * {@code worker.configuration.ConfigurationJobExecutor}'s discipline
 * exactly (lease-fenced state transitions, one pre-contact {@code
 * job_step_attempt} row, claim-time device re-check), but the submit-
 * then-poll-then-fetch-then-verify-then-delete state machine (14H BK-5)
 * has more terminal shapes than configuration's completed/failed split:
 * {@link BackupResult.OutcomeUnknown} maps to the job's own {@code
 * OUTCOME_UNKNOWN} state (never silently folded into {@code FAILED}), and
 * {@link BackupResult.CleanupFailed} still records the manifest (the
 * backup itself succeeded) while failing the job and marking the endpoint
 * ineligible (WORKER.md).
 *
 * <p>Deviation (14I DV-1) is computed here, immediately before recording:
 * the previous {@code backup} artefact's own plaintext digest for this
 * device is read first, compared, and the run's own manifest row carries
 * the result -- {@code unchanged}/{@code changed}/{@code first}, never a
 * structural summary for the opaque Gaia archive (DV-3).</p>
 */
public final class BackupJobExecutor {

    private static final String ACTOR = WorkerActor.RESERVED_ACTOR_FINGERPRINT;
    private static final String ACTION_CLAIM_TO_EXECUTING = "job_backup_claim_to_executing";
    private static final String ACTION_CLAIM_TIME_DEVICE_CHECK = "job_backup_claim_time_device_check";
    private static final String ACTION_COMPLETED = "job_backup_completed";
    private static final String ACTION_FAILED = "job_backup_failed";
    private static final String ACTION_OUTCOME_UNKNOWN = "job_backup_outcome_unknown";
    private static final String ACTION_MANIFEST_RECORDED = "job_backup_manifest_recorded";
    private static final String ACTION_INELIGIBILITY_MARKED = "job_backup_endpoint_marked_ineligible";
    private static final String ACTION_INELIGIBILITY_CLEARED = "job_backup_endpoint_cleared";

    /** section 3.4: the only validation level this movement honestly claims -- it writes and digests, nothing more. */
    private static final String BACKUP_VALIDATION_LEVEL = ArtefactValidation.V1;
    /** section 3.5's GFS defaults are out of this movement's scope -- one placeholder tier until a retention policy build assigns real ones. */
    private static final String BACKUP_RETENTION_TIER = "standard";
    private static final String VENDOR = "check_point";

    private final JobLeaseRepository leaseRepository;
    private final JobStepAttemptRepository attemptRepository;
    private final DeviceEnrollmentReadPort deviceEnrollmentReadPort;
    private final DeviceRepository deviceRepository;
    private final BackupCapabilityExecutor backupExecutor;
    private final BackupArtefactManifestRepository manifestRepository;
    private final BackupEndpointEligibilityRepository eligibilityRepository;
    private final HostnameFingerprint hostnameFingerprint;
    private final String recoveryVolumePath;

    public BackupJobExecutor(JobLeaseRepository leaseRepository, JobStepAttemptRepository attemptRepository,
            DeviceEnrollmentReadPort deviceEnrollmentReadPort, DeviceRepository deviceRepository,
            BackupCapabilityExecutor backupExecutor, BackupArtefactManifestRepository manifestRepository,
            BackupEndpointEligibilityRepository eligibilityRepository, HostnameFingerprint hostnameFingerprint,
            String recoveryVolumePath) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.deviceEnrollmentReadPort = Objects.requireNonNull(deviceEnrollmentReadPort, "deviceEnrollmentReadPort");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.backupExecutor = Objects.requireNonNull(backupExecutor, "backupExecutor");
        this.manifestRepository = Objects.requireNonNull(manifestRepository, "manifestRepository");
        this.eligibilityRepository = Objects.requireNonNull(eligibilityRepository, "eligibilityRepository");
        this.hostnameFingerprint = Objects.requireNonNull(hostnameFingerprint, "hostnameFingerprint");
        this.recoveryVolumePath = Objects.requireNonNull(recoveryVolumePath, "recoveryVolumePath");
    }

    public JobOutcome execute(String jobId, long leaseEpoch, String targetDeviceId, BackupRequest request) {
        Optional<com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot> enrollment =
                deviceEnrollmentReadPort.findEnrollment(targetDeviceId);
        if (enrollment.isEmpty() || !enrollment.get().permitsReadCollection()) {
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.REJECTED, ACTOR,
                    ACTION_CLAIM_TIME_DEVICE_CHECK);
            return new JobOutcome.Rejected("DEVICE_NOT_ELIGIBLE_AT_CLAIM_TIME");
        }

        if (!leaseRepository.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR,
                ACTION_CLAIM_TO_EXECUTING)) {
            return new JobOutcome.ZombieStopped();
        }

        String attemptId = attemptRepository.insertPreContact(jobId, leaseEpoch, 0, "BACKUP_COLLECT",
                ActionClass.CLASS_1_RECOVERY_WRITE.id(), 1);
        if (attemptId == null) {
            return new JobOutcome.ZombieStopped();
        }

        Optional<DeviceConfirmFacts> confirmFacts = deviceRepository.findConfirmFacts(targetDeviceId);

        BackupResult result = backupExecutor.collect(request, targetDeviceId, jobId);

        String outcomeToken = result instanceof BackupResult.Completed ? "MATCHED" : "EXPECTATION_UNMET";
        boolean outcomeWritten =
                attemptRepository.writeOutcome(attemptId, leaseEpoch, outcomeToken, null, 0L, 0L, fingerprintOf(result));
        if (!outcomeWritten) {
            return new JobOutcome.ZombieStopped();
        }

        if (result instanceof BackupResult.OutcomeUnknown outcomeUnknown) {
            // BK-5: a poll that never reaches a terminal state ends OUTCOME_UNKNOWN, never FAILED, and deletes nothing.
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.OUTCOME_UNKNOWN, ACTOR,
                    ACTION_OUTCOME_UNKNOWN);
            return new JobOutcome.OutcomeUnknown(outcomeUnknown.reason());
        }

        if (result instanceof BackupResult.Completed completed) {
            return handleCompleted(jobId, leaseEpoch, targetDeviceId, completed, confirmFacts);
        }

        if (result instanceof BackupResult.CleanupFailed cleanupFailed) {
            return handleCleanupFailed(jobId, leaseEpoch, targetDeviceId, cleanupFailed, confirmFacts);
        }

        leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR, ACTION_FAILED, describeFailure(result));
        return new JobOutcome.Failed(describeFailure(result));
    }

    private JobOutcome handleCompleted(String jobId, long leaseEpoch, String deviceId, BackupResult.Completed completed,
            Optional<DeviceConfirmFacts> confirmFacts) {
        String deviationState = deviationStateAgainstPrevious(deviceId, completed.artefact().plaintextSha256());
        boolean recorded = recordManifest(completed.artefact(), deviceId, confirmFacts, deviationState);
        if (!recorded) {
            // AC-3 / C7 section 3.3: an unresolvable Check Point software version refuses the store with zero rows.
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                    ACTION_FAILED, "backup_artefact_version_unresolvable: an unresolvable Check Point "
                    + "software version refuses the store with zero rows (C7 section 3.3)");
            return new JobOutcome.Failed("backup_artefact_version_unresolvable: an unresolvable Check Point "
                    + "software version refuses the store with zero rows (C7 section 3.3)");
        }

        eligibilityRepository.clear(deviceId, ACTOR, ACTION_INELIGIBILITY_CLEARED);

        leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR,
                ACTION_COMPLETED);
        return new JobOutcome.Completed();
    }

    private JobOutcome handleCleanupFailed(String jobId, long leaseEpoch, String deviceId,
            BackupResult.CleanupFailed cleanupFailed, Optional<DeviceConfirmFacts> confirmFacts) {
        // The archive was fetched, verified and stored before the delete
        // failed -- the backup itself succeeded, so its manifest row is
        // still recorded (best-effort: an unresolvable software version
        // still refuses the store here exactly as it would on the happy
        // path, and the run still fails for cleanup_failed either way).
        String deviationState = deviationStateAgainstPrevious(deviceId, cleanupFailed.artefact().plaintextSha256());
        recordManifest(cleanupFailed.artefact(), deviceId, confirmFacts, deviationState);

        eligibilityRepository.markIneligible(deviceId, cleanupFailed.reason(), ACTOR, ACTION_INELIGIBILITY_MARKED);

        leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR, ACTION_FAILED, "cleanup_failed: " + cleanupFailed.reason());
        return new JobOutcome.Failed("cleanup_failed: " + cleanupFailed.reason());
    }

    /** 14I DV-1: compares against the previous {@code backup}-class artefact for this device, by plaintext digest only. */
    private String deviationStateAgainstPrevious(String deviceId, String newPlaintextSha256) {
        Optional<BackupArtefactManifestRepository.PlaintextDigestSummary> previous =
                manifestRepository.findLatestPlaintextDigest(deviceId, ArtefactClass.BACKUP);
        if (previous.isEmpty()) {
            return BackupArtefactManifestRecord.DEVIATION_FIRST;
        }
        return previous.get().plaintextSha256().equals(newPlaintextSha256) ? BackupArtefactManifestRecord.DEVIATION_UNCHANGED
                : BackupArtefactManifestRecord.DEVIATION_CHANGED;
    }

    private boolean recordManifest(ArtefactStore.ArtefactMetadata artefact, String deviceId,
            Optional<DeviceConfirmFacts> confirmFacts, String deviationState) {
        Optional<String> virtualSystemRef = confirmFacts.flatMap(DeviceConfirmFacts::virtualSystemRef);
        Optional<String> softwareVersion = confirmFacts.flatMap(DeviceConfirmFacts::observedSoftwareVersion);
        String hostnameSource = confirmFacts.flatMap(DeviceConfirmFacts::observedHostname).orElse(deviceId);
        try {
            BackupArtefactManifestRecord manifest = new BackupArtefactManifestRecord(artefact.ref().value(), deviceId,
                    virtualSystemRef, ArtefactClass.BACKUP, VENDOR, softwareVersion, hostnameFingerprint.of(hostnameSource),
                    artefact.plaintextSha256(), artefact.plaintextBytes(), artefact.ciphertextSha256(),
                    artefact.ciphertextBytes(), artefact.keyId(), artefact.wrappedDataKey(),
                    ArtefactValidation.reachedWithoutRestore(BACKUP_VALIDATION_LEVEL), BACKUP_RETENTION_TIER,
                    Optional.empty(), recoveryVolumePath, Optional.of(deviationState));
            manifestRepository.record(manifest, ACTOR, ACTION_MANIFEST_RECORDED);
            return true;
        } catch (IllegalStateException versionUnresolvable) {
            return false;
        }
    }

    private static String describeFailure(BackupResult result) {
        return switch (result) {
            case BackupResult.CredentialUnresolvable r -> "credential_unresolvable: " + r.reason();
            case BackupResult.ConnectFailed r -> "connect_failed: " + r.reason();
            case BackupResult.InsufficientFreeSpace r -> "insufficient_free_space: " + r.reason();
            case BackupResult.SubmitOutputUnparseable r -> "submit_output_unparseable: " + r.reason();
            case BackupResult.SubmitRefused r -> "submit_refused: " + r.reason();
            case BackupResult.ArtefactStoreFailed r -> "artefact_store_failed: " + r.reason();
            case BackupResult.DigestMismatch r ->
                    "digest_mismatch: device_digest=" + r.deviceDigest() + " received_digest=" + r.receivedDigest();
            case BackupResult.Completed ignored ->
                    throw new IllegalStateException("unreachable: Completed is handled by handleCompleted");
            case BackupResult.CleanupFailed ignored ->
                    throw new IllegalStateException("unreachable: CleanupFailed is handled by handleCleanupFailed");
            case BackupResult.OutcomeUnknown ignored ->
                    throw new IllegalStateException("unreachable: OutcomeUnknown is handled before this switch");
        };
    }

    private static String fingerprintOf(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
