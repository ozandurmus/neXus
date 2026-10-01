package com.securityexpert.nexus.ui2.worker.policy;

import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.*;
import com.securityexpert.nexus.ui2.platform.WorkerActor;

public final class PolicyCollectionJobExecutor {
    private static final String ACTOR = WorkerActor.RESERVED_ACTOR_FINGERPRINT;
    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DiscoveryRunRepository runs;
    private final PolicyCollectionRepository repository;
    private final CheckPointPolicyCollector collector;
    private final GateRegistryPort gates;
    private final ObjectMapper json = new ObjectMapper();

    public PolicyCollectionJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts,
            DiscoveryRunRepository runs, PolicyCollectionRepository repository, CheckPointPolicyCollector collector, GateRegistryPort gates) {
        this.leases = leases; this.attempts = attempts; this.runs = runs; this.repository = repository;
        this.collector = collector; this.gates = gates;
    }

    /** Discovery has already closed its session; this only queues a separate ledgered job. */
    public void afterDiscovery(DiscoveryRun run) {
        if (!"check_point".equals(run.vendor())) return;
        try {
            CpPolicyGates.requireAll(gates);
            for (var source : repository.sources()) if (source.runId().equals(run.runId()))
                repository.enqueue(source.sourceId(), "", true, ACTOR);
        } catch (RuntimeException unavailable) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "POLICY_AUTOMATIC_ADMISSION_FAILED");
        }
    }

    public JobOutcome execute(String jobId, long epoch, String runId) {
        var request = repository.request(jobId);
        var run = runs.findRun(runId);
        if (request.isEmpty() || run.isEmpty() || run.get().state() != DiscoveryRunState.FINISHED
                || !"check_point".equals(run.get().vendor())
                || !repository.eligible(request.get().sourceId(), runId)) {
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "policy_collect_rejected");
            return new JobOutcome.Rejected("POLICY_SOURCE_NOT_ELIGIBLE");
        }
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "policy_collect_running"))
            return new JobOutcome.ZombieStopped();
        String attempt = attempts.insertPreContact(jobId, epoch, 0, "CP_POLICY_READ", "read", 1);
        if (attempt == null) return new JobOutcome.ZombieStopped();
        try {
            var snapshots = collector.collect(run.get(), request.get(), () -> leases.heartbeat(jobId, epoch, Duration.ofMinutes(10)));
            List<PolicySnapshotRepository.Stored> stored = new ArrayList<>();
            for (var snapshot : snapshots) stored.add(new PolicySnapshotRepository.Stored(snapshot.metadata().id(),
                    snapshot.metadata().collectedAt(), json.writeValueAsString(snapshot.metadata()), json.writeValueAsString(snapshot)));
            if (!attempts.writeOutcome(attempt, epoch, "MATCHED", null, true, null, null, null)) return new JobOutcome.ZombieStopped();
            if (!repository.publish(jobId, epoch, stored, ACTOR)) return new JobOutcome.ZombieStopped();
            return new JobOutcome.Completed();
        } catch (Exception incomplete) {
            if (!attempts.writeOutcome(attempt, epoch, "EXPECTATION_UNMET", null, false, null, null, null)) return new JobOutcome.ZombieStopped();
            leases.transitionState(jobId, epoch, JobState.EXECUTING, JobState.FAILED, ACTOR, "policy_collect_failed", "POLICY_COLLECTION_INCOMPLETE");
            return new JobOutcome.Failed("POLICY_COLLECTION_INCOMPLETE");
        }
    }
}
