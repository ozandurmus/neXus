package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.*;

class PolicyCollectionJobExecutorTest {
    @Test void partialFailureRetainsSnapshotAndLedgerContainsOnlySafeClass() {
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
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), isNull(), anyBoolean(), isNull(), isNull(), isNull())).thenReturn(true);
        when(collector.collect(any(), any(), any())).thenThrow(new IllegalStateException("synthetic private detail"));
        var executor = new PolicyCollectionJobExecutor(leases, attempts, runs, repository, collector, key -> List.of());
        assertInstanceOf(JobOutcome.Failed.class, executor.execute("job-1", 1, "run-1"));
        verify(repository, never()).publish(anyString(), anyLong(), anyList(), anyString());
        verify(leases).transitionState(eq("job-1"), eq(1L), eq(JobState.EXECUTING), eq(JobState.FAILED), anyString(),
                eq("policy_collect_failed"), eq("POLICY_COLLECTION_INCOMPLETE"));
        when(repository.eligible("mds-1", "run-1")).thenReturn(false);
        assertInstanceOf(JobOutcome.Rejected.class, executor.execute("job-1", 1, "run-1"));
        verify(collector, times(1)).collect(any(), any(), any());
    }
    @Test void successfulDiscoveryQueuesOnlyMatchingMdsAndMissingGatesBlockIt() {
        var repository = mock(PolicyCollectionRepository.class);
        when(repository.sources()).thenReturn(List.of(new PolicyCollectionRepository.Source("mds-1", "run-1"),
                new PolicyCollectionRepository.Source("mds-2", "another-run")));
        var run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
            DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml"))
            .stream().filter(row -> row.key().equals(key)).toList();
        var executor = new PolicyCollectionJobExecutor(null, null, null, repository, null, gates);
        executor.afterDiscovery(run);
        verify(repository).enqueue(eq("mds-1"), eq(""), eq(true), anyString());
        verify(repository, never()).enqueue(eq("mds-2"), anyString(), anyBoolean(), anyString());
        new PolicyCollectionJobExecutor(null, null, null, repository, null, key -> List.of()).afterDiscovery(run);
        verify(repository, times(1)).enqueue(anyString(), anyString(), anyBoolean(), anyString());
    }
}
