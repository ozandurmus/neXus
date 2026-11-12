package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

class CredentialCanaryJobExecutorTest {
    private static final String JOB = "job-canary-1";
    private static final long EPOCH = 3L;
    private static final String DEVICE = "dev-canary-1";

    private final ScriptedBackupTransport transport = new ScriptedBackupTransport();
    private final BackupJobExecutorFakes.FakeLeaseRepository lease =
            new BackupJobExecutorFakes.FakeLeaseRepository(JOB, EPOCH, JobState.CLAIMED);
    private final BackupJobExecutorFakes.FakeStepAttemptRepository attempts = new BackupJobExecutorFakes.FakeStepAttemptRepository();
    private final BackupJobExecutorFakes.FakeDeviceEnrollmentReadPort enrollment = new BackupJobExecutorFakes.FakeDeviceEnrollmentReadPort();
    private final RecordingCredentialAlerts alerts = new RecordingCredentialAlerts();

    private CredentialCanaryJobExecutor executor() {
        return new CredentialCanaryJobExecutor(lease, attempts, enrollment, transport, new CredentialFailureReporter(alerts));
    }

    private static BackupRequest request(Optional<String> ref) {
        return new BackupRequest(new ConnectionTarget("endpoint-1", "192.0.2.10", 22), ref, "utils.cp_ssh_trust");
    }

    private void assertNothingWasSent() {
        assertEquals(1, transport.connectCount, "one authentication");
        assertEquals(0, transport.execCount, "no exec: the canary sends no device command");
        assertEquals(0, transport.fetchCount, "no fetch");
        assertTrue(transport.commandsIssued.isEmpty());
        assertTrue(transport.disconnectCalled, "disconnects at once");
    }

    @Test
    void authenticatedClosesTheAlertCompletesAndSendsNothing() {
        var outcome = executor().execute(JOB, EPOCH, DEVICE, request(Optional.of("cred-1")));
        assertInstanceOf(JobOutcome.Completed.class, outcome);
        assertEquals(List.of("cred-1"), alerts.closed);
        assertTrue(alerts.failures.isEmpty());
        assertEquals(List.of("CLAIMED->EXECUTING", "EXECUTING->COMPLETED"), lease.transitions);
        assertNothingWasSent();
        var attempt = attempts.attempts.values().iterator().next();
        assertEquals("read", attempt.actionClass(), "class 0 read");
        assertEquals(false, attempt.mutationBoundaryCrossed());
    }

    @Test
    void authenticationFailureOpensTheAlertForTheCanaryDeviceAndFailsTheJob() {
        transport.connectFails = true;
        var outcome = assertInstanceOf(JobOutcome.Failed.class, executor().execute(JOB, EPOCH, DEVICE, request(Optional.of("cred-1"))));
        assertEquals("credential_canary_auth_failed", outcome.terminalReason());
        assertEquals(List.of("cred-1|check_point|" + JOB + "|" + DEVICE), alerts.failures);
        assertTrue(alerts.closed.isEmpty());
        assertEquals(0, transport.execCount);
        assertEquals(0, transport.fetchCount);
        assertTrue(alerts.holds.isEmpty(), "the canary alone holds nothing; the open alert does");
    }

    @Test
    void timeoutAndHostKeyFailuresKeepTheirOwnReasonAndLeaveTheAlertAlone() {
        transport.connectOverride = new ConnectResult.TimedOut("connect timed out after 30s");
        var timeout = assertInstanceOf(JobOutcome.Failed.class, executor().execute(JOB, EPOCH, DEVICE, request(Optional.of("cred-1"))));
        assertTrue(timeout.terminalReason().startsWith("credential_canary_timed_out"), timeout.terminalReason());

        var other = new ScriptedBackupTransport();
        other.connectOverride = new ConnectResult.HostKeyRejected("host_key_mismatch: changed");
        var lease2 = new BackupJobExecutorFakes.FakeLeaseRepository(JOB, EPOCH, JobState.CLAIMED);
        var hostKey = assertInstanceOf(JobOutcome.Failed.class, new CredentialCanaryJobExecutor(lease2,
                new BackupJobExecutorFakes.FakeStepAttemptRepository(), enrollment, other, new CredentialFailureReporter(alerts))
                .execute(JOB, EPOCH, DEVICE, request(Optional.of("cred-1"))));
        assertTrue(hostKey.terminalReason().startsWith("credential_canary_host_key_rejected"), hostKey.terminalReason());
        assertTrue(alerts.failures.isEmpty(), "no alert change");
        assertTrue(alerts.closed.isEmpty(), "no alert change");
    }

    @Test
    void anIneligibleDeviceIsRejectedBeforeAnyContact() {
        enrollment.state = DeviceEnrollmentState.DRAFT;
        assertInstanceOf(JobOutcome.Rejected.class, executor().execute(JOB, EPOCH, DEVICE, request(Optional.of("cred-1"))));
        assertEquals(0, transport.connectCount);
    }

    @Test
    void noConfiguredCredentialFailsWithoutContact() {
        var outcome = assertInstanceOf(JobOutcome.Failed.class, executor().execute(JOB, EPOCH, DEVICE, request(Optional.empty())));
        assertTrue(outcome.terminalReason().startsWith("credential_canary_not_configured"));
        assertEquals(0, transport.connectCount);
    }
}
