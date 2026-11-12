package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;

/**
 * PO 2026-11-13: a rule-complete snapshot whose rule count drops sharply against the stored one, with no change
 * evidence for its container in this run, is not made current; the job fails (job-failure alert route) and the last
 * rule-complete snapshot stays. With change evidence (policy_revision CHANGED) it is published after the run.
 */
class PolicyCollectionRuleDropGuardTest {
    private static PolicySnapshot snapshot(int rules) {
        var cell = new Cell(List.of(), false);
        List<Rule> list = new ArrayList<>();
        for (int i = 0; i < rules; i++) list.add(new Rule("r" + i, "u" + i, i + 1, "RULE-TEST-" + i, true, cell, cell, cell, cell, "Accept", "Log", "", Map.of()));
        return new PolicySnapshot(new Metadata("p1", "mds-1", "MGR-BRAVO-01", "CP", "c1", "DOM-TANGO-01", "POL-ALPHA-01",
            "2026-11-13T00:00:00Z", "a1", List.of()), List.of(new Section("s1", "Section A", "CP access layer", null, list)), Map.of());
    }

    private record Fixture(PolicyCollectionJobExecutor executor, PolicyCollectionRepository repository) {}

    private static Fixture fixture(int storedRules, int collectedRules, String revision, boolean completeness) {
        var leases = mock(JobLeaseRepository.class);
        var attempts = mock(JobStepAttemptRepository.class);
        var runs = mock(DiscoveryRunRepository.class);
        var repository = mock(PolicyCollectionRepository.class);
        var collector = mock(CheckPointPolicyCollector.class);
        var run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
            DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        when(runs.findRun("run-1")).thenReturn(Optional.of(run));
        when(repository.request("job-1")).thenReturn(Optional.of(new PolicyCollectionRepository.Request("mds-1", "", false)));
        when(repository.eligible("mds-1", "run-1")).thenReturn(true);
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt())).thenReturn("attempt-1");
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), isNull(), anyBoolean(), any(), isNull(), isNull())).thenReturn(true);
        when(repository.publish(anyString(), anyLong(), anyList(), anyString())).thenReturn(true);
        when(repository.publish(anyString(), anyLong(), anyList(), anyString(), anyString())).thenReturn(true);
        when(repository.checkpoint(anyString(), anyLong(), any(), anyString())).thenReturn(true);
        when(repository.ruleFacts("p1")).thenReturn(Optional.of(new PolicySnapshotRepository.RuleFacts(storedRules, true)));
        when(repository.revisionState("job-1", "c1")).thenReturn(Optional.ofNullable(revision));
        var collected = snapshot(collectedRules);
        doAnswer(call -> {
            @SuppressWarnings("unchecked") var publish = (Consumer<PolicySnapshot>) call.getArgument(3);
            publish.accept(collected);
            return List.of(collected);
        }).when(collector).collect(any(), any(), any(), any(), any(), any());
        var executor = new PolicyCollectionJobExecutor(leases, attempts, runs, repository, collector, key -> List.of());
        if (completeness) executor.withCompleteness(mock(PolicyCompletenessCoordinator.class));
        return new Fixture(executor, repository);
    }

    @Test void unconfirmedSharpDropKeepsTheStoredSnapshotAndFailsTheJob() {
        var f = fixture(9407, 6262, "UNCHANGED", true);
        assertInstanceOf(JobOutcome.Failed.class, f.executor().execute("job-1", 1, "run-1"));
        verify(f.repository(), never()).checkpoint(anyString(), anyLong(), any(), anyString());
        verify(f.repository()).publish(eq("job-1"), eq(1L), eq(List.of()), anyString(), eq("POLICY_RULE_DROP_UNCONFIRMED packages=1 rules=9407->6262"));
    }

    @Test void missingOrUnknownChangeEvidenceIsNotConfirmation() {
        for (String revision : Arrays.asList(null, "UNKNOWN")) {
            var f = fixture(9407, 6262, revision, true);
            assertInstanceOf(JobOutcome.Failed.class, f.executor().execute("job-1", 1, "run-1"));
            verify(f.repository(), never()).checkpoint(anyString(), anyLong(), any(), anyString());
        }
        // Without the completeness coordinator no change evidence exists at all: fail closed.
        var f = fixture(9407, 6262, "CHANGED", false);
        assertInstanceOf(JobOutcome.Failed.class, f.executor().execute("job-1", 1, "run-1"));
        verify(f.repository(), never()).checkpoint(anyString(), anyLong(), any(), anyString());
    }

    @Test void confirmedDropIsPublishedAfterTheRun() {
        var f = fixture(9407, 6262, "CHANGED", true);
        assertInstanceOf(JobOutcome.Completed.class, f.executor().execute("job-1", 1, "run-1"));
        verify(f.repository()).checkpoint(eq("job-1"), eq(1L), argThat(s -> s.id().equals("p1")), anyString());
        verify(f.repository()).publish(eq("job-1"), eq(1L), eq(List.of()), anyString());
    }

    @Test void ordinaryChangesAreWrittenAtOnceWithoutAskingForEvidence() {
        for (int[] counts : new int[][] { {9407, 9400}, {9407, 9500}, {100, 85}, {30, 12} }) {
            var f = fixture(counts[0], counts[1], "UNCHANGED", true);
            assertInstanceOf(JobOutcome.Completed.class, f.executor().execute("job-1", 1, "run-1"), Arrays.toString(counts));
            verify(f.repository()).checkpoint(eq("job-1"), eq(1L), any(), anyString());
            verify(f.repository(), never()).revisionState(anyString(), anyString());
        }
    }

    @Test void sharpDropNeedsMoreThanTenPercentAndAtLeastTwentyRules() {
        assertTrue(PolicyCollectionJobExecutor.sharpDrop(9407, 6262));
        assertTrue(PolicyCollectionJobExecutor.sharpDrop(200, 179));
        assertFalse(PolicyCollectionJobExecutor.sharpDrop(200, 180));
        assertFalse(PolicyCollectionJobExecutor.sharpDrop(100, 85));
        assertFalse(PolicyCollectionJobExecutor.sharpDrop(9407, 9407));
        assertFalse(PolicyCollectionJobExecutor.sharpDrop(10, 20));
    }
}
