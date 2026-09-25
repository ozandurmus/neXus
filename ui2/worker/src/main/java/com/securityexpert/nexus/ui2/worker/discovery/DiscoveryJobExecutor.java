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

    private static final System.Logger LOGGER = System.getLogger(DiscoveryJobExecutor.class.getName());
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
    static final String PAN_TRUST_RULE_REF = "pan_discovery_trust_default";

    private final JobLeaseRepository leaseRepository;
    private final JobStepAttemptRepository attemptRepository;
    private final DiscoveryRunRepository discoveryRunRepository;
    private final ManagementPlaneEnumeration checkPointEnumeration;
    private final PanoramaEnumeration paloAltoEnumeration;
    /** Radware discovery (2026-09-24): Radware Cyber Controller device list (the V67 REST calls); unset, a radware run fails UNSUPPORTED_VENDOR. */
    private com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor radwareCyberController;
    /** FortiManager discovery (2026-09-25): its ADOMs and their FortiGates over JSON-RPC. */
    private com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor fortiManager;

    public DiscoveryJobExecutor withFortiManager(com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor executor) {
        this.fortiManager = executor;
        return this;
    }

    public DiscoveryJobExecutor withRadwareCyberController(com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor executor) {
        this.radwareCyberController = executor;
        return this;
    }

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
        LOGGER.log(System.Logger.Level.DEBUG, "Executing discovery job");
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
                null, null, fingerprintOf(outcome)); // No response byte/line measurement reaches this executor.
        if (!outcomeWritten) {
            return new JobOutcome.ZombieStopped();
        }

        if (!outcome.succeeded) {
            LOGGER.log(System.Logger.Level.WARNING, "Discovery enumeration failed: {0}", outcome.failureReasonClass);
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                    ACTION_FAILED, outcome.failureReasonClass);
            discoveryRunRepository.markFailed(runId, outcome.failureReasonClass, ACTOR, ACTION_FAILED);
            return new JobOutcome.Failed(outcome.failureReasonClass);
        }

        try {
            discoveryRunRepository.replaceCandidates(runId, outcome.candidates, ACTOR, ACTION_COMPLETED);
            discoveryRunRepository.markFinished(runId, outcome.outcomeSummary, ACTOR, ACTION_COMPLETED);
        } catch (RuntimeException persistFailed) {
            LOGGER.log(System.Logger.Level.WARNING, "Discovery candidate persistence failed: {0}",
                    persistFailed.getClass().getSimpleName());
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                    ACTION_FAILED, "discovery_candidates_write_failed: " + persistFailed.getMessage());
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
            case "radware" -> radwareCyberController == null ? EnumerationOutcome.failed("UNSUPPORTED_VENDOR") : enumerateRadware(run);
            case "fortinet" -> fortiManager == null ? EnumerationOutcome.failed("UNSUPPORTED_VENDOR") : enumerateFortinet(run);
            case "bluecoat" -> radwareCyberController == null ? EnumerationOutcome.failed("UNSUPPORTED_VENDOR") : enumerateBlueCoat(run);
            default -> EnumerationOutcome.failed("UNSUPPORTED_VENDOR");
        };
    }

    private EnumerationOutcome enumerateCheckPoint(DiscoveryRun run) {
        ManagementPlaneEnumerationRequest request = new ManagementPlaneEnumerationRequest(
                run.managementAddress(), DEFAULT_CP_SSH_PORT, run.credentialReferenceId(),
                com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver.scopeRef(
                        run.managementAddress(), DEFAULT_CP_SSH_PORT),
                CP_CHANNEL_OBSERVATION_INTERVAL, Optional.empty());
        ManagementPlaneEnumerationResult result = checkPointEnumeration.run(request);
        return switch (result) {
            case ManagementPlaneEnumerationResult.Refused refused -> EnumerationOutcome.failed("AUTH_FAILED");
            case ManagementPlaneEnumerationResult.Failed failed -> EnumerationOutcome.failed(
                    failed.failureClass().map(Enum::name).orElseGet(() -> failureClass(failed.reason())));
            case ManagementPlaneEnumerationResult.Completed completed -> {
                var rows = CandidateRowAssembler.assemble(completed.candidates());
                var records = CheckPointDiscoveryCandidateMapper.map(run.runId(), rows);
                yield EnumerationOutcome.succeeded(records, CheckPointDiscoveryCandidateMapper.outcomeSummary(rows));
            }
        };
    }

    private EnumerationOutcome enumerateRadware(DiscoveryRun run) {
        String address = run.managementAddress();
        int port = DEFAULT_PAN_HTTPS_PORT;
        int colon = address.lastIndexOf(':');
        if (colon >= 0) {
            try {
                port = Integer.parseInt(address.substring(colon + 1));
                address = address.substring(0, colon);
            } catch (NumberFormatException bareHost) {
                // no port
            }
        }
        var result = radwareCyberController.ccDeviceList(
                new com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target(address, port), run.credentialReferenceId());
        if (result.list().isEmpty()) {
            return EnumerationOutcome.failed(result.failureClass());
        }
        java.util.logging.Logger.getLogger(DiscoveryJobExecutor.class.getName()).info(
                "[RADWARE_DISCOVERY] categorical values " + RadwareDiscoveryCandidateMapper.categoricalValues(result.list().get()));
        var records = RadwareDiscoveryCandidateMapper.map(run.runId(), result.list().get());
        return EnumerationOutcome.succeeded(records, RadwareDiscoveryCandidateMapper.outcomeSummary(records));
    }

    /** Symantec Management Center discovery (2026-09-25): its device list on 8082 unless the address names a port. */
    private EnumerationOutcome enumerateBlueCoat(DiscoveryRun run) {
        String address = run.managementAddress();
        int port = com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorPlan.MC_DEFAULT_PORT;
        int colon = address.lastIndexOf(':');
        if (colon >= 0) {
            try {
                port = Integer.parseInt(address.substring(colon + 1));
                address = address.substring(0, colon);
            } catch (NumberFormatException bareHost) {
                // no port
            }
        }
        var result = radwareCyberController.mcDeviceList(
                new com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target(address, port), run.credentialReferenceId());
        if (result.list().isEmpty()) {
            return EnumerationOutcome.failed(result.failureClass());
        }
        var records = BlueCoatDiscoveryCandidateMapper.map(run.runId(), result.list().get());
        return EnumerationOutcome.succeeded(records, BlueCoatDiscoveryCandidateMapper.outcomeSummary(records));
    }

    private EnumerationOutcome enumerateFortinet(DiscoveryRun run) {
        String address = run.managementAddress();
        int port = DEFAULT_PAN_HTTPS_PORT;
        int colon = address.lastIndexOf(':');
        if (colon >= 0) {
            try {
                port = Integer.parseInt(address.substring(colon + 1));
                address = address.substring(0, colon);
            } catch (NumberFormatException bareHost) {
                // no port
            }
        }
        var result = fortiManager.discover(
                new com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target(address, port), run.credentialReferenceId());
        return switch (result) {
            case com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor.Discovery.Failed f -> EnumerationOutcome.failed(f.reasonClass());
            case com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor.Discovery.Listed listed -> {
                var records = FortinetDiscoveryCandidateMapper.map(run.runId(), listed.devices());
                yield EnumerationOutcome.succeeded(records, FortinetDiscoveryCandidateMapper.outcomeSummary(records));
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
            default -> reason;
        };
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
