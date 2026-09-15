package com.securityexpert.nexus.ui2.worker.discovery;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.discovery.cp.CandidateRowAssembler;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumeration;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumeration;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationResult;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunState;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.WorkerActor;

/**
 * The {@code discovery_run} job's C2-compliant executor -- sibling of
 * {@code worker.inventory.InventoryJobExecutor}, over the same one-phase
 * shape (no peer follow, one contact producing the whole candidate set in
 * one pass): claim-time run re-check, one pre-contact attempt row, one
 * management-plane contact, one {@link DiscoveryRunRepository#replaceCandidates}
 * plus {@link DiscoveryRunRepository#markFinished} call in the terminal path.
 *
 * <p>Unlike the confirm and inventory executors, this job's target is a
 * {@code discovery_run} row, never a device (14F DR-1) -- the claim-time
 * re-check below consults {@link DiscoveryRunRepository#findRun}, never
 * {@code DeviceEnrollmentReadPort}, and nothing here ever resolves a
 * {@code devices}/{@code endpoints} row.</p>
 *
 * <p>Every step here is {@code CLASS_0_READ} (both discovery contracts are
 * read-only, T-3): no step ever crosses a mutation boundary, so a lease
 * that expires mid-run always finds the one attempt row's {@code
 * mutation_boundary_crossed = false} -- {@code JobReconciler}'s existing
 * {@code findExpiredAllBoundaryNo} branch requeues such a job to {@code
 * REQUESTED} with no new reconciliation logic, exactly as inventory's own
 * crash shape does.</p>
 */
public final class DiscoveryJobExecutor {

    private static final String ACTOR = WorkerActor.RESERVED_ACTOR_FINGERPRINT;
    private static final String ACTION_CLAIM_TO_EXECUTING = "job_discovery_claim_to_executing";
    private static final String ACTION_CLAIM_TIME_RUN_CHECK = "job_discovery_claim_time_run_check";
    private static final String ACTION_RUNNING = "job_discovery_running";
    private static final String ACTION_COMPLETED = "job_discovery_completed";
    private static final String ACTION_FAILED = "job_discovery_failed";

    /** WORKER.md: port 22/443 defaults -- CS-6b's "never hard-coded" governs a *channel* port read from a live sample, not this connection-target default. */
    static final int DEFAULT_CP_SSH_PORT = 22;
    static final int DEFAULT_PAN_HTTPS_PORT = 443;
    /** WORKER.md: "channel observation interval 5 s for Check Point" (contract §7.4 CS-3's sampling interval). */
    static final Duration CP_CHANNEL_OBSERVATION_INTERVAL = Duration.ofSeconds(5);
    /**
     * WORKER.md "trust rule ref from the existing env defaults": both
     * vendor trust resolvers (worker.discovery.cp.EnvironmentTrustRuleResolver,
     * worker.discovery.pan.EnvironmentPanTrustRuleResolver) read an
     * environment variable regardless of the ref string's own value -- these
     * two literals are opaque placeholders, never a lookup key.
     */
    static final String CP_TRUST_RULE_REF = "cp_discovery_trust_default";
    static final String PAN_TRUST_RULE_REF = "pan_discovery_trust_default";

    private final JobLeaseRepository leaseRepository;
    private final JobStepAttemptRepository attemptRepository;
    private final DiscoveryRunRepository discoveryRunRepository;
    private final ManagementPlaneEnumeration checkPointEnumeration;
    private final PanoramaEnumeration paloAltoEnumeration;

    public DiscoveryJobExecutor(JobLeaseRepository leaseRepository, JobStepAttemptRepository attemptRepository,
            DiscoveryRunRepository discoveryRunRepository, ManagementPlaneEnumeration checkPointEnumeration,
            PanoramaEnumeration paloAltoEnumeration) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.discoveryRunRepository = Objects.requireNonNull(discoveryRunRepository, "discoveryRunRepository");
        this.checkPointEnumeration = Objects.requireNonNull(checkPointEnumeration, "checkPointEnumeration");
        this.paloAltoEnumeration = Objects.requireNonNull(paloAltoEnumeration, "paloAltoEnumeration");
    }

    public JobOutcome execute(String jobId, long leaseEpoch, String runId) {
        Optional<DiscoveryRun> run = discoveryRunRepository.findRun(runId);
        if (run.isEmpty() || run.get().state() != DiscoveryRunState.REQUESTED) {
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.REJECTED, ACTOR,
                    ACTION_CLAIM_TIME_RUN_CHECK);
            return new JobOutcome.Rejected("DISCOVERY_RUN_NOT_ELIGIBLE_AT_CLAIM_TIME");
        }

        if (!leaseRepository.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR,
                ACTION_CLAIM_TO_EXECUTING)) {
            return new JobOutcome.ZombieStopped();
        }

        String attemptId = attemptRepository.insertPreContact(jobId, leaseEpoch, 0, "DISCOVERY_ENUMERATE_READ",
                ActionClass.CLASS_0_READ.id(), 1);
        if (attemptId == null) {
            return new JobOutcome.ZombieStopped();
        }

        // Best-effort: a failed markRunning (a concurrent reconciler already moved the run) does not
        // itself stop the job -- the job's own fenced state is what claim-time and terminal writes key on.
        discoveryRunRepository.markRunning(runId, ACTOR, ACTION_RUNNING);

        EnumerationOutcome outcome = enumerate(run.get());

        boolean outcomeWritten = attemptRepository.writeOutcome(attemptId, leaseEpoch,
                outcome.succeeded ? "MATCHED" : "EXPECTATION_UNMET", null, outcome.succeeded,
                measurementBytes(outcome), measurementLines(outcome), fingerprintOf(outcome));
        if (!outcomeWritten) {
            return new JobOutcome.ZombieStopped();
        }

        if (!outcome.succeeded) {
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                    ACTION_FAILED, outcome.failureReasonClass);
            discoveryRunRepository.markFailed(runId, outcome.failureReasonClass, ACTOR, ACTION_FAILED);
            return new JobOutcome.Failed(outcome.failureReasonClass);
        }

        try {
            discoveryRunRepository.replaceCandidates(runId, outcome.candidates, ACTOR, ACTION_COMPLETED);
            discoveryRunRepository.markFinished(runId, outcome.outcomeSummary, ACTOR, ACTION_COMPLETED);
        } catch (RuntimeException persistFailed) {
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                    ACTION_FAILED);
            discoveryRunRepository.markFailed(runId, "CANDIDATE_PERSIST_FAILED", ACTOR, ACTION_FAILED);
            return new JobOutcome.Failed("discovery_candidates_write_failed: " + persistFailed.getMessage());
        }

        leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR,
                ACTION_COMPLETED);
        return new JobOutcome.Completed();
    }

    private EnumerationOutcome enumerate(DiscoveryRun run) {
        return switch (run.vendor()) {
            case "check_point" -> enumerateCheckPoint(run);
            case "palo_alto" -> enumeratePaloAlto(run);
            default -> EnumerationOutcome.failed("UNSUPPORTED_VENDOR");
        };
    }

    private EnumerationOutcome enumerateCheckPoint(DiscoveryRun run) {
        ManagementPlaneEnumerationRequest request = new ManagementPlaneEnumerationRequest(
                run.managementAddress(), DEFAULT_CP_SSH_PORT, run.credentialReferenceId(), CP_TRUST_RULE_REF,
                CP_CHANNEL_OBSERVATION_INTERVAL, Optional.empty());
        ManagementPlaneEnumerationResult result = checkPointEnumeration.run(request);
        return switch (result) {
            case ManagementPlaneEnumerationResult.Refused refused -> EnumerationOutcome.failed("REFUSED");
            case ManagementPlaneEnumerationResult.Failed failed -> EnumerationOutcome.failed(failureClass(failed.reason()));
            case ManagementPlaneEnumerationResult.Completed completed -> {
                var rows = CandidateRowAssembler.assemble(completed.candidates());
                var records = CheckPointDiscoveryCandidateMapper.map(run.runId(), rows);
                yield EnumerationOutcome.succeeded(records, CheckPointDiscoveryCandidateMapper.outcomeSummary(rows));
            }
        };
    }

    private EnumerationOutcome enumeratePaloAlto(DiscoveryRun run) {
        PanoramaEnumerationRequest request = new PanoramaEnumerationRequest(
                run.managementAddress(), DEFAULT_PAN_HTTPS_PORT, run.credentialReferenceId(), PAN_TRUST_RULE_REF);
        PanoramaEnumerationResult result = paloAltoEnumeration.run(request);
        return switch (result) {
            case PanoramaEnumerationResult.Refused refused -> EnumerationOutcome.failed("REFUSED");
            case PanoramaEnumerationResult.Failed failed -> EnumerationOutcome.failed(failureClass(failed.reason()));
            case PanoramaEnumerationResult.Completed completed -> {
                var rows = com.securityexpert.nexus.ui2.discovery.pan.CandidateRowAssembler.assemble(completed.candidates());
                var records = PaloAltoDiscoveryCandidateMapper.map(run.runId(), rows);
                yield EnumerationOutcome.succeeded(records, PaloAltoDiscoveryCandidateMapper.outcomeSummary(rows));
            }
        };
    }

    private static String fingerprintOf(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String failureClass(String reason) {
        return switch (reason) {
            case "unreachable" -> "UNREACHABLE";
            case "refused" -> "REFUSED";
            default -> "UNKNOWN_FAILURE";
        };
    }

    private static long measurementBytes(EnumerationOutcome outcome) {
        return outcome.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static long measurementLines(EnumerationOutcome outcome) {
        return outcome.toString().lines().count();
    }

    private static final class EnumerationOutcome {
        final boolean succeeded;
        final String failureReasonClass;
        final List<DiscoveryCandidateRecord> candidates;
        final Map<String, Integer> outcomeSummary;

        private EnumerationOutcome(boolean succeeded, String failureReasonClass,
                List<DiscoveryCandidateRecord> candidates, Map<String, Integer> outcomeSummary) {
            this.succeeded = succeeded;
            this.failureReasonClass = failureReasonClass;
            this.candidates = candidates;
            this.outcomeSummary = outcomeSummary;
        }

        static EnumerationOutcome failed(String reasonClass) {
            return new EnumerationOutcome(false, reasonClass, List.of(), Map.of());
        }

        static EnumerationOutcome succeeded(List<DiscoveryCandidateRecord> candidates, Map<String, Integer> outcomeSummary) {
            return new EnumerationOutcome(true, null, candidates, outcomeSummary);
        }

        @Override
        public String toString() {
            return "EnumerationOutcome[succeeded=" + succeeded + ", failureReasonClass=" + failureReasonClass
                    + ", candidateCount=" + candidates.size() + "]";
        }
    }
}
