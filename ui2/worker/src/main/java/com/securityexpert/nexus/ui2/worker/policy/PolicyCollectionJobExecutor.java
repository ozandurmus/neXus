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
                repository.enqueue(source.sourceId(), "", true, ACTOR,
                    "system:discovery-refresh".equals(run.requestedByActorFingerprint())
                        ? PolicyCollectionRepository.Mode.FULL : PolicyCollectionRepository.Mode.CHANGED_ONLY);
        } catch (RuntimeException unavailable) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "POLICY_AUTOMATIC_ADMISSION_FAILED");
        }
    }

    public JobOutcome execute(String jobId, long epoch, String runId) {
        var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
        var pending = new java.util.concurrent.ConcurrentHashMap<Long, String>();
        try (var cancellation = new com.securityexpert.nexus.ui2.worker.JobCancellationScope(
                () -> leases.cancellationRequested(jobId, epoch));
             var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript);
             var trace = new PolicyCollectionTrace(runId, (step, total) -> {
                 if (pending.containsKey(Thread.currentThread().getId())) {
                     if (!attempts.writeOutcome(pending.get(Thread.currentThread().getId()), epoch, "MATCHED", null, true, null, null, null))
                         throw PolicyCollectionTrace.failure("LEASE_LOST");
                     pending.remove(Thread.currentThread().getId());
                 }
                 String id = attempts.insertPreContact(jobId, epoch, step, "POLICY_PROGRESS_" + total, "read", 1);
                 if (id == null) throw PolicyCollectionTrace.failure("LEASE_LOST");
                 pending.put(Thread.currentThread().getId(), id);
             }, measurement -> {
                 if (!pending.containsKey(Thread.currentThread().getId())) return;
                 boolean completed = measurement.outcome().equals("Completed");
                 if (!attempts.writeOutcome(pending.get(Thread.currentThread().getId()), epoch, completed ? "MATCHED" : "EXPECTATION_UNMET", null,
                         completed, measurement.bytes() < 0 ? null : measurement.bytes(), null, null))
                     throw PolicyCollectionTrace.failure("LEASE_LOST");
                 pending.remove(Thread.currentThread().getId());
             }, progress -> {
                 String id = attempts.insertPreContact(jobId, epoch, progress.step(),
                     "POLICY_LAYER_" + progress.layer() + "_" + progress.layers() + "_" + progress.rules()
                         + "_" + progress.packagesDone() + "_" + progress.packagesTotal()
                         + "_" + progress.readTimeoutSeconds() + "_" + progress.lastActivity()
                         + "_" + progress.domainsDone() + "_" + progress.domainsTotal(), "read", 1);
                 if (id == null || !attempts.writeOutcome(id, epoch, "MATCHED", null, true, null, null, null))
                     throw PolicyCollectionTrace.failure("LEASE_LOST");
             })) {
            JobOutcome outcome = executeScoped(jobId, epoch, runId);
            for (String id : pending.values()) attempts.writeOutcome(id, epoch,
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
        PolicySnapshotRepository.Stored[] latest = {null};
        java.util.function.Consumer<com.securityexpert.nexus.ui2.policy.PolicySnapshot> publish = snapshot -> {
            var stored = stored(snapshot);
            if (!repository.checkpoint(jobId, epoch, stored, ACTOR)) throw PolicyCollectionTrace.failure("LEASE_LOST");
            latest[0] = stored;
        };
        try {
            List<com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure> failures;
            if ("palo_alto".equals(run.get().vendor())) {
                failures = panorama.collect(run.get(), request.get(),
                    () -> leases.heartbeat(jobId, epoch, Duration.ofMinutes(10)), publish);
            } else {
                failures = new java.util.ArrayList<>();
                var snapshots = collector.collect(run.get(), request.get().withLease(epoch),
                    () -> leases.heartbeat(jobId, epoch, Duration.ofMinutes(10)), publish, failures::add);
                failures.addAll(snapshots.stream().flatMap(snapshot -> snapshot.failures().stream()).toList());
            }
            recordGaps(jobId, epoch, failures);
            String reason = failures.stream().map(f -> f.layerRef() + ": " + f.reason()).findFirst().orElse("");
            return finish(jobId, epoch, attempt, latest[0], reason, false);
        } catch (Exception incomplete) {
            String reason = PolicyCollectionTrace.reason(incomplete);
            if (reason.endsWith(": LEASE_LOST")) return new JobOutcome.ZombieStopped();
            return finish(jobId, epoch, attempt, latest[0], reason, PolicyCollectionTrace.fatal(incomplete));
        }
    }

    private void recordGaps(String jobId, long epoch,
            List<com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure> failures) {
        var units = new LinkedHashMap<String, String>();
        failures.forEach(f -> units.putIfAbsent(f.layerRef(), f.reason()));
        int index = -1;
        for (String reason : units.values()) {
            String code = reason.contains(": ") ? reason.substring(reason.lastIndexOf(": ") + 2) : reason;
            if (!code.matches("[A-Za-z][A-Za-z0-9_]{0,79}")) code = "COLLECTION_FAILED";
            String id = attempts.insertPreContact(jobId, epoch, index--, "POLICY_PROGRESS_GAP_" + code, "read", 1);
            if (id == null || !attempts.writeOutcome(id, epoch, "MATCHED", null, true, null, null, null))
                throw PolicyCollectionTrace.failure("LEASE_LOST");
        }
    }

    private JobOutcome finish(String jobId, long epoch, String attempt, PolicySnapshotRepository.Stored latest,
            String reason, boolean fatal) {
        if (!attempts.writeOutcome(attempt, epoch, reason.isEmpty() ? "MATCHED" : "EXPECTATION_UNMET", null,
                reason.isEmpty(), null, null, null)) return new JobOutcome.ZombieStopped();
        if (leases.cancellationRequested(jobId, epoch)) {
            if (!leases.transitionState(jobId, epoch, JobState.EXECUTING, JobState.CANCELLED, ACTOR, "job_cancel_finish", "CANCELLED"))
                return new JobOutcome.ZombieStopped();
            return new JobOutcome.Cancelled();
        }
        if (!reason.isEmpty() && latest != null) reason = "PARTIAL_SNAPSHOT " + reason;
        if (!reason.isEmpty() && (fatal || latest == null)) {
            if (!repository.publish(jobId, epoch, List.of(), ACTOR, reason)) return new JobOutcome.ZombieStopped();
            return new JobOutcome.Failed(reason);
        }
        // All earlier units are already durable; retain only the latest snapshot in memory.
        if (!reason.isEmpty()) {
            if (!repository.publishWithWarnings(jobId, epoch, List.of(latest), ACTOR, reason)) return new JobOutcome.ZombieStopped();
            com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", reason);
        } else if (!repository.publish(jobId, epoch, List.of(), ACTOR)) return new JobOutcome.ZombieStopped();
        return new JobOutcome.Completed();
    }

    private PolicySnapshotRepository.Stored stored(com.securityexpert.nexus.ui2.policy.PolicySnapshot snapshot) {
        try {
            return new PolicySnapshotRepository.Stored(snapshot.metadata().id(), snapshot.metadata().collectedAt(),
                json.writeValueAsString(snapshot.metadata()), json.writeValueAsString(snapshot));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw PolicyCollectionTrace.failure("INVALID_OR_INCOMPLETE_RESPONSE");
        }
    }
}
