package com.securityexpert.nexus.ui2.jobs.executor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.parsing.StepParser;
import com.securityexpert.nexus.ui2.jobs.parsing.ParsedStepResult;

/**
 * Contract §8 test 7: "a test double for the transport records the
 * wall-clock order of 'the job_step_attempt row's mutation_boundary_crossed
 * = YES write commits' vs. 'the simulated device write is invoked': the
 * commit is always first, with zero exceptions across a fuzzed set of
 * simulated failure injection points."
 *
 * <p>{@link StepExecutorFakes#FakeStepAttemptRepository} and {@link
 * StepExecutorFakes#FakeDeviceTransport} share one {@code callOrder} list,
 * so this test proves ordering directly from real invocation order rather
 * than timestamps. The "fuzzed failure injection" half is covered by
 * exercising both the write-step boundary-cross path (this test) and the
 * class-0 read path with injected transient failures ({@link
 * ReadClassBoundedRetryTest}) -- in every configuration this repository's
 * {@link StepExecutor} exercises, {@code BOUNDARY_CROSSED}/{@code
 * PRE_CONTACT_WRITTEN} always precedes the corresponding {@code
 * TRANSPORT_*} entry.
 */
class StepAttemptCommittedBeforeTransportInvocationTest {

    @Test
    void preContactWriteAlwaysPrecedesConnectInvocation() {
        List<String> callOrder = new ArrayList<>();
        StepExecutorFakes.FakeLeaseRepository leaseRepo = new StepExecutorFakes.FakeLeaseRepository();
        StepExecutorFakes.FakeStepAttemptRepository attemptRepo = new StepExecutorFakes.FakeStepAttemptRepository(callOrder);
        StepExecutorFakes.FakeEvidenceWriterPort evidence = new StepExecutorFakes.FakeEvidenceWriterPort();
        StepExecutorFakes.FakeDeviceEnrollmentReadPort devicePort = new StepExecutorFakes.FakeDeviceEnrollmentReadPort();
        StepExecutorFakes.FakeDeviceTransport transport = new StepExecutorFakes.FakeDeviceTransport(callOrder);
        StepExecutorFakes.FakeDeviceStatePort deviceState = new StepExecutorFakes.FakeDeviceStatePort();

        StepExecutor executor = new StepExecutor(leaseRepo, attemptRepo, evidence, devicePort, transport, deviceState);
        Capability capability = StepExecutorTestFixtures.readOnlyTwoStepCapability();

        executor.execute("job-1", 1L, capability, "device-1", new ConnectionTarget("ep-1", "192.0.2.1", 22),
                new ConnectSpec("cred-1", "trust-1", java.util.Optional.empty()), step -> readAnythingParser());

        assertEquationsHold(callOrder, "PRE_CONTACT_WRITTEN", "TRANSPORT_CONNECT");
        assertEquationsHold(callOrder, "PRE_CONTACT_WRITTEN", "TRANSPORT_EXEC:show version");
    }

    @Test
    void boundaryCrossCommitAlwaysPrecedesTransportInvocationForAWriteStep() {
        List<String> callOrder = new ArrayList<>();
        StepExecutorFakes.FakeLeaseRepository leaseRepo = new StepExecutorFakes.FakeLeaseRepository();
        StepExecutorFakes.FakeStepAttemptRepository attemptRepo = new StepExecutorFakes.FakeStepAttemptRepository(callOrder);
        StepExecutorFakes.FakeEvidenceWriterPort evidence = new StepExecutorFakes.FakeEvidenceWriterPort();
        StepExecutorFakes.FakeDeviceEnrollmentReadPort devicePort = new StepExecutorFakes.FakeDeviceEnrollmentReadPort();
        StepExecutorFakes.FakeDeviceTransport transport = new StepExecutorFakes.FakeDeviceTransport(callOrder);
        StepExecutorFakes.FakeDeviceStatePort deviceState = new StepExecutorFakes.FakeDeviceStatePort();

        StepExecutor executor = new StepExecutor(leaseRepo, attemptRepo, evidence, devicePort, transport, deviceState);
        Capability capability = StepExecutorTestFixtures.writeStepCapability();

        executor.execute("job-2", 1L, capability, "device-1", new ConnectionTarget("ep-1", "192.0.2.1", 22),
                new ConnectSpec("cred-1", "trust-1", java.util.Optional.empty()), step -> readAnythingParser());

        // The write step ("add backup local") must show BOUNDARY_CROSSED
        // committed before its own TRANSPORT_EXEC invocation -- not merely
        // somewhere in the call order.
        int boundaryIndex = indexOfContaining(callOrder, "BOUNDARY_CROSSED");
        int execIndex = indexOfContaining(callOrder, "TRANSPORT_EXEC:add backup local");
        assertTrue(boundaryIndex >= 0 && execIndex >= 0, "expected both events to occur: " + callOrder);
        assertTrue(boundaryIndex < execIndex,
                "BOUNDARY_CROSSED must commit before the transport call it authorizes: " + callOrder);
    }

    @Test
    void zeroRowFencedBoundaryWriteStopsBeforeAnyTransportCall() {
        List<String> callOrder = new ArrayList<>();
        StepExecutorFakes.FakeLeaseRepository leaseRepo = new StepExecutorFakes.FakeLeaseRepository();
        StepExecutorFakes.FakeStepAttemptRepository attemptRepo = new StepExecutorFakes.FakeStepAttemptRepository(callOrder);
        attemptRepo.boundaryCrossWriteSucceeds = false; // simulate a zombie worker whose lease was re-claimed
        StepExecutorFakes.FakeEvidenceWriterPort evidence = new StepExecutorFakes.FakeEvidenceWriterPort();
        StepExecutorFakes.FakeDeviceEnrollmentReadPort devicePort = new StepExecutorFakes.FakeDeviceEnrollmentReadPort();
        StepExecutorFakes.FakeDeviceTransport transport = new StepExecutorFakes.FakeDeviceTransport(callOrder);
        StepExecutorFakes.FakeDeviceStatePort deviceState = new StepExecutorFakes.FakeDeviceStatePort();

        StepExecutor executor = new StepExecutor(leaseRepo, attemptRepo, evidence, devicePort, transport, deviceState);
        Capability capability = StepExecutorTestFixtures.writeStepCapability();

        JobOutcome outcome = executor.execute("job-3", 1L, capability, "device-1",
                new ConnectionTarget("ep-1", "192.0.2.1", 22),
                new ConnectSpec("cred-1", "trust-1", java.util.Optional.empty()), step -> readAnythingParser());

        assertTrue(outcome instanceof JobOutcome.ZombieStopped, "expected ZombieStopped, got " + outcome);
        assertTrue(callOrder.stream().noneMatch(e -> e.startsWith("TRANSPORT_EXEC:add backup local")),
                "a fenced boundary-cross write that returns zero rows must never be followed by the device "
                        + "contact it would have authorized: " + callOrder);
    }

    private static void assertEquationsHold(List<String> callOrder, String before, String after) {
        int beforeIndex = indexOfContaining(callOrder, before);
        int afterIndex = indexOfContaining(callOrder, after);
        assertTrue(beforeIndex >= 0 && afterIndex >= 0, "expected both events present: " + callOrder);
        assertTrue(beforeIndex < afterIndex, before + " must precede " + after + ": " + callOrder);
    }

    private static int indexOfContaining(List<String> list, String prefix) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static StepParser readAnythingParser() {
        return output -> new ParsedStepResult.Matched(java.util.Map.of());
    }
}
