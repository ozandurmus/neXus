package com.securityexpert.nexus.ui2.worker.confirm;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.WorkerActor;

/**
 * The enrollment confirm job's own C2-compliant executor (PO_DECISION_RECORD_2026_09_14B
 * EC-J3/EC-J4: "the confirm job obeys every C2 rule in full... it is only exempt from the
 * DRAFT refusal"). Deliberately a sibling of {@link com.securityexpert.nexus.ui2.jobs.executor.StepExecutor},
 * not a caller of it: {@code StepExecutor#execute} claims a job exactly once per call (its
 * own {@code CLAIMED -> EXECUTING -> <terminal>} transitions happen once, against one {@code
 * ConnectionTarget}) and has no mechanism to run a second, conditional device contact against
 * a different target discovered mid-run -- exactly what EC-J5's peer follow (PF-1..PF-5) needs
 * ("runs inside the same confirm job... never a second admitted job"). This class implements
 * the same C2 contract primitives {@code StepExecutor} does (lease-fenced state transitions via
 * {@link JobLeaseRepository#transitionState}, a {@code job_step_attempt} row written before each
 * device contact via {@link JobStepAttemptRepository}, a zero-rows-affected fenced write stopping
 * the run without a second device contact) directly, over the confirm's own two-phase (self, then
 * conditionally peer) shape, using the already-tested {@link ConfirmCapabilityExecutor} and {@link
 * PeerFollowResolver} for the device semantics 0156 already built.
 *
 * <p>Every step here is {@code CLASS_0_READ} (contract EC-11/EC-12): no step ever calls a
 * mutation-boundary write, so a lease that expires mid-run always finds every attempt row's
 * {@code mutation_boundary_crossed = false} -- {@link com.securityexpert.nexus.ui2.jobs.executor.JobReconciler}'s
 * existing {@code findExpiredAllBoundaryNo}/{@code findExpiredWithNoAttempt} branches already
 * requeue such a job to {@code REQUESTED} with no new reconciliation logic (C2 §4.4; AC-2).</p>
 */
public final class ConfirmJobExecutor {

    private static final System.Logger LOG = System.getLogger(ConfirmJobExecutor.class.getName());
    private static final String ACTOR = "system:worker";
    private static final String ACTION_CLAIM_TIME_DEVICE_CHECK = "confirm_claim_time_device_check";
    private static final String ACTION_CLAIM_TO_EXECUTING = "confirm_claim_to_executing";
    private static final String ACTION_FAILED = "confirm_failed";
    private static final String ACTION_COMPLETED = "confirm_completed";

    private final JobLeaseRepository leaseRepository;
    private final JobStepAttemptRepository attemptRepository;
    private final DeviceEnrollmentReadPort deviceEnrollmentReadPort;
    private final DeviceRepository deviceRepository;
    private final ConfirmCapabilityExecutor confirmExecutor;
    private final PeerFollowResolver peerFollowResolver;

    public ConfirmJobExecutor(JobLeaseRepository leaseRepository, JobStepAttemptRepository attemptRepository,
            DeviceEnrollmentReadPort deviceEnrollmentReadPort, DeviceRepository deviceRepository,
            ConfirmCapabilityExecutor confirmExecutor, PeerFollowResolver peerFollowResolver) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.deviceEnrollmentReadPort = Objects.requireNonNull(deviceEnrollmentReadPort, "deviceEnrollmentReadPort");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.confirmExecutor = Objects.requireNonNull(confirmExecutor, "confirmExecutor");
        this.peerFollowResolver = Objects.requireNonNull(peerFollowResolver, "peerFollowResolver");
    }

    /**
     * @param peerRequestFactory builds the one PF-1 request against a named peer's own
     *     management address; never invoked unless the primary device's own HA/peer read
     *     names one (see {@link PeerFollowResolver#resolve}).
     */
    public JobOutcome execute(String jobId, long leaseEpoch, String targetDeviceId, ConfirmRequest request,
            PeerFollowResolver.ConfirmRequestFactory peerRequestFactory, boolean strictRefuseEnabled) {

        long jobStart = System.currentTimeMillis();
        LOG.log(System.Logger.Level.INFO,
                "[JOB_START] Confirm job {0} for device {1} leaseEpoch={2}",
                jobId, targetDeviceId, leaseEpoch);

        // EC-J1's claim-time mirror of F6's second enforcement point: the confirm is admissible
        // only while the device is still DRAFT and not disabled -- re-checked here independent of
        // JobAdmissionService's own admission-time check (a device disabled between admission and
        // claim is refused here, before any contact).
        Optional<com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot> enrollment =
                deviceEnrollmentReadPort.findEnrollment(targetDeviceId);
        if (enrollment.isEmpty() || enrollment.get().disabled()
                || enrollment.get().enrollmentState() != DeviceEnrollmentState.DRAFT) {
            long elapsed = System.currentTimeMillis() - jobStart;
            LOG.log(System.Logger.Level.WARNING,
                    "[JOB_REJECTED] Confirm job {0} device {1} not eligible after {2}ms",
                    jobId, targetDeviceId, elapsed);
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.REJECTED, ACTOR,
                    ACTION_CLAIM_TIME_DEVICE_CHECK, "DEVICE_NOT_ELIGIBLE_AT_CLAIM_TIME (after " + elapsed + "ms)");
            return new JobOutcome.Rejected("DEVICE_NOT_ELIGIBLE_AT_CLAIM_TIME");
        }

        if (!leaseRepository.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR,
                ACTION_CLAIM_TO_EXECUTING)) {
            return new JobOutcome.ZombieStopped();
        }

        AttemptWrite primaryAttempt = writePreContactAttempt(jobId, leaseEpoch, 0, "CONFIRM_PRIMARY_READ");
        if (primaryAttempt.zombie()) {
            return new JobOutcome.ZombieStopped();
        }

        ConfirmResult primaryResult = confirmExecutor.confirm(request);
        if (!recordAttemptOutcome(primaryAttempt.attemptId(), leaseEpoch, primaryResult)) {
            return new JobOutcome.ZombieStopped();
        }

        if (!(primaryResult instanceof ConfirmResult.Completed completed)) {
            long elapsed = System.currentTimeMillis() - jobStart;
            String reasonWithTime = describeFailure(primaryResult) + " (after " + elapsed + "ms)";
            LOG.log(System.Logger.Level.WARNING,
                    "[JOB_FAILED] Confirm job {0} for device {1} FAILED after {2}ms: {3}",
                    jobId, targetDeviceId, elapsed, reasonWithTime);
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                    ACTION_FAILED, reasonWithTime);
            return new JobOutcome.Failed(describeFailure(primaryResult));
        }

        // EC-5: this is always the device's first confirm (the job is only ever admissible
        // against DRAFT, EC-J1) -- there is never a recorded baseline to compare against, so
        // this always resolves NO_BASELINE and this confirm establishes the baseline itself
        // (EC-J3). Called anyway, not inlined, so the decision point stays visible and testable.
        IdentityMismatchEvaluator.Decision identityDecision =
                IdentityMismatchEvaluator.evaluate(Optional.empty(), completed.presentedIdentity(), strictRefuseEnabled);

        PeerFollowOutcome peerFollowOutcome;
        if (completed.haPeerClaim().isMember()) {
            AttemptWrite peerAttempt = writePreContactAttempt(jobId, leaseEpoch, 1, "CONFIRM_PEER_FOLLOW_READ");
            if (peerAttempt.zombie()) {
                return new JobOutcome.ZombieStopped();
            }
            peerFollowOutcome = peerFollowResolver.resolve(completed, peerRequestFactory);
            if (!recordAttemptOutcome(peerAttempt.attemptId(), leaseEpoch, peerFollowOutcome)) {
                return new JobOutcome.ZombieStopped();
            }
        } else {
            peerFollowOutcome = PeerFollowOutcome.none();
        }

        DeviceConfirmFacts facts = toDeviceConfirmFacts(completed, identityDecision, peerFollowOutcome);
        boolean written = deviceRepository.recordConfirmSuccess(targetDeviceId, facts, ACTOR, ACTION_COMPLETED);
        if (!written) {
            long elapsed = System.currentTimeMillis() - jobStart;
            String reasonWithTime = "device_confirm_write_conflict (after " + elapsed + "ms)";
            LOG.log(System.Logger.Level.ERROR,
                    "[JOB_FAILED] Confirm job {0} for device {1} write conflict after {2}ms",
                    jobId, targetDeviceId, elapsed);
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                    ACTION_FAILED, reasonWithTime);
            return new JobOutcome.Failed("device_confirm_write_conflict");
        }

        long elapsed = System.currentTimeMillis() - jobStart;
        LOG.log(System.Logger.Level.INFO,
                "[JOB_COMPLETED] Confirm job {0} for device {1} COMPLETED in {2}ms",
                jobId, targetDeviceId, elapsed);
        leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR,
                ACTION_COMPLETED, "completed in " + elapsed + "ms");
        return new JobOutcome.Completed();
    }

    private record AttemptWrite(String attemptId, boolean zombie) {
    }

    private AttemptWrite writePreContactAttempt(String jobId, long leaseEpoch, int stepIndex, String stepKind) {
        String attemptId = attemptRepository.insertPreContact(jobId, leaseEpoch, stepIndex, stepKind,
                com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ.id(), 1);
        return new AttemptWrite(attemptId, attemptId == null);
    }

    private boolean recordAttemptOutcome(String attemptId, long leaseEpoch, Object result) {
        String outcome = describeOutcomeToken(result);
        return attemptRepository.writeOutcome(attemptId, leaseEpoch, outcome, null, 0L, 0L, fingerprintOf(result));
    }

    private static String describeOutcomeToken(Object result) {
        if (result instanceof ConfirmResult.Completed) {
            return "MATCHED";
        }
        if (result instanceof PeerFollowOutcome.Corroborated) {
            return "MATCHED";
        }
        return "EXPECTATION_UNMET";
    }

    private static String describeFailure(ConfirmResult result) {
        return switch (result) {
            case ConfirmResult.CredentialUnresolvable unresolvable -> "credential_unresolvable: " + unresolvable.reason();
            case ConfirmResult.ConnectFailed connectFailed -> "connect_failed: " + connectFailed.reason();
            case ConfirmResult.Completed ignored -> throw new IllegalStateException("unreachable: Completed is not a failure");
        };
    }

    private static DeviceConfirmFacts toDeviceConfirmFacts(ConfirmResult.Completed completed,
            IdentityMismatchEvaluator.Decision identityDecision, PeerFollowOutcome peerFollowOutcome) {
        ObservedFacts facts = completed.facts();
        PresentedIdentity presented = completed.presentedIdentity();

        String peerOutcomeToken = switch (peerFollowOutcome) {
            case PeerFollowOutcome.None ignored -> DeviceConfirmFacts.PEER_FOLLOW_NONE;
            case PeerFollowOutcome.Corroborated ignored -> DeviceConfirmFacts.PEER_FOLLOW_CORROBORATED;
            case PeerFollowOutcome.NotConfirmed ignored -> DeviceConfirmFacts.PEER_FOLLOW_NOT_CONFIRMED;
        };
        Optional<String> peerReason = switch (peerFollowOutcome) {
            case PeerFollowOutcome.NotConfirmed notConfirmed -> Optional.of(notConfirmed.reason().name());
            default -> Optional.empty();
        };
        Optional<String> clusterMemberRef = peerFollowOutcome instanceof PeerFollowOutcome.Corroborated corroborated
                ? Optional.of(corroborated.unitId())
                : Optional.empty();

        // identityDecision is always NO_BASELINE at this movement's scope (EC-5) -- the mismatch
        // markers stay NONE/empty and the presented identity becomes the recorded baseline. A
        // later re-confirm movement is what would ever observe MATCH/WARN_AND_CONTINUE/REFUSE here.
        String mismatchState = identityDecision == IdentityMismatchEvaluator.Decision.WARN_AND_CONTINUE
                ? DeviceConfirmFacts.IDENTITY_MISMATCH_OPEN
                : DeviceConfirmFacts.IDENTITY_MISMATCH_NONE;

        return new DeviceConfirmFacts(
                facts.hostname(), facts.model(), facts.softwareVersion(), facts.haRole(),
                Optional.of(presented.primary()), presented.secondary(),
                mismatchState, Optional.empty(), Optional.empty(),
                clusterMemberRef, Optional.empty(),
                peerOutcomeToken, peerReason);
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
