package com.securityexpert.nexus.ui2.jobs.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;

/**
 * Proves C2 §4.4's three lease-expiry branches and the "no second device
 * contact, ever" invariant (contract §4, §8 crash matrix), without a
 * database. Complements the disabled container placeholder ({@code
 * WorkerKilledMidStepLandsInOutcomeUnknownNoSecondContactTest} in {@code
 * integration-tests}, which additionally proves this against a real SSH
 * adapter and a container-hosted endpoint) by proving the pure
 * reconciliation logic itself: given only the durable state a crashed
 * worker would have left behind, {@link JobReconciler} classifies it
 * correctly and the fencing check on its own transition write means a
 * worker that wakes up late can never undo the reconciler's decision.
 */
class JobReconcilerTest {

    private static final class FakeLeaseRepository implements JobLeaseRepository {
        List<ClaimedJob> noAttempt = List.of();
        List<ClaimedJob> allBoundaryNo = List.of();
        List<ClaimedJob> unconfirmedYes = List.of();
        final List<String> transitions = new ArrayList<>();
        boolean rejectAllTransitions = false;

        @Override
        public Optional<ClaimedJob> claimNext(String workerId, List<String> eligibleCapabilityIds,
                Duration leaseDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration) {
            return true;
        }

        @Override
        public boolean transitionState(String jobId, long leaseEpoch, JobState expectedFrom, JobState to,
                String actorFingerprint, String actionId) {
            transitions.add(jobId + ":" + expectedFrom + "->" + to);
            return !rejectAllTransitions;
        }

        @Override
        public List<ClaimedJob> findExpiredWithNoAttempt() {
            return noAttempt;
        }

        @Override
        public List<ClaimedJob> findExpiredAllBoundaryNo() {
            return allBoundaryNo;
        }

        @Override
        public List<ClaimedJob> findExpiredUnconfirmedYes() {
            return unconfirmedYes;
        }
    }

    @Test
    void noAttemptRowRequeuesFromClaimed() {
        FakeLeaseRepository repo = new FakeLeaseRepository();
        repo.noAttempt = List.of(new ClaimedJob("job-a", 1L));
        JobReconciler reconciler = new JobReconciler(repo);

        JobReconciler.ReconciliationSummary summary = reconciler.reconcileOnce();

        assertEquals(1, summary.requeuedNoAttempt());
        assertTrue(repo.transitions.contains("job-a:CLAIMED->REQUESTED"));
    }

    @Test
    void everyAttemptBoundaryNoRequeuesFromExecuting() {
        FakeLeaseRepository repo = new FakeLeaseRepository();
        repo.allBoundaryNo = List.of(new ClaimedJob("job-b", 2L));
        JobReconciler reconciler = new JobReconciler(repo);

        JobReconciler.ReconciliationSummary summary = reconciler.reconcileOnce();

        assertEquals(1, summary.requeuedAllBoundaryNo());
        assertTrue(repo.transitions.contains("job-b:EXECUTING->REQUESTED"));
    }

    @Test
    void oneUnconfirmedYesReachesOutcomeUnknownNeverRequeued() {
        FakeLeaseRepository repo = new FakeLeaseRepository();
        repo.unconfirmedYes = List.of(new ClaimedJob("job-c", 3L));
        JobReconciler reconciler = new JobReconciler(repo);

        JobReconciler.ReconciliationSummary summary = reconciler.reconcileOnce();

        assertEquals(1, summary.outcomeUnknown());
        assertTrue(repo.transitions.contains("job-c:EXECUTING->OUTCOME_UNKNOWN"));
        assertTrue(repo.transitions.stream().noneMatch(t -> t.contains("REQUESTED")),
                "a job with an unconfirmed write must never be requeued -- that would be the automatic second "
                        + "device contact this movement's every invariant forbids");
    }

    @Test
    void aFencedReconcilerWriteThatLosesTheRaceChangesNothing() {
        // Models a worker that wakes up late from the same GC pause a
        // second worker's claim already resolved: the reconciler's own
        // transition write is itself fencing-token-guarded (C2 §4.4), so
        // it can lose the race and correctly report zero jobs transitioned
        // rather than overwriting whatever the second worker already did.
        FakeLeaseRepository repo = new FakeLeaseRepository();
        repo.unconfirmedYes = List.of(new ClaimedJob("job-d", 4L));
        repo.rejectAllTransitions = true;
        JobReconciler reconciler = new JobReconciler(repo);

        JobReconciler.ReconciliationSummary summary = reconciler.reconcileOnce();

        assertEquals(0, summary.outcomeUnknown(), "a fenced write that affects zero rows must not be counted as "
                + "a successful reconciliation");
    }
}
