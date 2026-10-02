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
import com.securityexpert.nexus.ui2.jobs.policy.PanPolicyGates;
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
    private PanoramaPolicyCollector panorama;
    public PolicyCollectionJobExecutor withPanorama(PanoramaPolicyCollector collector) { this.panorama = collector; return this; }
    private com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore transcriptStore;
    private com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao transcriptJobs;
    public PolicyCollectionJobExecutor withTranscript(com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore store,
            com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao jobs) {
        transcriptStore = store; transcriptJobs = jobs; return this;
    }
    private final ObjectMapper json = new ObjectMapper();

    public PolicyCollectionJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts,
            DiscoveryRunRepository runs, PolicyCollectionRepository repository, CheckPointPolicyCollector collector, GateRegistryPort gates) {
        this.leases = leases; this.attempts = attempts; this.runs = runs; this.repository = repository;
        this.collector = collector; this.gates = gates;
    }

    /** Discovery has already closed its session; this only queues a separate ledgered job. */
    public void afterDiscovery(DiscoveryRun run) {
        if (!Set.of("check_point", "palo_alto").contains(run.vendor())) return;
        try {
            if ("palo_alto".equals(run.vendor())) PanPolicyGates.requireAll(gates);
            else CpPolicyGates.requireAll(gates);
            for (var source : repository.sources()) if (source.runId().equals(run.runId()))
                repository.enqueue(source.sourceId(), "", true, ACTOR);
        } catch (RuntimeException unavailable) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "POLICY_AUTOMATIC_ADMISSION_FAILED");
        }
    }

    public JobOutcome execute(String jobId, long epoch, String runId) {
        var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
        String[] pending = {null};
        try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript);
             var trace = new PolicyCollectionTrace(runId, (step, total) -> {
                 if (pending[0] != null) {
                     if (!attempts.writeOutcome(pending[0], epoch, "MATCHED", null, true, null, null, null))
                         throw PolicyCollectionTrace.failure("LEASE_LOST");
                     pending[0] = null;
                 }
                 String id = attempts.insertPreContact(jobId, epoch, step, "POLICY_PROGRESS_" + total, "read", 1);
                 if (id == null) throw PolicyCollectionTrace.failure("LEASE_LOST");
                 pending[0] = id;
             }, measurement -> {
                 if (pending[0] == null) return;
                 boolean completed = measurement.outcome().equals("Completed");
                 if (!attempts.writeOutcome(pending[0], epoch, completed ? "MATCHED" : "EXPECTATION_UNMET", null,
                         completed, measurement.bytes() < 0 ? null : measurement.bytes(), null, null))
                     throw PolicyCollectionTrace.failure("LEASE_LOST");
                 pending[0] = null;
             })) {
            JobOutcome outcome = executeScoped(jobId, epoch, runId);
            if (pending[0] != null) attempts.writeOutcome(pending[0], epoch,
                outcome instanceof JobOutcome.Completed ? "MATCHED" : "EXPECTATION_UNMET", null,
                outcome instanceof JobOutcome.Completed, null, null, null);
            com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "verdict",
                outcome instanceof JobOutcome.Failed failed ? failed.terminalReason() : outcome instanceof JobOutcome.Rejected rejected ? rejected.reason() : outcome.getClass().getSimpleName());
            return outcome;
        } finally {
            if (transcriptStore != null && transcriptJobs != null) {
                try (var handle = transcriptStore.open(runId, jobId, "transcript", true)) {
                    transcript.writeTo(handle.sink());
                    var stored = handle.finish();
                    if (!transcriptJobs.writeBackupTranscript(jobId, epoch, stored.ref().value(), stored.wrappedDataKey()))
                        throw new IllegalStateException("TRANSCRIPT_REFERENCE_NOT_RECORDED");
                } catch (Exception error) {
                    System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "POLICY_TRANSCRIPT_NOT_STORED: " + error.getClass().getSimpleName());
                }
            }
        }
    }

    private JobOutcome executeScoped(String jobId, long epoch, String runId) {
        var request = repository.request(jobId);
        var run = runs.findRun(runId);
        if (request.isEmpty() || run.isEmpty() || run.get().state() != DiscoveryRunState.FINISHED
                || !Set.of("check_point", "palo_alto").contains(run.get().vendor())
                || ("palo_alto".equals(run.get().vendor()) && panorama == null)
                || !repository.eligible(request.get().sourceId(), runId)) {
            String reason = "preflight target=" + (request.isPresent() ? request.get().sourceId() : runId) + ": "
                + (request.isEmpty() ? "POLICY_REQUEST_NOT_FOUND" : run.isEmpty() ? "DISCOVERY_RUN_NOT_FOUND"
                    : "palo_alto".equals(run.get().vendor()) && panorama == null ? "PANORAMA_COLLECTOR_NOT_WIRED" : "POLICY_SOURCE_NOT_ELIGIBLE");
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "policy_collect_rejected", reason);
            return new JobOutcome.Rejected(reason);
        }
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "policy_collect_running"))
            return new JobOutcome.ZombieStopped();
        String attempt = attempts.insertPreContact(jobId, epoch, 0, "palo_alto".equals(run.get().vendor()) ? "PAN_POLICY_READ" : "CP_POLICY_READ", "read", 1);
        if (attempt == null) return new JobOutcome.ZombieStopped();
        try {
            var snapshots = "palo_alto".equals(run.get().vendor())
                ? panorama.collect(run.get(), request.get(), () -> leases.heartbeat(jobId, epoch, Duration.ofMinutes(10)))
                : collector.collect(run.get(), request.get(), () -> leases.heartbeat(jobId, epoch, Duration.ofMinutes(10)));
            List<PolicySnapshotRepository.Stored> stored = new ArrayList<>();
            for (var snapshot : snapshots) stored.add(new PolicySnapshotRepository.Stored(snapshot.metadata().id(),
                    snapshot.metadata().collectedAt(), json.writeValueAsString(snapshot.metadata()), json.writeValueAsString(snapshot)));
            String reason = snapshots.stream().flatMap(s -> s.failures().stream()).map(f -> f.layerRef() + ": " + f.reason())
                .findFirst().orElse("");
            if (!attempts.writeOutcome(attempt, epoch, reason.isEmpty() ? "MATCHED" : "EXPECTATION_UNMET", null, reason.isEmpty(), null, null, null))
                return new JobOutcome.ZombieStopped();
            if (!reason.isEmpty()) {
                if ("palo_alto".equals(run.get().vendor())) {
                    if (!repository.publishWithWarnings(jobId, epoch, stored, ACTOR, "PARTIAL_SNAPSHOT " + reason)) return new JobOutcome.ZombieStopped();
                    com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", "PARTIAL_SNAPSHOT " + reason);
                    return new JobOutcome.Completed();
                }
                if (!repository.publish(jobId, epoch, stored, ACTOR, "PARTIAL_SNAPSHOT " + reason)) return new JobOutcome.ZombieStopped();
                return new JobOutcome.Failed("PARTIAL_SNAPSHOT " + reason);
            }
            if (!repository.publish(jobId, epoch, stored, ACTOR)) return new JobOutcome.ZombieStopped();
            return new JobOutcome.Completed();
        } catch (Exception incomplete) {
            if (!attempts.writeOutcome(attempt, epoch, "EXPECTATION_UNMET", null, false, null, null, null)) return new JobOutcome.ZombieStopped();
            String reason = PolicyCollectionTrace.reason(incomplete);
            leases.transitionState(jobId, epoch, JobState.EXECUTING, JobState.FAILED, ACTOR, "policy_collect_failed", reason);
            return new JobOutcome.Failed(reason);
        }
    }
}
