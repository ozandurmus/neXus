package com.securityexpert.nexus.ui2.worker.packetanalysis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;
import com.securityexpert.nexus.ui2.platform.WorkerActor;

/**
 * Runs one claimed {@code packet_analysis_run} job: the case's captures go to the {@link PacketAnalysisEngine}, the
 * full report is written to the case's run directory on the analysis volume, and only the engine's masked summary
 * is stored with the run. Every job and run write is fenced on the lease epoch, so a worker that lost its lease
 * stops at its next write. No device is contacted; the one step attempt never crosses a mutation boundary, so the
 * reconciler requeues an expired run rather than marking it OUTCOME_UNKNOWN.
 */
public final class PacketAnalysisJobExecutor {

    static final String ACTOR = WorkerActor.RESERVED_ACTOR_FINGERPRINT;
    static final String STEP_KIND = "PACKET_ANALYSIS";

    private static final System.Logger LOG = System.getLogger(PacketAnalysisJobExecutor.class.getName());

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final PacketAnalysisRepository repository;
    private final PacketAnalysisEngine engine;
    private final Path root;
    private final ObjectMapper json = new ObjectMapper();

    public PacketAnalysisJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts,
            PacketAnalysisRepository repository, PacketAnalysisEngine engine, Path root) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /** @return the terminal job state this call wrote, or {@code null} when the lease was lost (zombie stop). */
    public JobState execute(String jobId, long epoch, String caseId) {
        Optional<PacketAnalysisRepository.RunRow> run = repository.runForJob(jobId);
        Optional<PacketAnalysisRepository.CaseRow> found = PacketAnalysisPaths.isId(caseId)
                ? repository.caseForWorker(caseId) : Optional.empty();
        if (run.isEmpty() || found.isEmpty() || !caseId.equals(run.get().caseId())) {
            return leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR,
                    "packet_analysis_rejected", "CASE_NOT_FOUND") ? JobState.REJECTED : null;
        }
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "packet_analysis_running"))
            return null;
        String attempt = attempts.insertPreContact(jobId, epoch, 0, STEP_KIND, "read", 1);
        if (attempt == null || !repository.markRunning(jobId, epoch, ACTOR)) return null;
        try {
            List<PacketAnalysisEngine.Capture> captures = new ArrayList<>();
            for (var capture : repository.captures(caseId)) {
                Path file = PacketAnalysisPaths.captureFile(root, caseId, capture.storageKey());
                if (!Files.isRegularFile(file)) return fail(jobId, epoch, attempt, "CAPTURE_MISSING");
                captures.add(new PacketAnalysisEngine.Capture(capture.captureId(), capture.displayName(), capture.format(),
                        capture.sizeBytes(), capture.sha256(), file));
            }
            if (captures.isEmpty()) return fail(jobId, epoch, attempt, "NO_CAPTURES");
            var result = engine.analyze(new PacketAnalysisEngine.Request(caseId, jobId,
                    PacketAnalysisPaths.caseDir(root, caseId), List.copyOf(captures),
                    new PacketAnalysisEngine.Options(found.get().payloadZeroing())));
            Path report = PacketAnalysisPaths.reportFile(root, caseId, jobId);
            Files.createDirectories(report.getParent());
            Path partial = report.resolveSibling(".report.json.partial");
            byte[] bytes = result.reportJson().getBytes(StandardCharsets.UTF_8);
            Files.write(partial, bytes);
            Files.move(partial, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (leases.cancellationRequested(jobId, epoch)) {
                attempts.writeOutcome(attempt, epoch, "EXPECTATION_UNMET", "CANCELLED", false, null, null, null);
                repository.fail(jobId, epoch, "CANCELLED", ACTOR);
                return leases.transitionState(jobId, epoch, JobState.EXECUTING, JobState.CANCELLED, ACTOR,
                        "job_cancel_finish", "CANCELLED") ? JobState.CANCELLED : null;
            }
            if (!repository.complete(jobId, epoch, result.engineVersion(), json.writeValueAsString(result.maskedSummary()), ACTOR))
                return null;
            if (!attempts.writeOutcome(attempt, epoch, "MATCHED", null, true, (long) bytes.length, null, null)) return null;
            return leases.transitionState(jobId, epoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR,
                    "packet_analysis_completed", "COMPLETED") ? JobState.COMPLETED : null;
        } catch (Exception failure) {
            // The engine's message may quote capture content or paths: record the class of failure only.
            LOG.log(System.Logger.Level.WARNING, "[PACKET_ANALYSIS] job {0} failed: {1}", jobId, failure.getClass().getSimpleName());
            return fail(jobId, epoch, attempt, "ENGINE_FAILED");
        }
    }

    private JobState fail(String jobId, long epoch, String attempt, String reason) {
        attempts.writeOutcome(attempt, epoch, "EXPECTATION_UNMET", reason, false, null, null, null);
        if (!repository.fail(jobId, epoch, reason, ACTOR)) return null;
        return leases.transitionState(jobId, epoch, JobState.EXECUTING, JobState.FAILED, ACTOR, "packet_analysis_failed", reason)
                ? JobState.FAILED : null;
    }
}
