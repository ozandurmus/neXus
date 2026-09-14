package com.securityexpert.nexus.ui2.worker.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobReconciler;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * <b>AC-2.</b> A worker that dies after claiming leaves the job in the
 * C2-prescribed state and the device {@code DRAFT}; a later worker
 * reconciles per C2 §4.4. Every confirm step is {@code CLASS_0_READ}
 * (EC-11/EC-12) -- it never crosses a mutation boundary -- so a crash after
 * the one pre-contact attempt row is written (and before its outcome is
 * written) leaves that attempt {@code mutation_boundary_crossed = false},
 * exactly {@link com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository#findExpiredAllBoundaryNo}'s
 * predicate, so {@link JobReconciler} (unmodified, already-tested job-engine
 * machinery) safely requeues the job to {@code REQUESTED} -- never {@code
 * OUTCOME_UNKNOWN} (that branch is reserved for a write step's unconfirmed
 * boundary-crossed attempt, C2 §4.4-3, which a pure-read confirm never has).
 * This test was written and observed failing (compile failure, no {@link
 * ConfirmJobExecutor} existed) before {@link ConfirmJobExecutor} was
 * implemented.
 */
class ConfirmJobExecutorWorkerDiesTest {

    private static final String JOB_ID = "job-confirm-dies-1";
    private static final long LEASE_EPOCH = 3L;
    private static final String DEVICE_ID = "device-draft-2";

    /** A transport whose {@code connect} throws an unchecked exception -- simulating the worker process dying mid-step. */
    private static final class CrashingDeviceTransport implements com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport {
        @Override
        public com.securityexpert.nexus.ui2.jobs.transport.ConnectResult connect(ConnectionTarget target,
                com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec spec, java.time.Duration timeout) {
            throw new RuntimeException("simulated worker process crash mid-device-contact");
        }

        @Override
        public com.securityexpert.nexus.ui2.jobs.transport.ExecResult exec(
                com.securityexpert.nexus.ui2.jobs.transport.TransportSession session,
                com.securityexpert.nexus.ui2.jobs.transport.ExecSpec spec, java.time.Duration timeout) {
            throw new UnsupportedOperationException("unreachable -- connect crashes first");
        }

        @Override
        public com.securityexpert.nexus.ui2.jobs.transport.FetchResult fetch(
                com.securityexpert.nexus.ui2.jobs.transport.TransportSession session,
                com.securityexpert.nexus.ui2.jobs.transport.FetchSpec spec, java.time.Duration timeout) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult xmlApiCall(
                com.securityexpert.nexus.ui2.jobs.transport.ApiTarget target,
                com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec spec, java.time.Duration timeout) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void disconnect(com.securityexpert.nexus.ui2.jobs.transport.TransportSession session) {
            throw new UnsupportedOperationException("unreachable -- connect crashes first");
        }
    }

    @Test
    void aWorkerThatDiesMidStepLeavesTheJobReconcilableAndTheDeviceDraft() {
        ConfirmJobExecutorFakes.FakeLeaseRepository leaseRepo =
                new ConfirmJobExecutorFakes.FakeLeaseRepository(JOB_ID, LEASE_EPOCH, JobState.CLAIMED);
        ConfirmJobExecutorFakes.FakeStepAttemptRepository attemptRepo = new ConfirmJobExecutorFakes.FakeStepAttemptRepository();
        ConfirmJobExecutorFakes.FakeDeviceEnrollmentReadPort devicePort = new ConfirmJobExecutorFakes.FakeDeviceEnrollmentReadPort();
        devicePort.state = DeviceEnrollmentState.DRAFT;
        ConfirmJobExecutorFakes.FakeDeviceRepository deviceRepository = new ConfirmJobExecutorFakes.FakeDeviceRepository();
        deviceRepository.put(DEVICE_ID, DeviceEnrollmentState.DRAFT);

        ConfirmCapabilityExecutor confirmExecutor = new ConfirmCapabilityExecutor(new CrashingDeviceTransport(),
                ref -> { throw new IllegalStateException("not used by this test"); });
        PeerFollowResolver peerFollowResolver = new PeerFollowResolver(confirmExecutor);
        ConfirmJobExecutor executor = new ConfirmJobExecutor(leaseRepo, attemptRepo, devicePort, deviceRepository,
                confirmExecutor, peerFollowResolver);
        ConfirmRequest request = ConfirmRequest.checkPoint(new ConnectionTarget("ep-2", "fw-b-host", 22), "cred-1",
                "trust-1");

        // Phase 1: the worker process "runs" up to the point of device contact, then crashes --
        // an uncaught exception, never a normal return, is how a killed process actually behaves
        // (no finally step, no terminal transition -- nothing here ever runs again for this attempt).
        assertThrows(RuntimeException.class, () -> executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request,
                address -> ConfirmRequest.checkPoint(new ConnectionTarget("ep-peer", address, 22), "cred-1", "trust-1"),
                false));

        assertEquals(JobState.EXECUTING, leaseRepo.currentState, "CLAIMED->EXECUTING committed before contact; "
                + "no terminal transition was ever reached by the crashed run");
        assertEquals(1, attemptRepo.attempts.size(), "exactly the one pre-contact attempt row was written");
        assertTrue(attemptRepo.attempts.values().iterator().next().outcome().isEmpty(),
                "the crash happened before this attempt's outcome was ever written");
        assertFalse(attemptRepo.attempts.values().iterator().next().mutationBoundaryCrossed(),
                "a read-class confirm step never crosses a mutation boundary");
        assertFalse(deviceRepository.recordConfirmSuccessCalled, "no half-enrolled row -- the device write never ran");
        assertEquals(DeviceEnrollmentState.DRAFT, deviceRepository.find(DEVICE_ID).orElseThrow().enrollmentState());

        // Phase 2: a later worker's reconciliation pass (C2 §4.4) finds this lease-expired,
        // all-attempts-boundary-false job and requeues it to REQUESTED -- unmodified job-engine
        // machinery, exercised here only to prove the confirm's own crash shape feeds it correctly.
        leaseRepo.expiredAllBoundaryNo = List.of(new ClaimedJob(JOB_ID, LEASE_EPOCH));
        JobReconciler reconciler = new JobReconciler(leaseRepo);

        JobReconciler.ReconciliationSummary summary = reconciler.reconcileOnce();

        assertEquals(1, summary.requeuedAllBoundaryNo());
        assertEquals(JobState.REQUESTED, leaseRepo.currentState, "the C2-prescribed post-reconciliation state");
        assertTrue(leaseRepo.transitions.contains("EXECUTING->REQUESTED"));
        assertFalse(deviceRepository.recordConfirmSuccessCalled, "still no half-enrolled row after reconciliation");
        assertEquals(DeviceEnrollmentState.DRAFT, deviceRepository.find(DEVICE_ID).orElseThrow().enrollmentState());
    }
}
