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
    @Test @org.junit.jupiter.api.Timeout(15)
    void concurrentPagesCloseTheirOwnLedgerAttempts() {
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
        when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()))
            .thenAnswer(call -> "attempt-" + call.getArgument(2));
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), isNull(), anyBoolean(), any(), isNull(), isNull())).thenReturn(true);
        when(repository.publish(anyString(), anyLong(), anyList(), anyString())).thenReturn(true);
        doAnswer(call -> {
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            var ready = new java.util.concurrent.CountDownLatch(2);
            try {
                var futures = new ArrayList<java.util.concurrent.Future<Void>>();
                for (int i = 0; i < 2; i++) futures.add(pool.submit(PolicyCollectionTrace.worker(() -> {
                    PolicyCollectionTrace.step("synthetic-page", "opaque-ref");
                    ready.countDown();
                    assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    PolicyCollectionTrace.result(System.nanoTime(), 1, "Completed");
                    return null;
                })));
                for (var future : futures) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            return List.of();
        }).when(collector).collect(any(), any(), any(), any(), any());
        var executor = new PolicyCollectionJobExecutor(leases, attempts, runs, repository, collector, key -> List.of());
        assertInstanceOf(JobOutcome.Completed.class, executor.execute("job-1", 1, "run-1"));
        for (int step = 1; step <= 2; step++) verify(attempts).writeOutcome("attempt-" + step, 1,
            "MATCHED", null, true, 1L, null, null);
    }

    @Test void cancellationFinishesCancelledAndKeepsAlreadyPublishedUnits() {
        var leases = mock(JobLeaseRepository.class);
        var attempts = mock(JobStepAttemptRepository.class);
        var runs = mock(DiscoveryRunRepository.class);
        var repository = mock(PolicyCollectionRepository.class);
        var collector = mock(CheckPointPolicyCollector.class);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
            DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        when(runs.findRun("run-1")).thenReturn(Optional.of(run));
        when(repository.request("job-1")).thenReturn(Optional.of(new PolicyCollectionRepository.Request("mds-1", "", false)));
        when(repository.eligible("mds-1", "run-1")).thenReturn(true);
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), anyString())).thenReturn(true);
        when(leases.cancellationRequested("job-1", 1)).thenAnswer(call -> cancelled.get());
        when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt())).thenReturn("attempt-1");
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), isNull(), anyBoolean(), isNull(), isNull(), isNull())).thenReturn(true);
        when(repository.checkpoint(anyString(), anyLong(), any(), anyString())).thenReturn(true);
        var metadata = new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata("policy-1", "mds-1", "MGR-BRAVO-01", "CP",
            "domain-1", "DOM-TANGO-01", "OBJ-POLICY-01", "2026-10-02T00:00:00Z", "", List.of());
        var snapshot = new com.securityexpert.nexus.ui2.policy.PolicySnapshot(metadata, List.of(), Map.of());
        doAnswer(call -> {
            java.util.function.Consumer<com.securityexpert.nexus.ui2.policy.PolicySnapshot> publish = call.getArgument(3);
            publish.accept(snapshot);
            cancelled.set(true);
            throw PolicyCollectionTrace.failure("CANCELLED");
        }).when(collector).collect(any(), any(), any(), any(), any());
        var executor = new PolicyCollectionJobExecutor(leases, attempts, runs, repository, collector, key -> List.of());
        assertInstanceOf(JobOutcome.Cancelled.class, executor.execute("job-1", 1, "run-1"));
        verify(repository).checkpoint(eq("job-1"), eq(1L), any(), anyString());
        verify(leases).transitionState("job-1", 1, JobState.EXECUTING, JobState.CANCELLED, "system:worker", "job_cancel_finish", "CANCELLED");
        verify(repository, never()).publish(anyString(), anyLong(), anyList(), anyString());
        verify(repository, never()).publish(anyString(), anyLong(), anyList(), anyString(), anyString());
    }

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
        when(repository.publish(anyString(), anyLong(), anyList(), anyString(), anyString())).thenReturn(true);
        when(collector.collect(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("synthetic private detail"));
        var executor = new PolicyCollectionJobExecutor(leases, attempts, runs, repository, collector, key -> List.of());
        assertInstanceOf(JobOutcome.Failed.class, executor.execute("job-1", 1, "run-1"));
        verify(repository, never()).publish(anyString(), anyLong(), anyList(), anyString());
        verify(repository).publish(eq("job-1"), eq(1L), eq(List.of()), anyString(),
                eq("preflight target=run-1: FAILED_IllegalStateException"));
        when(repository.eligible("mds-1", "run-1")).thenReturn(false);
        assertInstanceOf(JobOutcome.Rejected.class, executor.execute("job-1", 1, "run-1"));
        verify(collector, times(1)).collect(any(), any(), any(), any(), any());
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
    }    @Test void panoramaFailureDoesNotPublishAndDiscoveryUsesOnlyApprovedGates() {
        var leases = mock(JobLeaseRepository.class);
        var attempts = mock(JobStepAttemptRepository.class);
        var runs = mock(DiscoveryRunRepository.class);
        var repository = mock(PolicyCollectionRepository.class);
        var collector = mock(PanoramaPolicyCollector.class);
        var run = new DiscoveryRun("run-pan", "palo_alto", "192.0.2.10", "synthetic-ref", "synthetic-actor",
            DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        when(runs.findRun("run-pan")).thenReturn(Optional.of(run));
        when(repository.request("job-pan")).thenReturn(Optional.of(new PolicyCollectionRepository.Request("pan-1", "", false)));
        when(repository.eligible("pan-1", "run-pan")).thenReturn(true);
        when(repository.sources()).thenReturn(List.of(new PolicyCollectionRepository.Source("pan-1", "run-pan", "palo_alto")));
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt())).thenReturn("attempt-pan");
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), isNull(), anyBoolean(), isNull(), isNull(), isNull())).thenReturn(true);
        when(repository.publish(anyString(), anyLong(), anyList(), anyString(), anyString())).thenReturn(true);
        when(collector.collect(any(), any(), any(), any())).thenThrow(new IllegalStateException("synthetic incomplete page"));
        GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml"))
            .stream().filter(row -> row.key().equals(key)).toList();
        var executor = new PolicyCollectionJobExecutor(leases, attempts, runs, repository, null, gates).withPanorama(collector);
        executor.afterDiscovery(run);
        verify(repository).enqueue(eq("pan-1"), eq(""), eq(true), anyString());
        assertInstanceOf(JobOutcome.Failed.class, executor.execute("job-pan", 1, "run-pan"));
        verify(repository, never()).publish(anyString(), anyLong(), anyList(), anyString());
        verify(attempts).insertPreContact(eq("job-pan"), eq(1L), eq(0), eq("PAN_POLICY_READ"), eq("read"), eq(1));
    }
    @Test void bothVendorsShareIncrementalWarningAndFatalOutcomes() {
        for (String vendor : List.of("check_point", "palo_alto")) {
            var leases = mock(JobLeaseRepository.class);
            var attempts = mock(JobStepAttemptRepository.class);
            var runs = mock(DiscoveryRunRepository.class);
            var repository = mock(PolicyCollectionRepository.class);
            var cp = mock(CheckPointPolicyCollector.class);
            var pan = mock(PanoramaPolicyCollector.class);
            var run = new DiscoveryRun("run-1", vendor, "192.0.2.10", "synthetic-ref", "synthetic-actor",
                DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            when(runs.findRun("run-1")).thenReturn(Optional.of(run));
            when(repository.request("job-1")).thenReturn(Optional.of(new PolicyCollectionRepository.Request("manager-1", "", false)));
            when(repository.eligible("manager-1", "run-1")).thenReturn(true);
            when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
            when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt())).thenReturn("attempt-1");
            when(attempts.writeOutcome(anyString(), anyLong(), anyString(), isNull(), anyBoolean(), isNull(), isNull(), isNull())).thenReturn(true);
            when(repository.checkpoint(anyString(), anyLong(), any(), anyString())).thenReturn(true);
            when(repository.publishWithWarnings(anyString(), anyLong(), anyList(), anyString(), anyString())).thenReturn(true);
            when(repository.publish(anyString(), anyLong(), anyList(), anyString())).thenReturn(true);
            when(repository.publish(anyString(), anyLong(), anyList(), anyString(), anyString())).thenReturn(true);
            String[] failure = {"XML_PARSE_OR_SIZE_FAILED"};
            org.mockito.stubbing.Answer<Object> collect = call -> {
                var metadata = new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata("policy-1", "manager-1", "MGR-BRAVO-01",
                    vendor.equals("check_point") ? "CP" : "PAN", "unit-1", "OBJ-UNIT-01", "OBJ-POLICY-01", "2026-10-02T03:39:00Z", "", List.of());
                var failures = failure[0].isEmpty() ? List.<com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure>of()
                    : List.of(new com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure("unit-2", "policy: " + failure[0]));
                if (vendor.equals("check_point")) {
                    java.util.function.Consumer<com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure> domainFailure = call.getArgument(4);
                    failures.forEach(domainFailure);
                }
                var snapshot = new com.securityexpert.nexus.ui2.policy.PolicySnapshot(metadata, List.of(), Map.of(),
                    vendor.equals("check_point") ? List.of() : failures);
                java.util.function.Consumer<com.securityexpert.nexus.ui2.policy.PolicySnapshot> publish = call.getArgument(3);
                publish.accept(snapshot);
                if (PolicyCollectionTrace.fatal(new PolicyCollectionTrace.Failure("policy: " + failure[0])))
                    throw PolicyCollectionTrace.failure(failure[0]);
                return vendor.equals("check_point") ? List.of(snapshot) : failures;
            };
            doAnswer(collect).when(cp).collect(any(), any(), any(), any(), any());
            doAnswer(collect).when(pan).collect(any(), any(), any(), any());
            var executor = new PolicyCollectionJobExecutor(leases, attempts, runs, repository, cp, key -> List.of()).withPanorama(pan);
            assertInstanceOf(JobOutcome.Completed.class, executor.execute("job-1", 1, "run-1"));
            verify(attempts).insertPreContact(eq("job-1"), eq(1L), eq(-1),
                eq("POLICY_PROGRESS_GAP_XML_PARSE_OR_SIZE_FAILED"), eq("read"), eq(1));
            var order = inOrder(repository);
            order.verify(repository).request("job-1");
            order.verify(repository).eligible("manager-1", "run-1");
            order.verify(repository).checkpoint(eq("job-1"), eq(1L), any(), anyString());
            order.verify(repository).publishWithWarnings(eq("job-1"), eq(1L), anyList(), anyString(), startsWith("PARTIAL_SNAPSHOT "));
            failure[0] = "";
            assertInstanceOf(JobOutcome.Completed.class, executor.execute("job-1", 1, "run-1"));
            verify(repository).publish(eq("job-1"), eq(1L), eq(List.of()), anyString());
            for (String fatal : List.of("JOB_DEADLINE", "POLICY_GATE_UNAVAILABLE", "INTERRUPTED", "AUTHENTICATION_FAILURE")) {
                failure[0] = fatal;
                var outcome = assertInstanceOf(JobOutcome.Failed.class, executor.execute("job-1", 1, "run-1"));
                assertTrue(outcome.terminalReason().startsWith("PARTIAL_SNAPSHOT "));
                assertTrue(outcome.terminalReason().endsWith(": " + fatal));
                verify(repository).publish(eq("job-1"), eq(1L), eq(List.of()), anyString(), endsWith(": " + fatal));
            }
            failure[0] = "LEASE_LOST";
            assertInstanceOf(JobOutcome.ZombieStopped.class, executor.execute("job-1", 1, "run-1"));
            failure[0] = "";
            when(repository.checkpoint(anyString(), anyLong(), any(), anyString())).thenReturn(false);
            assertInstanceOf(JobOutcome.ZombieStopped.class, executor.execute("job-1", 1, "run-1"));
        }
    }
}
