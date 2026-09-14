package com.securityexpert.nexus.ui2.worker.discovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutorFakes.FakeDiscoveryRunRepository;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutorFakes.FakeLeaseRepository;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutorFakes.FakeManagementPlaneEnumeration;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutorFakes.FakePanoramaEnumeration;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutorFakes.FakeStepAttemptRepository;

/**
 * AC-2: on worker death, the run is left {@code REQUESTED}/{@code RUNNING}
 * with no candidates -- proven here by the zero-row-affected fenced write
 * the {@code C2} lease mechanism relies on (contract §4.2), the same
 * mechanism {@code InventoryJobExecutorWorkerDiesTest} proves for inventory.
 */
class DiscoveryJobExecutorWorkerDiesTest {

    @Test
    void aZombieFencingFailureAtClaimToExecutingStopsBeforeAnyContactOrWrite() {
        FakeLeaseRepository lease = new FakeLeaseRepository("job-1", 1, JobState.REQUESTED); // not CLAIMED: fencing already lost
        FakeStepAttemptRepository attempts = new FakeStepAttemptRepository();
        FakeDiscoveryRunRepository runs = new FakeDiscoveryRunRepository();
        runs.run = DiscoveryJobExecutorFakes.requestedRun("run-1", "check_point");
        FakeManagementPlaneEnumeration cpEnumeration = new FakeManagementPlaneEnumeration();

        DiscoveryJobExecutor executor = new DiscoveryJobExecutor(lease, attempts, runs, cpEnumeration,
                new FakePanoramaEnumeration());

        JobOutcome outcome = executor.execute("job-1", 1, "run-1");

        assertTrue(outcome instanceof JobOutcome.ZombieStopped, "expected ZombieStopped, got " + outcome);
        assertFalse(runs.runningCalled, "no run state write happens once the fencing check has already failed");
        assertFalse(runs.finishedCalled);
        assertFalse(runs.failedCalled);
        assertTrue(attempts.attempts.isEmpty(), "no pre-contact attempt row is written after a lost fencing check");
    }

    @Test
    void aZombieFencingFailureAfterAttemptInsertLeavesNoCandidatesAndNoTerminalWrite() {
        FakeLeaseRepository lease = new FakeLeaseRepository("job-1", 1, JobState.CLAIMED);
        FakeStepAttemptRepository attempts = new FakeStepAttemptRepository();
        attempts.refuseWriteOutcome = true; // simulates a second worker's re-claim bumping the epoch mid-run
        FakeDiscoveryRunRepository runs = new FakeDiscoveryRunRepository();
        runs.run = DiscoveryJobExecutorFakes.requestedRun("run-1", "check_point");
        FakeManagementPlaneEnumeration cpEnumeration = new FakeManagementPlaneEnumeration();
        cpEnumeration.result = new com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult.Failed(
                "unreachable", 1, com.securityexpert.nexus.ui2.discovery.cp.SessionDisconnectOutcome.CLOSED);

        DiscoveryJobExecutor executor = new DiscoveryJobExecutor(lease, attempts, runs, cpEnumeration,
                new FakePanoramaEnumeration());

        JobOutcome outcome = executor.execute("job-1", 1, "run-1");

        assertTrue(outcome instanceof JobOutcome.ZombieStopped, "expected ZombieStopped, got " + outcome);
        assertFalse(runs.finishedCalled);
        assertFalse(runs.failedCalled, "a lost fencing check never reaches the run's own terminal write");
        assertTrue(runs.lastReplacedCandidates == null, "no candidate rows are ever persisted for a job this worker no longer owns");
    }
}
