package com.securityexpert.nexus.ui2.worker.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * <b>AC-1, the decisive test.</b> An admitted confirm job is claimed by the
 * worker loop under the C2 lease and, over a fake transport (never a real
 * device), lands the device {@code ENROLLED} with the observed facts --
 * proving the service -> job -> worker -> row path end to end without a
 * device. This test was written and observed failing (compile failure, no
 * {@link ConfirmJobExecutor} existed) before {@link ConfirmJobExecutor} was
 * implemented.
 */
class ConfirmJobExecutorEndToEndTest {

    private static final String JOB_ID = "job-confirm-1";
    private static final long LEASE_EPOCH = 7L;
    private static final String DEVICE_ID = "device-draft-1";

    @Test
    void aClaimedConfirmJobLandsTheDraftDeviceEnrolledWithTheObservedFacts() {
        ConfirmJobExecutorFakes.FakeLeaseRepository leaseRepo =
                new ConfirmJobExecutorFakes.FakeLeaseRepository(JOB_ID, LEASE_EPOCH, JobState.CLAIMED);
        ConfirmJobExecutorFakes.FakeStepAttemptRepository attemptRepo = new ConfirmJobExecutorFakes.FakeStepAttemptRepository();
        ConfirmJobExecutorFakes.FakeDeviceEnrollmentReadPort devicePort = new ConfirmJobExecutorFakes.FakeDeviceEnrollmentReadPort();
        devicePort.state = DeviceEnrollmentState.DRAFT;
        ConfirmJobExecutorFakes.FakeDeviceRepository deviceRepository = new ConfirmJobExecutorFakes.FakeDeviceRepository();
        deviceRepository.put(DEVICE_ID, DeviceEnrollmentState.DRAFT);

        String identityOutput = "Host Name: fw-a\nProduct version R81.20\nAppliance Name: 6000\n";
        String haPeerOutput = "HA is not applicable, not enabled on this machine\n";
        ScriptedDeviceTransport transport = new ScriptedDeviceTransport(Map.of("fw-a-host", identityOutput),
                Map.of("fw-a-host", haPeerOutput));
        ConfirmCapabilityExecutor confirmExecutor =
                new ConfirmCapabilityExecutor(transport, ref -> { throw new IllegalStateException("not used by this test"); });
        PeerFollowResolver peerFollowResolver = new PeerFollowResolver(confirmExecutor);

        ConfirmJobExecutor executor = new ConfirmJobExecutor(leaseRepo, attemptRepo, devicePort, deviceRepository,
                confirmExecutor, peerFollowResolver);

        ConfirmRequest request = ConfirmRequest.checkPoint(new ConnectionTarget("ep-1", "fw-a-host", 22), "cred-1",
                "trust-1");

        JobOutcome outcome = executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request,
                address -> ConfirmRequest.checkPoint(new ConnectionTarget("ep-peer", address, 22), "cred-1", "trust-1"),
                false);

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        assertTrue(leaseRepo.transitions.contains("CLAIMED->EXECUTING"));
        assertTrue(leaseRepo.transitions.contains("EXECUTING->COMPLETED"));
        assertTrue(deviceRepository.recordConfirmSuccessCalled);

        assertEquals(DeviceEnrollmentState.ENROLLED, deviceRepository.find(DEVICE_ID).orElseThrow().enrollmentState());
        var facts = deviceRepository.lastRecordedFacts;
        assertEquals("fw-a", facts.observedHostname().orElseThrow());
        assertEquals("6000", facts.observedModel().orElseThrow());
        assertEquals("R81.20", facts.observedSoftwareVersion().orElseThrow());
        assertEquals("STANDALONE", facts.observedHaRole().orElseThrow());
        assertEquals("NONE", facts.peerFollowOutcome());
        assertEquals("NONE", facts.identityMismatchState());
    }

    @Test
    void hostKeyMismatchAbortsConnectionAndSetsTerminalReasonWithFingerprint() {
        ConfirmJobExecutorFakes.FakeLeaseRepository leaseRepo =
                new ConfirmJobExecutorFakes.FakeLeaseRepository(JOB_ID, LEASE_EPOCH, JobState.CLAIMED);
        ConfirmJobExecutorFakes.FakeStepAttemptRepository attemptRepo = new ConfirmJobExecutorFakes.FakeStepAttemptRepository();
        ConfirmJobExecutorFakes.FakeDeviceEnrollmentReadPort devicePort = new ConfirmJobExecutorFakes.FakeDeviceEnrollmentReadPort();
        devicePort.state = DeviceEnrollmentState.DRAFT;
        ConfirmJobExecutorFakes.FakeDeviceRepository deviceRepository = new ConfirmJobExecutorFakes.FakeDeviceRepository();
        deviceRepository.put(DEVICE_ID, DeviceEnrollmentState.DRAFT);

        ScriptedDeviceTransport transport = new ScriptedDeviceTransport(java.util.Map.of(), java.util.Map.of());
        String mismatchedFingerprint = "SHA256:112233445566778899aabbccddeeff00";
        transport.setConnectResult(new com.securityexpert.nexus.ui2.jobs.transport.ConnectResult.HostKeyRejected(
                "host_key_mismatch: " + mismatchedFingerprint));

        ConfirmCapabilityExecutor confirmExecutor =
                new ConfirmCapabilityExecutor(transport, ref -> { throw new IllegalStateException("not used"); });
        PeerFollowResolver peerFollowResolver = new PeerFollowResolver(confirmExecutor);

        ConfirmJobExecutor executor = new ConfirmJobExecutor(leaseRepo, attemptRepo, devicePort, deviceRepository,
                confirmExecutor, peerFollowResolver);

        ConfirmRequest request = ConfirmRequest.checkPoint(new ConnectionTarget("ep-1", "fw-a-host", 22), "cred-1",
                "trust-1");

        JobOutcome outcome = executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request,
                address -> ConfirmRequest.checkPoint(new ConnectionTarget("ep-peer", address, 22), "cred-1", "trust-1"),
                false);

        assertTrue(outcome instanceof JobOutcome.Failed, "expected Failed, got " + outcome);
        JobOutcome.Failed failed = (JobOutcome.Failed) outcome;
        assertEquals("connect_failed: host_key_mismatch: " + mismatchedFingerprint, failed.terminalReason());
        assertEquals("connect_failed: host_key_mismatch: " + mismatchedFingerprint, leaseRepo.lastTerminalReason);
        org.junit.jupiter.api.Assertions.assertFalse(deviceRepository.recordConfirmSuccessCalled);
        assertEquals(DeviceEnrollmentState.DRAFT, deviceRepository.find(DEVICE_ID).orElseThrow().enrollmentState());
    }
}
