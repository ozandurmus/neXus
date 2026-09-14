package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobReconciler;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

/**
 * <b>AC-4.</b> A worker that dies mid-contact leaves the job reconcilable
 * and records no run -- {@code inventory_collect} is a pure {@code
 * CLASS_0_READ} job, exactly like the confirm's own crash shape
 * ({@code ConfirmJobExecutorWorkerDiesTest}).
 */
class InventoryJobExecutorWorkerDiesTest {

    private static final String JOB_ID = "job-inventory-dies-1";
    private static final long LEASE_EPOCH = 2L;
    private static final String DEVICE_ID = "device-enrolled-2";

    private static final class CrashingDeviceTransport implements DeviceTransport {
        @Override
        public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            throw new RuntimeException("simulated worker process crash mid-device-contact");
        }

        @Override
        public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("unreachable -- connect crashes first");
        }

        @Override
        public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void disconnect(TransportSession session) {
            throw new UnsupportedOperationException("unreachable -- connect crashes first");
        }
    }

    @Test
    void aWorkerThatDiesMidStepLeavesTheJobReconcilableAndRecordsNoRun() {
        InventoryJobExecutorFakes.FakeLeaseRepository leaseRepo =
                new InventoryJobExecutorFakes.FakeLeaseRepository(JOB_ID, LEASE_EPOCH, JobState.CLAIMED);
        InventoryJobExecutorFakes.FakeStepAttemptRepository attemptRepo = new InventoryJobExecutorFakes.FakeStepAttemptRepository();
        InventoryJobExecutorFakes.FakeDeviceEnrollmentReadPort devicePort = new InventoryJobExecutorFakes.FakeDeviceEnrollmentReadPort();
        InventoryJobExecutorFakes.FakeDeviceRepository deviceRepository = new InventoryJobExecutorFakes.FakeDeviceRepository();
        InventoryJobExecutorFakes.FakeDeviceInventoryRepository inventoryRepository =
                new InventoryJobExecutorFakes.FakeDeviceInventoryRepository();

        InventoryCapabilityExecutor capabilityExecutor = new InventoryCapabilityExecutor(new CrashingDeviceTransport(),
                ref -> { throw new IllegalStateException("not used by this test"); });
        InventoryJobExecutor executor = new InventoryJobExecutor(leaseRepo, attemptRepo, devicePort, deviceRepository,
                inventoryRepository, capabilityExecutor);
        InventoryRequest request =
                InventoryRequest.checkPoint(new ConnectionTarget("ep-3", "gw-c-host", 22), "cred-1", "trust-1");

        assertThrows(RuntimeException.class, () -> executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request, false));

        assertEquals(JobState.EXECUTING, leaseRepo.currentState, "CLAIMED->EXECUTING committed before contact; "
                + "no terminal transition was ever reached by the crashed run");
        assertEquals(1, attemptRepo.attempts.size(), "exactly the one pre-contact attempt row was written");
        assertTrue(attemptRepo.attempts.values().iterator().next().outcome().isEmpty(),
                "the crash happened before this attempt's outcome was ever written");
        assertFalse(attemptRepo.attempts.values().iterator().next().mutationBoundaryCrossed());
        assertFalse(inventoryRepository.recordRunCalled, "no partial run is ever recorded");

        leaseRepo.expiredAllBoundaryNo = List.of(new ClaimedJob(JOB_ID, LEASE_EPOCH));
        JobReconciler reconciler = new JobReconciler(leaseRepo);

        JobReconciler.ReconciliationSummary summary = reconciler.reconcileOnce();

        assertEquals(1, summary.requeuedAllBoundaryNo());
        assertEquals(JobState.REQUESTED, leaseRepo.currentState);
        assertTrue(leaseRepo.transitions.contains("EXECUTING->REQUESTED"));
        assertFalse(inventoryRepository.recordRunCalled, "still no run recorded after reconciliation");
    }
}
