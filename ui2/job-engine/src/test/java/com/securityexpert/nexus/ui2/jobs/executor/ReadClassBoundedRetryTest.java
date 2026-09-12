package com.securityexpert.nexus.ui2.jobs.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.jobs.parsing.ParsedStepResult;
import com.securityexpert.nexus.ui2.jobs.parsing.StepParser;
import com.securityexpert.nexus.ui2.jobs.stepattempt.StepAttempt;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;

/**
 * Contract §8 test 8 (C2 §9 criterion 6): "a class-0 step whose transport
 * fails twice then succeeds on the third attempt completes the job
 * COMPLETED, with three job_step_attempt rows at the same step_index and
 * increasing attempt_number; a fourth consecutive failure... yields FAILED,
 * never OUTCOME_UNKNOWN."
 */
class ReadClassBoundedRetryTest {

    private static final int EXEC_STEP_INDEX = 1; // steps: [0]=connect, [1]=exec("show version"), [2]=disconnect

    @Test
    void twoTransientFailuresThenSuccessCompletesWithThreeAttemptRows() {
        List<String> callOrder = new ArrayList<>();
        StepExecutorFakes.FakeLeaseRepository leaseRepo = new StepExecutorFakes.FakeLeaseRepository();
        StepExecutorFakes.FakeStepAttemptRepository attemptRepo = new StepExecutorFakes.FakeStepAttemptRepository(callOrder);
        StepExecutorFakes.FakeDeviceTransport transport = new StepExecutorFakes.FakeDeviceTransport(callOrder);
        transport.scriptedExecResults.add(new ExecResult.TimedOut());
        transport.scriptedExecResults.add(new ExecResult.TimedOut());
        transport.scriptedExecResults.add(new ExecResult.Completed("Product version R81.20", 0));

        StepExecutor executor = buildExecutor(leaseRepo, attemptRepo, transport);
        Capability capability = StepExecutorTestFixtures.readOnlyTwoStepCapability();

        JobOutcome outcome = executor.execute("job-retry-1", 1L, capability, "device-1",
                new ConnectionTarget("ep-1", "192.0.2.1", 22),
                new ConnectSpec("cred-1", "trust-1", Optional.empty()), step -> matchAnythingParser());

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        List<StepAttempt> attempts = attemptRepo.findByJobAndStep("job-retry-1", EXEC_STEP_INDEX);
        assertEquals(3, attempts.size(), "expected three attempt rows at the same step_index: " + attempts);
        for (int i = 0; i < attempts.size(); i++) {
            assertEquals(i + 1, attempts.get(i).attemptNumber());
        }
    }

    @Test
    void exhaustingTheRetryBudgetYieldsFailedNeverOutcomeUnknown() {
        List<String> callOrder = new ArrayList<>();
        StepExecutorFakes.FakeLeaseRepository leaseRepo = new StepExecutorFakes.FakeLeaseRepository();
        StepExecutorFakes.FakeStepAttemptRepository attemptRepo = new StepExecutorFakes.FakeStepAttemptRepository(callOrder);
        StepExecutorFakes.FakeDeviceTransport transport = new StepExecutorFakes.FakeDeviceTransport(callOrder);
        transport.scriptedExecResults.add(new ExecResult.TimedOut());
        transport.scriptedExecResults.add(new ExecResult.TimedOut());
        transport.scriptedExecResults.add(new ExecResult.TimedOut());

        StepExecutor executor = buildExecutor(leaseRepo, attemptRepo, transport);
        Capability capability = StepExecutorTestFixtures.readOnlyTwoStepCapability();

        JobOutcome outcome = executor.execute("job-retry-2", 1L, capability, "device-1",
                new ConnectionTarget("ep-1", "192.0.2.1", 22),
                new ConnectSpec("cred-1", "trust-1", Optional.empty()), step -> matchAnythingParser());

        assertTrue(outcome instanceof JobOutcome.Failed, "expected Failed, never OUTCOME_UNKNOWN for a class-0 "
                + "job (C2 §5.3): got " + outcome);
        List<StepAttempt> attempts = attemptRepo.findByJobAndStep("job-retry-2", EXEC_STEP_INDEX);
        assertEquals(3, attempts.size(), "retry budget exhausted at three attempts, never a fourth: " + attempts);
    }

    private static StepExecutor buildExecutor(StepExecutorFakes.FakeLeaseRepository leaseRepo,
            StepExecutorFakes.FakeStepAttemptRepository attemptRepo, StepExecutorFakes.FakeDeviceTransport transport) {
        return new StepExecutor(leaseRepo, attemptRepo, new StepExecutorFakes.FakeEvidenceWriterPort(),
                new StepExecutorFakes.FakeDeviceEnrollmentReadPort(), transport,
                new StepExecutorFakes.FakeDeviceStatePort());
    }

    private static StepParser matchAnythingParser() {
        return output -> output != null && output.startsWith("Product version")
                ? new ParsedStepResult.Matched(java.util.Map.of())
                : new ParsedStepResult.ExpectationUnmet("no match");
    }
}
