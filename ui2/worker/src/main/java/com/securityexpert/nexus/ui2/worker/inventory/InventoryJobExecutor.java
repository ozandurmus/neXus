package com.securityexpert.nexus.ui2.worker.inventory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.WorkerActor;
import com.securityexpert.nexus.ui2.worker.confirm.PresentedIdentity;

/**
 * The {@code inventory_collect} job's own C2-compliant executor -- copies
 * {@code worker.confirm.ConfirmJobExecutor}'s C2 discipline (lease-fenced
 * state transitions, a {@code job_step_attempt} row written before the
 * one device contact, a zero-rows-affected fenced write stopping the run
 * without a second contact) directly, over inventory's simpler one-phase
 * shape (no peer follow): claim-time device re-check, one pre-contact
 * attempt row, one device contact producing every context in one pass,
 * one {@link DeviceInventoryRepository#recordRun} call.
 *
 * <p>Unlike the confirm, this job is never admissible against {@code
 * DRAFT} (14C D-1) -- the claim-time re-check below requires {@link
 * com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot#permitsReadCollection()},
 * not the confirm's DRAFT-only check.</p>
 *
 * <p>Every step here is {@code CLASS_0_READ} (14C D-1/D-7): no step ever
 * crosses a mutation boundary, so a lease that expires mid-run always
 * finds the one attempt row's {@code mutation_boundary_crossed = false} --
 * {@link com.securityexpert.nexus.ui2.jobs.executor.JobReconciler}'s
 * existing {@code findExpiredAllBoundaryNo} branch requeues such a job to
 * {@code REQUESTED} with no new reconciliation logic, exactly as the
 * confirm's own crash shape does.</p>
 */
public final class InventoryJobExecutor {

    private static final String ACTOR = WorkerActor.RESERVED_ACTOR_FINGERPRINT;
    private static final String ACTION_CLAIM_TO_EXECUTING = "job_inventory_claim_to_executing";
    private static final String ACTION_CLAIM_TIME_DEVICE_CHECK = "job_inventory_claim_time_device_check";
    private static final String ACTION_COMPLETED = "job_inventory_completed";
    private static final String ACTION_FAILED = "job_inventory_failed";

    private final JobLeaseRepository leaseRepository;
    private final JobStepAttemptRepository attemptRepository;
    private final DeviceEnrollmentReadPort deviceEnrollmentReadPort;
    private final DeviceRepository deviceRepository;
    private final DeviceInventoryRepository deviceInventoryRepository;
    private final InventoryCapabilityExecutor inventoryExecutor;

    public InventoryJobExecutor(JobLeaseRepository leaseRepository, JobStepAttemptRepository attemptRepository,
            DeviceEnrollmentReadPort deviceEnrollmentReadPort, DeviceRepository deviceRepository,
            DeviceInventoryRepository deviceInventoryRepository, InventoryCapabilityExecutor inventoryExecutor) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.deviceEnrollmentReadPort = Objects.requireNonNull(deviceEnrollmentReadPort, "deviceEnrollmentReadPort");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.deviceInventoryRepository = Objects.requireNonNull(deviceInventoryRepository, "deviceInventoryRepository");
        this.inventoryExecutor = Objects.requireNonNull(inventoryExecutor, "inventoryExecutor");
    }

    public JobOutcome execute(String jobId, long leaseEpoch, String targetDeviceId, InventoryRequest request,
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

        String attemptId = attemptRepository.insertPreContact(jobId, leaseEpoch, 0, "INVENTORY_COLLECT_READ",
                ActionClass.CLASS_0_READ.id(), 1);
        if (attemptId == null) {
            return new JobOutcome.ZombieStopped();
        }

        Optional<PresentedIdentity> recordedIdentity = deviceRepository.findConfirmFacts(targetDeviceId)
                .flatMap(facts -> facts.recordedIdentityPrimary()
                        .map(primary -> new PresentedIdentity(primary, facts.recordedIdentitySecondary())));

        InventoryResult result = inventoryExecutor.collect(request, recordedIdentity, strictRefuseEnabled);

        String outcomeToken = result instanceof InventoryResult.Completed ? "MATCHED" : "EXPECTATION_UNMET";
        boolean outcomeWritten =
                attemptRepository.writeOutcome(attemptId, leaseEpoch, outcomeToken, null, 0L, 0L, fingerprintOf(result));
        if (!outcomeWritten) {
            return new JobOutcome.ZombieStopped();
        }

        if (!(result instanceof InventoryResult.Completed completed)) {
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                    ACTION_FAILED);
            return new JobOutcome.Failed(describeFailure(result));
        }

        InventoryRun run = new InventoryRun(UUID.randomUUID().toString(), targetDeviceId, jobId, Instant.now(),
                completed.contexts().size(), completed.contexts());
        try {
            deviceInventoryRepository.recordRun(run, ACTOR, ACTION_COMPLETED);
        } catch (RuntimeException recordFailed) {
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                    ACTION_FAILED);
            return new JobOutcome.Failed("inventory_run_write_failed: " + recordFailed.getMessage());
        }

        leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR,
                ACTION_COMPLETED);
        return new JobOutcome.Completed();
    }

    private static String describeFailure(InventoryResult result) {
        return switch (result) {
            case InventoryResult.CredentialUnresolvable unresolvable -> "credential_unresolvable: " + unresolvable.reason();
            case InventoryResult.ConnectFailed connectFailed -> "connect_failed: " + connectFailed.reason();
            case InventoryResult.IdentityMismatchRefused refused -> "identity_mismatch_refused: " + refused.reason();
            case InventoryResult.Completed ignored -> throw new IllegalStateException("unreachable: Completed is not a failure");
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
