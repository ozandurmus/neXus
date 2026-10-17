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
    private PolicyCompletenessCoordinator completeness;
    /** Writes the per-container revision rows and runs the approved, off-by-default change readers (PO approval 2026-10-18). */
    public PolicyCollectionJobExecutor withCompleteness(PolicyCompletenessCoordinator coordinator) { this.completeness = coordinator; return this; }
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

    /**
     * Mode of the automatic job queued after a finished discovery. The scheduled refresh reads Palo Alto fully every
     * window. For Check Point it reads fully in the 00:00 window (hit counts refreshed once a day) and changed-only at
     * 06/12/18; which domains may then be reused is the collector's changed-only setting. Any other discovery queues
     * the throttled CHANGED_ONLY request, as before.
     */
    static PolicyCollectionRepository.Mode automaticMode(DiscoveryRun run, java.time.Instant now) {
        if (!PolicyCollectionRepository.SCHEDULED_REFRESH_ACTOR.equals(run.requestedByActorFingerprint()))
            return PolicyCollectionRepository.Mode.CHANGED_ONLY;
        if (!"check_point".equals(run.vendor())) return PolicyCollectionRepository.Mode.FULL;
        var started = run.startedAt().orElse(now);
        return com.securityexpert.nexus.ui2.platform.JobWindowPolicy.SYSTEM.slotStart(started).getHour() == 0
            ? PolicyCollectionRepository.Mode.FULL : PolicyCollectionRepository.Mode.CHANGED_ONLY;
    }

    /** Discovery has already closed its session; this only queues a separate ledgered job. */
    public void afterDiscovery(DiscoveryRun run) {
        if (!Set.of("check_point", "palo_alto").contains(run.vendor())) return;
        try {
            if ("palo_alto".equals(run.vendor())) PanPolicyGates.requireAll(gates);
            else CpPolicyGates.requireAll(gates);
            for (var source : repository.sources()) if (source.runId().equals(run.runId()))
                repository.enqueue(source.sourceId(), "", true, ACTOR, automaticMode(run, java.time.Instant.now()));
        } catch (RuntimeException unavailable) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unavailable);
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
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(error);
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
        long runStarted = System.nanoTime();
        var observations = new PolicyObservations();
        java.time.Instant jobStart = completeness == null ? java.time.Instant.now() : completeness.now();
        java.util.function.BooleanSupplier lease = () -> leases.heartbeat(jobId, epoch, Duration.ofMinutes(10));
        PolicySnapshotRepository.Stored[] latest = {null};
        java.util.function.Consumer<com.securityexpert.nexus.ui2.policy.PolicySnapshot> publish = snapshot -> {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.checkPublication();
            var stored = stored(snapshot);
            if (!repository.checkpoint(jobId, epoch, stored, ACTOR)) throw PolicyCollectionTrace.failure("LEASE_LOST");
            latest[0] = stored;
        };
        try {
            List<com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure> failures;
            if ("palo_alto".equals(run.get().vendor())) {
                failures = panorama.collect(run.get(), request.get().withLease(epoch), lease, publish, observations);
            } else {
                failures = new java.util.ArrayList<>();
                var snapshots = collector.collect(run.get(), request.get().withLease(epoch), lease, publish, failures::add, observations);
                failures.addAll(snapshots.stream().flatMap(snapshot -> snapshot.failures().stream()).toList());
            }
            if (completeness != null) completeness.afterCollection(run.get(), request.get().withLease(epoch), observations, jobStart, lease, true);
            recordGaps(jobId, epoch, failures);
            recordRun(jobId, epoch, request.get(), run.get().vendor(), runStarted);
            String reason = failures.stream().map(f -> f.layerRef() + ": " + f.reason()).findFirst().orElse("");
            return finish(jobId, epoch, attempt, latest[0], reason, false);
        } catch (Exception incomplete) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(incomplete);
            String reason = PolicyCollectionTrace.reason(incomplete);
            if (reason.endsWith(": LEASE_LOST")) return new JobOutcome.ZombieStopped();
            try { recordRun(jobId, epoch, request.get(), run.get().vendor(), runStarted); }
            catch (RuntimeException unrecorded) {
                com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unrecorded);
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "POLICY_RUN_RECORD_NOT_WRITTEN");
            }
            // Revision rows for what was observed before the failure; no device read after a failed collection.
            if (completeness != null && !reason.endsWith(": CANCELLED")) {
                try { completeness.afterCollection(run.get(), request.get().withLease(epoch), observations, jobStart, lease, false); }
                catch (RuntimeException ignored) { com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(ignored); }
            }
            return finish(jobId, epoch, attempt, latest[0], reason, PolicyCollectionTrace.fatal(incomplete));
        }
    }

    static final int RUN_RECORD_STEP = -10_000;

    /**
     * Durable per-run record for judging the changed-only trial, as ledgered step rows (no schema change):
     * POLICY_RUN_<mode>_<origin>_<domainsReused>_<domainsRead>_<packagesReused>_<packagesRead>_<seconds> with the run's
     * total response bytes in output_bytes, and one POLICY_DOMAIN_<domainRef>_<decision>_<packagesReused>_<packagesRead>
     * row per domain. Only opaque references, fixed words and counts.
     */
    private void recordRun(String jobId, long epoch, PolicyCollectionRepository.Request request, String vendor, long started) {
        if (!"check_point".equals(vendor)) return;
        var trace = PolicyCollectionTrace.current();
        if (trace == null) return;
        var decisions = trace.decisions();
        int domainsReused = 0, domainsRead = 0, packagesReused = 0, packagesRead = 0, index = RUN_RECORD_STEP - 1;
        for (var entry : decisions.entrySet()) {
            var decision = entry.getValue();
            if (decision.decision().equals("REUSED")) domainsReused++; else domainsRead++;
            packagesReused += decision.packagesReused(); packagesRead += decision.packagesRead();
            String domain = entry.getKey().matches("[A-Za-z0-9-]{1,64}") ? entry.getKey() : "UNKNOWN";
            step(jobId, epoch, index--, "POLICY_DOMAIN_" + domain + "_" + decision.decision() + "_" + decision.packagesReused()
                + "_" + decision.packagesRead(), null);
        }
        String origin = request.scheduled() ? "SCHEDULED" : request.automatic() ? "AUTOMATIC" : "MANUAL";
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        String summary = "POLICY_RUN_" + request.mode() + "_" + origin + "_" + domainsReused + "_" + domainsRead
            + "_" + packagesReused + "_" + packagesRead + "_" + seconds;
        step(jobId, epoch, RUN_RECORD_STEP, summary, trace.responseBytes());
        com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", "[CP_POLICY_RUN] mode=" + request.mode()
            + " origin=" + origin + " domainsReused=" + domainsReused + " domainsRead=" + domainsRead + " packagesReused=" + packagesReused
            + " packagesRead=" + packagesRead + " bytes=" + trace.responseBytes() + " seconds=" + seconds);
    }

    private void step(String jobId, long epoch, int index, String kind, Long bytes) {
        String id = attempts.insertPreContact(jobId, epoch, index, kind, "read", 1);
        if (id == null || !attempts.writeOutcome(id, epoch, "MATCHED", null, true, bytes, null, null))
            throw PolicyCollectionTrace.failure("LEASE_LOST");
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
