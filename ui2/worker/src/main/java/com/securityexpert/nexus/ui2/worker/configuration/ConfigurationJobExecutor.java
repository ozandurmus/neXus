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
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ChangeState;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationNotificationRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationOverride;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRun;
import com.securityexpert.nexus.ui2.persistence.device.configuration.DeviceConfigurationRepository;
import com.securityexpert.nexus.ui2.platform.ActionClass;
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

    private final JobLeaseRepository leaseRepository;
    private final JobStepAttemptRepository attemptRepository;
    private final DeviceEnrollmentReadPort deviceEnrollmentReadPort;
    private final DeviceRepository deviceRepository;
    private final DeviceConfigurationRepository deviceConfigurationRepository;
    private final ConfigurationNotificationRepository notificationRepository;
    private final ConfigurationCapabilityExecutor configurationExecutor;

    public ConfigurationJobExecutor(JobLeaseRepository leaseRepository, JobStepAttemptRepository attemptRepository,
            DeviceEnrollmentReadPort deviceEnrollmentReadPort, DeviceRepository deviceRepository,
            DeviceConfigurationRepository deviceConfigurationRepository,
            ConfigurationNotificationRepository notificationRepository,
            ConfigurationCapabilityExecutor configurationExecutor) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.deviceEnrollmentReadPort = Objects.requireNonNull(deviceEnrollmentReadPort, "deviceEnrollmentReadPort");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.deviceConfigurationRepository =
                Objects.requireNonNull(deviceConfigurationRepository, "deviceConfigurationRepository");
        this.notificationRepository = Objects.requireNonNull(notificationRepository, "notificationRepository");
        this.configurationExecutor = Objects.requireNonNull(configurationExecutor, "configurationExecutor");
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

        Optional<PresentedIdentity> recordedIdentity = deviceRepository.findConfirmFacts(targetDeviceId)
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
                    ACTION_FAILED);
            return new JobOutcome.Failed(describeFailure(result));
        }

        String vendor = request.vendor() == ConfigurationVendor.CHECK_POINT ? "check_point" : "palo_alto";
        Instant now = Instant.now();
        List<String> overridePaths = new ArrayList<>();
        String primaryRunId = null;

        for (ConfigurationRunData data : completed.runs()) {
            Optional<ConfigurationRun> previous =
                    deviceConfigurationRepository.findLatestRun(targetDeviceId, data.readKind());
            String changeState = previous.isEmpty() ? ChangeState.FIRST_RUN
                    : previous.get().canonicalHash().equals(data.canonicalHash()) ? ChangeState.UNCHANGED
                            : ChangeState.CHANGED;

            String runId = UUID.randomUUID().toString();
            if (data.primary()) {
                primaryRunId = runId;
            }
            ConfigurationRun run = new ConfigurationRun(runId, targetDeviceId, jobId, now, vendor, data.readKind(),
                    data.primary(), data.canonicalHash(), data.rawHash(), data.rawBytes(), data.artefact().artefactRef(),
                    data.withheldLineCount(), data.sanitizedText(), changeState, data.index(), data.overrides());
            try {
                deviceConfigurationRepository.recordRun(run, data.artefact(), ACTOR, ACTION_COMPLETED);
            } catch (RuntimeException recordFailed) {
                leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                        ACTION_FAILED);
                return new JobOutcome.Failed("configuration_run_write_failed: " + recordFailed.getMessage());
            }
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
