package com.securityexpert.nexus.ui2.jobs.executor;

import java.util.Objects;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.platform.WorkerActor;

/**
 * C2 §4.4's lease-expiry reconciler. This class, not {@link StepExecutor},
 * is what makes the crash matrix (contract §8, C2 §8) well-defined: it
 * never contacts a device, it only reads durable {@code job_step_attempt}
 * state and applies exactly one of three transitions, fenced against the
 * epoch the expired lease was issued under (C2 §4.4: "the reconciler's own
 * transition writes are themselves fencing-token-guarded"). Run
 * periodically by any worker, or once on demand -- it is idempotent: a job
 * no longer matching any {@code findExpired*} predicate by the time this
 * runs (already reclaimed, already recovered) is simply absent from the
 * next scan, never double-transitioned, because every transition itself
 * carries {@code WHERE state = <expected> AND lease_epoch = <expected>}.
 */
public final class JobReconciler {

    private static final String RECONCILER_ACTOR = WorkerActor.RESERVED_ACTOR_FINGERPRINT;
    private static final String ACTION_RECONCILE_REQUEUE = "job_reconcile_requeue";
    private static final String ACTION_RECONCILE_OUTCOME_UNKNOWN = "job_reconcile_outcome_unknown";

    private final JobLeaseRepository leaseRepository;

    public JobReconciler(JobLeaseRepository leaseRepository) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
    }

    /** @return how many jobs each branch actually transitioned (a fenced write can legitimately affect zero, see class javadoc). */
    public ReconciliationSummary reconcileOnce() {
        int requeuedNoAttempt = 0;
        for (ClaimedJob job : leaseRepository.findExpiredWithNoAttempt()) {
            if (leaseRepository.transitionState(job.jobId(), job.leaseEpoch(), JobState.CLAIMED, JobState.REQUESTED,
                    RECONCILER_ACTOR, ACTION_RECONCILE_REQUEUE)) {
                requeuedNoAttempt++;
            }
        }

        int requeuedAllBoundaryNo = 0;
        for (ClaimedJob job : leaseRepository.findExpiredAllBoundaryNo()) {
            if (leaseRepository.transitionState(job.jobId(), job.leaseEpoch(), JobState.EXECUTING, JobState.REQUESTED,
                    RECONCILER_ACTOR, ACTION_RECONCILE_REQUEUE)) {
                requeuedAllBoundaryNo++;
            }
        }

        int outcomeUnknown = 0;
        for (ClaimedJob job : leaseRepository.findExpiredUnconfirmedYes()) {
            // The one edge this movement's own AGENTS.md/contract invariant
            // depends on: EXECUTING -> OUTCOME_UNKNOWN, never re-claimed
            // (C2 §4.4-3). No component past this point ever writes
            // REQUESTED onto this job_id again (contract §4).
            if (leaseRepository.transitionState(job.jobId(), job.leaseEpoch(), JobState.EXECUTING,
                    JobState.OUTCOME_UNKNOWN, RECONCILER_ACTOR, ACTION_RECONCILE_OUTCOME_UNKNOWN)) {
                outcomeUnknown++;
            }
        }

        return new ReconciliationSummary(requeuedNoAttempt, requeuedAllBoundaryNo, outcomeUnknown);
    }

    public record ReconciliationSummary(int requeuedNoAttempt, int requeuedAllBoundaryNo, int outcomeUnknown) {
    }
}
