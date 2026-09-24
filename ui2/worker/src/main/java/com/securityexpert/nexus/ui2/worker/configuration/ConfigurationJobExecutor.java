package com.securityexpert.nexus.ui2.worker.configuration;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactValidation;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ChangeState;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationArtefactRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationSummary;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationNotificationRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationOverride;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRun;
import com.securityexpert.nexus.ui2.persistence.device.configuration.DeviceConfigurationRepository;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.platform.WorkerActor;
import com.securityexpert.nexus.ui2.worker.confirm.PresentedIdentity;

/**
 * The {@code configuration_collect} job's own C2-compliant executor --
 * copies {@code worker.inventory.InventoryJobExecutor}'s discipline
 * exactly (lease-fenced state transitions, one pre-contact {@code
 * job_step_attempt} row, claim-time device re-check), but persists a list
 * of runs (one per read kind, 14G CG-8: "one run per device per job" reads
 * as "one job produces every read kind's own row") instead of one.
 *
 * <p>Change detection (CG-10) and the override notification (CG-7d) both
 * happen here, once every run's outcome is known -- the capability
 * executor only collects and stores; this class is the only place that
 * compares against history or writes a notification.</p>
 */
public final class ConfigurationJobExecutor {

    private static final String ACTOR = WorkerActor.RESERVED_ACTOR_FINGERPRINT;
    private static final String ACTION_CLAIM_TO_EXECUTING = "job_configuration_claim_to_executing";
    private static final String ACTION_CLAIM_TIME_DEVICE_CHECK = "job_configuration_claim_time_device_check";
    private static final String ACTION_COMPLETED = "job_configuration_completed";
    private static final String ACTION_FAILED = "job_configuration_failed";
    private static final String ACTION_MANIFEST_RECORDED = "job_configuration_manifest_recorded";

    /** section 3.4: the only validation level the configuration path honestly claims -- it writes and digests, nothing more. */
    private static final String CONFIGURATION_VALIDATION_LEVEL = ArtefactValidation.V1;
    /** section 3.5's GFS defaults are out of this movement's scope (WORKER.md: "nothing enforces deletion") -- one placeholder tier until a retention policy build assigns real ones. */
    private static final String CONFIGURATION_RETENTION_TIER = "standard";

    private final JobLeaseRepository leaseRepository;
    private final JobStepAttemptRepository attemptRepository;
    private final DeviceEnrollmentReadPort deviceEnrollmentReadPort;
    private final DeviceRepository deviceRepository;
    private final DeviceConfigurationRepository deviceConfigurationRepository;
    private final ConfigurationNotificationRepository notificationRepository;
    private final ConfigurationCapabilityExecutor configurationExecutor;
    private final BackupArtefactManifestRepository backupArtefactManifestRepository;
    private final HostnameFingerprint hostnameFingerprint;
    private final String recoveryVolumePath;

    public ConfigurationJobExecutor(JobLeaseRepository leaseRepository, JobStepAttemptRepository attemptRepository,
            DeviceEnrollmentReadPort deviceEnrollmentReadPort, DeviceRepository deviceRepository,
            DeviceConfigurationRepository deviceConfigurationRepository,
            ConfigurationNotificationRepository notificationRepository,
            ConfigurationCapabilityExecutor configurationExecutor,
            BackupArtefactManifestRepository backupArtefactManifestRepository, HostnameFingerprint hostnameFingerprint,
            String recoveryVolumePath) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.deviceEnrollmentReadPort = Objects.requireNonNull(deviceEnrollmentReadPort, "deviceEnrollmentReadPort");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.deviceConfigurationRepository =
                Objects.requireNonNull(deviceConfigurationRepository, "deviceConfigurationRepository");
        this.notificationRepository = Objects.requireNonNull(notificationRepository, "notificationRepository");
        this.configurationExecutor = Objects.requireNonNull(configurationExecutor, "configurationExecutor");
        this.backupArtefactManifestRepository =
                Objects.requireNonNull(backupArtefactManifestRepository, "backupArtefactManifestRepository");
        this.hostnameFingerprint = Objects.requireNonNull(hostnameFingerprint, "hostnameFingerprint");
        this.recoveryVolumePath = Objects.requireNonNull(recoveryVolumePath, "recoveryVolumePath");
    }

    public JobOutcome execute(String jobId, long leaseEpoch, String targetDeviceId, ConfigurationRequest request,
            boolean strictRefuseEnabled) {

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

        String attemptId = attemptRepository.insertPreContact(jobId, leaseEpoch, 0, "CONFIGURATION_COLLECT_READ",
                ActionClass.CLASS_0_READ.id(), 1);
        if (attemptId == null) {
            return new JobOutcome.ZombieStopped();
        }

        Optional<DeviceConfirmFacts> confirmFacts = deviceRepository.findConfirmFacts(targetDeviceId);
        Optional<PresentedIdentity> recordedIdentity = confirmFacts
                .flatMap(facts -> facts.recordedIdentityPrimary()
                        .map(primary -> new PresentedIdentity(primary, facts.recordedIdentitySecondary())));

        ConfigurationResult result =
                configurationExecutor.collect(request, targetDeviceId, jobId, recordedIdentity, strictRefuseEnabled);

        String outcomeToken = result instanceof ConfigurationResult.Completed ? "MATCHED" : "EXPECTATION_UNMET";
        boolean outcomeWritten =
                attemptRepository.writeOutcome(attemptId, leaseEpoch, outcomeToken, null, 0L, 0L, fingerprintOf(result));
        if (!outcomeWritten) {
            return new JobOutcome.ZombieStopped();
        }

        if (!(result instanceof ConfigurationResult.Completed completed)) {
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                    ACTION_FAILED, describeFailure(result));
            return new JobOutcome.Failed(describeFailure(result));
        }

        String vendor = request.vendor() == ConfigurationVendor.CHECK_POINT ? "check_point" : "palo_alto";
        // Fill a device's empty hostname/version from this run's identity refresh; never overwrites a recorded value.
        if (completed.identity().hostname().isPresent() || completed.identity().softwareVersion().isPresent()) {
            try {
                deviceRepository.fillObservedIdentityIfAbsent(targetDeviceId, completed.identity().hostname(),
                        completed.identity().softwareVersion(), ACTOR, "configuration_identity_fill");
            } catch (RuntimeException e) {
                System.getLogger(ConfigurationJobExecutor.class.getName()).log(System.Logger.Level.WARNING, "[CONFIG_IDENTITY_FILL_FAILED] job {0}: {1}", jobId, e.getMessage());
            }
        }
        Instant now = Instant.now();
        List<String> overridePaths = new ArrayList<>();
        String primaryRunId = null;

        for (ConfigurationRunData data : completed.runs()) {
            Optional<ConfigurationRun> previous =
                    deviceConfigurationRepository.findLatestRun(targetDeviceId, data.readKind());
            String changeState = previous.isEmpty() ? ChangeState.FIRST_RUN
                    : previous.get().canonicalHash().equals(data.canonicalHash()) ? ChangeState.UNCHANGED
                            : ChangeState.CHANGED;

            // 14I DV-2: compute the structural deviation summary for changed runs only (AC-4).
            Optional<ConfigurationDeviationSummary> deviationSummary = Optional.empty();
            if (ChangeState.CHANGED.equals(changeState)) {
                // previous is present (CHANGED requires a predecessor). Its index list is
                // never null (ConfigurationRun's compact constructor guarantees that). An
                // empty list is a legitimate empty predecessor; null would mean absent, but
                // ConfigurationRun cannot hold null (AC-5 -- IndexDeviationComputer treats
                // null as not-computable; a non-null empty list is computable).
                deviationSummary = Optional.of(
                        IndexDeviationComputer.compute(previous.get().index(), data.index()));
            }

            String runId = UUID.randomUUID().toString();
            if (data.primary()) {
                primaryRunId = runId;
            }
            ConfigurationRun run = new ConfigurationRun(runId, targetDeviceId, jobId, now, vendor, data.readKind(),
                    data.primary(), data.canonicalHash(), data.rawHash(), data.rawBytes(), data.artefact().artefactRef(),
                    data.withheldLineCount(), data.sanitizedText(), changeState, data.index(), data.overrides(),
                    deviationSummary);
            try {
                deviceConfigurationRepository.recordRun(run, data.artefact(), ACTOR, ACTION_COMPLETED);
            } catch (RuntimeException recordFailed) {
                leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                        ACTION_FAILED, "configuration_run_write_failed: " + recordFailed.getMessage());
                return new JobOutcome.Failed("configuration_run_write_failed: " + recordFailed.getMessage());
            }
            recordBackupArtefactManifest(data.artefact(), targetDeviceId, vendor, confirmFacts);
            for (ConfigurationOverride override : data.overrides()) {
                overridePaths.add(override.category() + "/" + override.elementPath());
            }
        }

        if (!overridePaths.isEmpty() && primaryRunId != null) {
            String summary = overridePaths.size() + " element(s) carry a local override not defined by the device's "
                    + "Panorama assignment";
            notificationRepository.record(targetDeviceId, primaryRunId, summary, overridePaths, ACTOR, ACTION_COMPLETED);
        }

        leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR,
                ACTION_COMPLETED);
        return new JobOutcome.Completed();
    }

    /**
     * BK-16: writes a {@code backup_artefact} manifest row (artefact class
     * {@code configuration}) for the artefact {@code
     * deviceConfigurationRepository.recordRun} above already committed as
     * this path's own authority. Section 3.3's version-locking refusal
     * fires inside {@link BackupArtefactManifestRecord}'s constructor for
     * a check_point artefact whose software version this device's confirm
     * facts never resolved -- caught here rather than propagated, since a
     * missing manifest row is a known gap this movement documents (named
     * in the SESSION_CLOSE), not a reason to fail a configuration run that
     * already wrote its own {@code configuration_artefact}/{@code
     * device_configuration_run} rows successfully.
     */
    private void recordBackupArtefactManifest(ConfigurationArtefactRecord artefact, String deviceId, String vendor,
            Optional<DeviceConfirmFacts> confirmFacts) {
        Optional<String> virtualSystemRef = confirmFacts.flatMap(DeviceConfirmFacts::virtualSystemRef);
        Optional<String> softwareVersion = confirmFacts.flatMap(DeviceConfirmFacts::observedSoftwareVersion);
        String hostnameSource = confirmFacts.flatMap(DeviceConfirmFacts::observedHostname).orElse(deviceId);
        try {
            BackupArtefactManifestRecord manifest = new BackupArtefactManifestRecord(UUID.randomUUID().toString(), deviceId,
                    virtualSystemRef, ArtefactClass.CONFIGURATION, vendor, softwareVersion,
                    hostnameFingerprint.of(hostnameSource), artefact.plaintextSha256(), artefact.plaintextBytes(),
                    artefact.ciphertextSha256(), artefact.ciphertextBytes(), artefact.keyId(),
                    artefact.wrappedDataKey(), ArtefactValidation.reachedWithoutRestore(CONFIGURATION_VALIDATION_LEVEL),
                    CONFIGURATION_RETENTION_TIER, Optional.empty(), artefact.artefactRef(), Optional.empty());
            backupArtefactManifestRepository.record(manifest, ACTOR, ACTION_MANIFEST_RECORDED);
        } catch (IllegalStateException versionUnresolvable) {
            // C7 section 3.3's refusal -- see method Javadoc.
        }
    }

    private static String describeFailure(ConfigurationResult result) {
        return switch (result) {
            case ConfigurationResult.CredentialUnresolvable unresolvable -> "credential_unresolvable: " + unresolvable.reason();
            case ConfigurationResult.ConnectFailed connectFailed -> "connect_failed: " + connectFailed.reason();
            case ConfigurationResult.IdentityMismatchRefused refused -> "identity_mismatch_refused: " + refused.reason();
            case ConfigurationResult.ArtefactStoreFailed failed -> "artefact_store_failed: " + failed.reason();
            case ConfigurationResult.Completed ignored -> throw new IllegalStateException("unreachable: Completed is not a failure");
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
