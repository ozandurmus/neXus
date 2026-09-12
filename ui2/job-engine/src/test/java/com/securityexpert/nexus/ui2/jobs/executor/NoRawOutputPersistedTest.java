package com.securityexpert.nexus.ui2.jobs.executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.jobs.evidence.ProvenanceRecordData;
import com.securityexpert.nexus.ui2.jobs.parsing.ParsedStepResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;

/**
 * Contract §8 test 13 (AC-9): "fails if any persisted column carries a raw
 * response slice beyond the declared bound." {@link
 * com.securityexpert.nexus.ui2.jobs.evidence.EvidenceWriterPort}'s own
 * signature ({@link ProvenanceRecordData}/{@code StepAttemptOutcome}) has
 * no field wide enough to carry an unbounded raw transcript by
 * construction; this test additionally proves {@link StepExecutor} itself
 * never routes the raw device response into any evidence field it does
 * expose -- only the response's SHA-256 fingerprint reaches persistence.
 */
class NoRawOutputPersistedTest {

    @Test
    void rawDeviceOutputNeverReachesAnyPersistedEvidenceField() {
        String rawOutput = "Product version R81.20\n" + "SECRET-LOOKING-TOKEN-abc123\n".repeat(50);

        List<String> callOrder = new ArrayList<>();
        StepExecutorFakes.FakeLeaseRepository leaseRepo = new StepExecutorFakes.FakeLeaseRepository();
        StepExecutorFakes.FakeStepAttemptRepository attemptRepo = new StepExecutorFakes.FakeStepAttemptRepository(callOrder);
        StepExecutorFakes.FakeEvidenceWriterPort evidence = new StepExecutorFakes.FakeEvidenceWriterPort();
        StepExecutorFakes.FakeDeviceTransport transport = new StepExecutorFakes.FakeDeviceTransport(callOrder);
        transport.scriptedExecResults.add(new ExecResult.Completed(rawOutput, 0));

        StepExecutor executor = new StepExecutor(leaseRepo, attemptRepo, evidence,
                new StepExecutorFakes.FakeDeviceEnrollmentReadPort(), transport,
                new StepExecutorFakes.FakeDeviceStatePort());
        Capability capability = StepExecutorTestFixtures.readOnlyTwoStepCapability();

        executor.execute("job-raw", 1L, capability, "device-1", new ConnectionTarget("ep-1", "192.0.2.1", 22),
                new ConnectSpec("cred-1", "trust-1", Optional.empty()),
                step -> output -> new ParsedStepResult.Matched(java.util.Map.of()));

        assertFalse(evidence.provenanceWrites.isEmpty(), "expected at least one evidence write");
        for (ProvenanceRecordData record : evidence.provenanceWrites) {
            assertFalse(record.sourceLocation().contains(rawOutput), "sourceLocation must never carry raw output");
            assertTrue(record.sanitizedFragment().isEmpty(),
                    "this capability's evidence_shape_ref permits no sanitized fragment by default -- "
                            + "sanitizedFragment must stay empty (contract §7: 'by default none is written')");
            assertFalse(record.fingerprintSha256().contains("SECRET-LOOKING-TOKEN"),
                    "fingerprintSha256 must be a digest, never a slice of the raw response");
        }
        for (var outcome : evidence.outcomeWrites) {
            assertFalse(String.valueOf(outcome.outcome()).contains("SECRET-LOOKING-TOKEN"),
                    "the persisted outcome field must never carry a raw response fragment");
        }
    }
}
