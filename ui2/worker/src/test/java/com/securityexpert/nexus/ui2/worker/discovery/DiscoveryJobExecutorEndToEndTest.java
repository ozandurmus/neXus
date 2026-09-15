package com.securityexpert.nexus.ui2.worker.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.cp.Address;
import com.securityexpert.nexus.ui2.discovery.cp.CandidateKey;
import com.securityexpert.nexus.ui2.discovery.cp.ClassificationFlags;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult;
import com.securityexpert.nexus.ui2.discovery.cp.ObjectType;
import com.securityexpert.nexus.ui2.discovery.cp.RawCandidateInput;
import com.securityexpert.nexus.ui2.discovery.cp.SessionDisconnectOutcome;
import com.securityexpert.nexus.ui2.discovery.pan.KeyDisposalOutcome;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationResult;
import com.securityexpert.nexus.ui2.discovery.pan.RawDeviceInput;
import com.securityexpert.nexus.ui2.discovery.pan.RawVirtualSystemInput;
import com.securityexpert.nexus.ui2.discovery.pan.Serial;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutorFakes.FakeDiscoveryRunRepository;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutorFakes.FakeLeaseRepository;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutorFakes.FakeManagementPlaneEnumeration;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutorFakes.FakePanoramaEnumeration;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutorFakes.FakeStepAttemptRepository;

/**
 * AC-2: against scripted fake vendor transports, {@link DiscoveryJobExecutor}
 * persists the Check Point and Palo Alto candidate sets with the import
 * contract's kinds and importability, and marks the run FINISHED with a
 * counts-only summary.
 */
class DiscoveryJobExecutorEndToEndTest {

    @Test
    void checkPointRunPersistsCandidatesWithHostLinkedVirtualSystemImportableAndFinishesTheRun() {
        FakeLeaseRepository lease = new FakeLeaseRepository("job-1", 1, JobState.CLAIMED);
        FakeStepAttemptRepository attempts = new FakeStepAttemptRepository();
        FakeDiscoveryRunRepository runs = new FakeDiscoveryRunRepository();
        runs.run = DiscoveryJobExecutorFakes.requestedRun("run-1", "check_point");

        RawCandidateInput host = new RawCandidateInput(
                new CandidateKey(OpaqueId.of("domain-1"), OpaqueId.of("host-1")), ObjectType.GATEWAY,
                new ClassificationFlags(true, true, false), "host-display", Address.of("10.1.1.1"),
                Address.of("10.1.1.1"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        RawCandidateInput virtualSystem = new RawCandidateInput(
                new CandidateKey(OpaqueId.of("domain-1"), OpaqueId.of("vs-1")), ObjectType.GATEWAY,
                new ClassificationFlags(true, false, true), "vs-display", Address.absent(), Address.of("10.1.1.1"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        FakeManagementPlaneEnumeration cpEnumeration = new FakeManagementPlaneEnumeration();
        cpEnumeration.result = new ManagementPlaneEnumerationResult.Completed(List.of(host, virtualSystem),
                Map.of(), 5, SessionDisconnectOutcome.CLOSED, Map.of());

        DiscoveryJobExecutor executor = new DiscoveryJobExecutor(lease, attempts, runs, cpEnumeration,
                new FakePanoramaEnumeration());

        JobOutcome outcome = executor.execute("job-1", 1, "run-1");

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        assertTrue(runs.runningCalled);
        assertTrue(runs.finishedCalled);
        assertEquals(List.of("CLAIMED->EXECUTING", "EXECUTING->COMPLETED"), lease.transitions);

        List<DiscoveryCandidateRecord> records = runs.lastReplacedCandidates;
        assertEquals(2, records.size());
        DiscoveryCandidateRecord hostRecord = records.stream().filter(r -> r.stableIdentifier().equals("host-1")).findFirst().orElseThrow();
        DiscoveryCandidateRecord vsRecord = records.stream().filter(r -> r.stableIdentifier().equals("vs-1")).findFirst().orElseThrow();
        assertEquals("STANDALONE_VIRTUALIZATION_HOST", hostRecord.kind());
        assertTrue(hostRecord.importable());
        assertEquals(Optional.empty(), hostRecord.parentCandidateId());
        assertEquals("STANDALONE_VIRTUAL_SYSTEM", vsRecord.kind());
        assertTrue(vsRecord.importable(), "a virtual system whose host resolved within the run is importable (IM-2)");
        assertEquals(Optional.of(hostRecord.candidateId()), vsRecord.parentCandidateId());

        assertEquals(2, runs.lastOutcomeSummary.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void paloAltoRunPersistsReciprocalPairAndNestedVirtualSystemAndFinishesTheRun() {
        FakeLeaseRepository lease = new FakeLeaseRepository("job-2", 1, JobState.CLAIMED);
        FakeStepAttemptRepository attempts = new FakeStepAttemptRepository();
        FakeDiscoveryRunRepository runs = new FakeDiscoveryRunRepository();
        runs.run = DiscoveryJobExecutorFakes.requestedRun("run-2", "palo_alto");

        RawDeviceInput deviceA = new RawDeviceInput(Serial.of("serial-a"), "device-a", "", Optional.of("10.2.2.1"),
                Optional.empty(), Serial.of("serial-b"), Optional.of("established"), Optional.empty(),
                Optional.empty(), Optional.empty(),
                List.of(new RawVirtualSystemInput(Serial.of("vsys-1"), "vs1", Optional.empty(), Optional.empty(),
                        Optional.empty())));
        RawDeviceInput deviceB = new RawDeviceInput(Serial.of("serial-b"), "device-b", "", Optional.of("10.2.2.2"),
                Optional.empty(), Serial.of("serial-a"), Optional.of("established"), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of());

        FakePanoramaEnumeration panEnumeration = new FakePanoramaEnumeration();
        panEnumeration.result = new PanoramaEnumerationResult.Completed(List.of(deviceA, deviceB), 2,
                KeyDisposalOutcome.DISCARDED);

        DiscoveryJobExecutor executor = new DiscoveryJobExecutor(lease, attempts, runs, new FakeManagementPlaneEnumeration(),
                panEnumeration);

        JobOutcome outcome = executor.execute("job-2", 1, "run-2");

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        List<DiscoveryCandidateRecord> records = runs.lastReplacedCandidates;
        assertEquals(3, records.size());
        DiscoveryCandidateRecord a = records.stream().filter(r -> r.stableIdentifier().equals("serial-a")).findFirst().orElseThrow();
        DiscoveryCandidateRecord b = records.stream().filter(r -> r.stableIdentifier().equals("serial-b")).findFirst().orElseThrow();
        DiscoveryCandidateRecord vs = records.stream().filter(r -> r.stableIdentifier().equals("vsys-1")).findFirst().orElseThrow();

        assertEquals("PALO_ALTO_DEVICE", a.kind());
        assertTrue(a.importable());
        assertEquals(a.clusterReference(), b.clusterReference(), "a reciprocal pair shares one cluster_reference");
        assertTrue(a.clusterReference().isPresent());

        assertEquals("PALO_ALTO_VIRTUAL_SYSTEM", vs.kind());
        assertEquals(Optional.of(a.candidateId()), vs.parentCandidateId());
        assertTrue(vs.importable(), "a virtual system nested under a present host is importable");
    }

    @Test
    void failedDiscoveryUsesNamedReasonAndMeasuredAttempt() {
        FakeLeaseRepository lease = new FakeLeaseRepository("job-failed", 1, JobState.CLAIMED);
        FakeStepAttemptRepository attempts = new FakeStepAttemptRepository();
        FakeDiscoveryRunRepository runs = new FakeDiscoveryRunRepository();
        runs.run = DiscoveryJobExecutorFakes.requestedRun("run-failed", "check_point");
        FakeManagementPlaneEnumeration cp = new FakeManagementPlaneEnumeration();
        cp.result = new ManagementPlaneEnumerationResult.Failed("unreachable", 1, SessionDisconnectOutcome.CLOSED);

        JobOutcome outcome = new DiscoveryJobExecutor(lease, attempts, runs, cp,
                new FakePanoramaEnumeration()).execute("job-failed", 1, "run-failed");

        assertTrue(outcome instanceof JobOutcome.Failed);
        assertEquals("UNREACHABLE", runs.lastFailureReasonClass);
        assertEquals("UNREACHABLE", lease.terminalReason);
        assertEquals(Boolean.FALSE, attempts.lastMatchedExpectation);
        assertTrue(attempts.lastOutputBytes > 0, "measurement must not be the old hardcoded zero");
    }
}
