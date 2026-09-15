package com.securityexpert.nexus.ui2.worker.discovery;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumeration;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumeration;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationResult;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.StepAttempt;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunState;

/** No-database test doubles for {@link DiscoveryJobExecutor}, mirroring {@code worker.inventory.InventoryJobExecutorFakes}. */
final class DiscoveryJobExecutorFakes {

    private DiscoveryJobExecutorFakes() {
    }

    static final class FakeLeaseRepository implements JobLeaseRepository {
        final List<String> transitions = new ArrayList<>();
        String terminalReason;
        final String jobId;
        final long leaseEpoch;
        JobState currentState;

        FakeLeaseRepository(String jobId, long leaseEpoch, JobState initialState) {
            this.jobId = jobId;
            this.leaseEpoch = leaseEpoch;
            this.currentState = initialState;
        }

        @Override
        public Optional<ClaimedJob> claimNext(String workerId, List<String> eligibleCapabilityIds, Duration leaseDuration) {
            throw new UnsupportedOperationException("not exercised by DiscoveryJobExecutor tests");
        }

        @Override
        public boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration) {
            return true;
        }

        @Override
        public boolean transitionState(String jobId, long leaseEpoch, JobState expectedFrom, JobState to,
                String actorFingerprint, String actionId) {
            transitions.add(expectedFrom + "->" + to);
            if (!this.jobId.equals(jobId) || this.leaseEpoch != leaseEpoch || this.currentState != expectedFrom) {
                return false;
            }
            this.currentState = to;
            return true;
        }

        @Override
        public boolean transitionState(String jobId, long leaseEpoch, JobState expectedFrom, JobState to,
                String actorFingerprint, String actionId, String terminalReason) {
            this.terminalReason = terminalReason;
            return transitionState(jobId, leaseEpoch, expectedFrom, to, actorFingerprint, actionId);
        }

        @Override
        public List<ClaimedJob> findExpiredWithNoAttempt() {
            return List.of();
        }

        @Override
        public List<ClaimedJob> findExpiredAllBoundaryNo() {
            return List.of();
        }

        @Override
        public List<ClaimedJob> findExpiredUnconfirmedYes() {
            return List.of();
        }
    }

    static final class FakeStepAttemptRepository implements JobStepAttemptRepository {
        final Map<String, StepAttempt> attempts = new HashMap<>();
        final AtomicInteger idSeq = new AtomicInteger();
        boolean refuseInsert = false;
        boolean refuseWriteOutcome = false;
        long lastOutputBytes;
        Boolean lastMatchedExpectation;

        @Override
        public String insertPreContact(String jobId, long leaseEpoch, int stepIndex, String stepKind,
                String actionClass, int attemptNumber) {
            if (refuseInsert) {
                return null;
            }
            String attemptId = "attempt-" + idSeq.incrementAndGet();
            attempts.put(attemptId, new StepAttempt(attemptId, jobId, leaseEpoch, stepIndex, attemptNumber, stepKind,
                    actionClass, false, Optional.empty(), Optional.empty()));
            return attemptId;
        }

        @Override
        public boolean markBoundaryCrossed(String attemptId, long leaseEpoch) {
            throw new UnsupportedOperationException("discovery_enumerate is a pure CLASS_0_READ job");
        }

        @Override
        public boolean writeOutcome(String attemptId, long leaseEpoch, String outcome, String errorClass,
                long outputBytes, long outputLines, String fingerprintSha256) {
            if (refuseWriteOutcome) {
                return false;
            }
            StepAttempt existing = attempts.get(attemptId);
            if (existing == null) {
                return false;
            }
            attempts.put(attemptId, new StepAttempt(existing.attemptId(), existing.jobId(), existing.leaseEpoch(),
                    existing.stepIndex(), existing.attemptNumber(), existing.stepKind(), existing.actionClass(),
                    existing.mutationBoundaryCrossed(), Optional.of(outcome), Optional.ofNullable(errorClass)));
            return true;
        }

        @Override
        public boolean writeOutcome(String attemptId, long leaseEpoch, String outcome, String errorClass,
                boolean matchedExpectation, long outputBytes, long outputLines, String fingerprintSha256) {
            lastMatchedExpectation = matchedExpectation;
            lastOutputBytes = outputBytes;
            return writeOutcome(attemptId, leaseEpoch, outcome, errorClass, outputBytes, outputLines, fingerprintSha256);
        }

        @Override
        public Optional<StepAttempt> find(String attemptId) {
            return Optional.ofNullable(attempts.get(attemptId));
        }

        @Override
        public List<StepAttempt> findByJobAndStep(String jobId, int stepIndex) {
            return attempts.values().stream()
                    .filter(a -> a.jobId().equals(jobId) && a.stepIndex() == stepIndex)
                    .toList();
        }
    }

    static final class FakeDiscoveryRunRepository implements DiscoveryRunRepository {
        DiscoveryRun run;
        List<DiscoveryCandidateRecord> lastReplacedCandidates;
        Map<String, Integer> lastOutcomeSummary;
        String lastFailureReasonClass;
        boolean runningCalled = false;
        boolean finishedCalled = false;
        boolean failedCalled = false;
        RuntimeException throwOnReplaceCandidates;

        @Override
        public void createRun(String runId, String vendor, String managementAddress, String credentialReferenceId,
                String requestedByActorFingerprint, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean setJobId(String runId, String jobId, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<DiscoveryRun> findRun(String runId) {
            return Optional.ofNullable(run);
        }

        @Override
        public boolean markRunning(String runId, String actorFingerprint, String actionId) {
            runningCalled = true;
            return true;
        }

        @Override
        public boolean markFinished(String runId, Map<String, Integer> outcomeSummary, String actorFingerprint,
                String actionId) {
            finishedCalled = true;
            lastOutcomeSummary = outcomeSummary;
            return true;
        }

        @Override
        public boolean markFailed(String runId, String reasonClass, String actorFingerprint, String actionId) {
            failedCalled = true;
            lastFailureReasonClass = reasonClass;
            return true;
        }

        @Override
        public void replaceCandidates(String runId, List<DiscoveryCandidateRecord> candidates,
                String actorFingerprint, String actionId) {
            if (throwOnReplaceCandidates != null) {
                throw throwOnReplaceCandidates;
            }
            lastReplacedCandidates = candidates;
        }

        @Override
        public List<DiscoveryCandidateRecord> listCandidates(String runId) {
            return lastReplacedCandidates == null ? List.of() : lastReplacedCandidates;
        }

        @Override
        public Optional<DiscoveryCandidateRecord> findCandidate(String candidateId) {
            return listCandidates(null).stream().filter(c -> c.candidateId().equals(candidateId)).findFirst();
        }

        @Override
        public boolean markImportOutcome(String candidateId, String importOutcome, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public int sweepExpired(Instant now, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    static DiscoveryRun requestedRun(String runId, String vendor) {
        return new DiscoveryRun(runId, vendor, "10.0.0.1", "cred-1", "actor-1", DiscoveryRunState.REQUESTED,
                Optional.of("job-1"), Optional.empty(), Optional.empty(), Optional.empty());
    }

    static final class FakeManagementPlaneEnumeration implements ManagementPlaneEnumeration {
        ManagementPlaneEnumerationResult result;
        ManagementPlaneEnumerationRequest lastRequest;

        @Override
        public ManagementPlaneEnumerationResult run(ManagementPlaneEnumerationRequest request) {
            lastRequest = request;
            return result;
        }
    }

    static final class FakePanoramaEnumeration implements PanoramaEnumeration {
        PanoramaEnumerationResult result;
        PanoramaEnumerationRequest lastRequest;

        @Override
        public PanoramaEnumerationResult run(PanoramaEnumerationRequest request) {
            lastRequest = request;
            return result;
        }
    }
}
