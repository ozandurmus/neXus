package com.securityexpert.nexus.ui2.persistence.discovery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code discovery_run}/{@code discovery_candidate} persistence (14F DR-1,
 * DR-2, DR-3). {@link #createRun} and {@link #setJobId} are two separate
 * writes because the run must exist before {@code JobAdmissionService}
 * admits a job against it (the job-engine discovery read port looks the run
 * up by id) -- {@code DiscoveryRunService.start} calls both, plus the
 * admission itself, inside one transaction (mirrors {@code
 * DeviceAddSingleService}'s register-then-admit pattern).
 */
public interface DiscoveryRunRepository {

    /** Creates the row in state {@code REQUESTED} with no job linked yet. */
    void createRun(String runId, String vendor, String managementAddress, String credentialReferenceId,
            String requestedByActorFingerprint, String actorFingerprint, String actionId);

    boolean setJobId(String runId, String jobId, String actorFingerprint, String actionId);

    Optional<DiscoveryRun> findRun(String runId);

    /** Fenced: only from {@code REQUESTED}. */
    boolean markRunning(String runId, String actorFingerprint, String actionId);

    /** Fenced: only from {@code RUNNING}. {@code outcomeSummary} is counts only (PR-3: never a name/address). */
    boolean markFinished(String runId, Map<String, Integer> outcomeSummary, String actorFingerprint, String actionId);

    /** Fenced: only from {@code REQUESTED} or {@code RUNNING}. {@code reasonClass} is a class, never an address or name. */
    boolean markFailed(String runId, String reasonClass, String actorFingerprint, String actionId);

    /** One transaction: deletes any existing candidates for {@code runId} and inserts the given set (idempotent per run). */
    void replaceCandidates(String runId, List<DiscoveryCandidateRecord> candidates, String actorFingerprint,
            String actionId);

    /** Cluster/host parents before their members -- callers needing a display tree sort client-side by {@code parentCandidateId}. */
    List<DiscoveryCandidateRecord> listCandidates(String runId);

    Optional<DiscoveryCandidateRecord> findCandidate(String candidateId);

    /** RD-5: {@code new} / {@code already_imported} / {@code conflicting}, written once per imported candidate. */
    boolean markImportOutcome(String candidateId, String importOutcome, String actorFingerprint, String actionId);

    /** DR-3: deletes every run (and its candidates, cascade) whose {@code finished_at} is older than {@code now - 24h}. */
    int sweepExpired(Instant now, String actorFingerprint, String actionId);
}
