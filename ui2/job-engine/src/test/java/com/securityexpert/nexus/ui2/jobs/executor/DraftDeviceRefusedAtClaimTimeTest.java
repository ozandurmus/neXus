package com.securityexpert.nexus.ui2.jobs.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.jobs.parsing.ParsedStepResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * Adjudication F6, second enforcement point: "C2 §6 check 5 re-checks at
 * claim time." A device that was {@code ENROLLED} at admission but has
 * since gone {@code DRAFT} (or been disabled) is refused here, before
 * {@code EXECUTING}, before any device contact -- distinct from, and
 * independent of, {@link
 * com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionServiceTest}'s
 * admission-time refusal (F6's first enforcement point).
 */
class DraftDeviceRefusedAtClaimTimeTest {

    @Test
    void aDraftDeviceAtClaimTimeIsRejectedWithNoAttemptRowAndNoDeviceContact() {
        List<String> callOrder = new ArrayList<>();
        StepExecutorFakes.FakeLeaseRepository leaseRepo = new StepExecutorFakes.FakeLeaseRepository();
        StepExecutorFakes.FakeStepAttemptRepository attemptRepo = new StepExecutorFakes.FakeStepAttemptRepository(callOrder);
        StepExecutorFakes.FakeDeviceTransport transport = new StepExecutorFakes.FakeDeviceTransport(callOrder);
        StepExecutorFakes.FakeDeviceEnrollmentReadPort devicePort = new StepExecutorFakes.FakeDeviceEnrollmentReadPort();
        devicePort.state = DeviceEnrollmentState.DRAFT;

        StepExecutor executor = new StepExecutor(leaseRepo, attemptRepo, new StepExecutorFakes.FakeEvidenceWriterPort(),
                devicePort, transport, new StepExecutorFakes.FakeDeviceStatePort());
        Capability capability = StepExecutorTestFixtures.readOnlyTwoStepCapability();

        JobOutcome outcome = executor.execute("job-draft", 1L, capability, "device-draft",
                new ConnectionTarget("ep-1", "192.0.2.1", 22),
                new ConnectSpec("cred-1", "trust-1", Optional.empty()),
                step -> output -> new ParsedStepResult.Matched(java.util.Map.of()));

        assertTrue(outcome instanceof JobOutcome.Rejected, "expected Rejected, got " + outcome);
        assertEquals(0, transport.connectCalls, "a DRAFT device at claim time must never be contacted");
        assertEquals(0, transport.execCalls);
        assertTrue(attemptRepo.attempts.isEmpty(), "no job_step_attempt row is ever written for a claim-time refusal");
        assertTrue(leaseRepo.transitions.contains("CLAIMED->REJECTED"));
    }
}
