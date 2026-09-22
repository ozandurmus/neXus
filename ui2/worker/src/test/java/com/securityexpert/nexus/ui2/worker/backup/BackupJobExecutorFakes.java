package com.securityexpert.nexus.ui2.worker.backup;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.StepAttempt;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupEndpointEligibilityRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/** No-database test doubles for {@link BackupJobExecutor}, mirroring {@code worker.configuration.ConfigurationJobExecutorFakes}. */
final class BackupJobExecutorFakes {

    private BackupJobExecutorFakes() {
    }

    static DeviceConfirmFacts confirmFactsWithSoftwareVersion(String softwareVersion) {
        return new DeviceConfirmFacts(Optional.of("gw-a"), Optional.of("Quantum"), Optional.ofNullable(softwareVersion),
                Optional.empty(), Optional.empty(), Optional.empty(), DeviceConfirmFacts.IDENTITY_MISMATCH_NONE,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                DeviceConfirmFacts.PEER_FOLLOW_NONE, Optional.empty());
    }

    static final class FakeLeaseRepository implements JobLeaseRepository {
        final List<String> transitions = new ArrayList<>();
        final String jobId;
        final long leaseEpoch;
        JobState currentState;
        List<ClaimedJob> expiredWithNoAttempt = List.of();
        List<ClaimedJob> expiredAllBoundaryNo = List.of();
        List<ClaimedJob> expiredUnconfirmedYes = List.of();

        FakeLeaseRepository(String jobId, long leaseEpoch, JobState initialState) {
            this.jobId = jobId;
            this.leaseEpoch = leaseEpoch;
            this.currentState = initialState;
        }

        @Override
        public Optional<ClaimedJob> claimNext(String workerId, List<String> eligibleCapabilityIds, Duration leaseDuration) {
            throw new UnsupportedOperationException("not exercised by BackupJobExecutor tests");
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
        public List<ClaimedJob> findExpiredWithNoAttempt() {
            return expiredWithNoAttempt;
        }

        @Override
        public List<ClaimedJob> findExpiredAllBoundaryNo() {
            return expiredAllBoundaryNo;
        }

        @Override
        public List<ClaimedJob> findExpiredUnconfirmedYes() {
            return expiredUnconfirmedYes;
        }
    }

    static final class FakeStepAttemptRepository implements JobStepAttemptRepository {
        final Map<String, StepAttempt> attempts = new HashMap<>();
        final AtomicInteger idSeq = new AtomicInteger();

        @Override
        public String insertPreContact(String jobId, long leaseEpoch, int stepIndex, String stepKind,
                String actionClass, int attemptNumber) {
            String attemptId = "attempt-" + idSeq.incrementAndGet();
            attempts.put(attemptId, new StepAttempt(attemptId, jobId, leaseEpoch, stepIndex, attemptNumber, stepKind,
                    actionClass, false, Optional.empty(), Optional.empty()));
            return attemptId;
        }

        @Override
        public boolean markBoundaryCrossed(String attemptId, long leaseEpoch) {
            throw new UnsupportedOperationException("not exercised by these tests");
        }

        @Override
        public boolean writeOutcome(String attemptId, long leaseEpoch, String outcome, String errorClass,
                long outputBytes, long outputLines, String fingerprintSha256) {
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
        public Optional<StepAttempt> find(String attemptId) {
            return Optional.ofNullable(attempts.get(attemptId));
        }

        @Override
        public List<StepAttempt> findByJobAndStep(String jobId, int stepIndex) {
            return attempts.values().stream().filter(a -> a.jobId().equals(jobId) && a.stepIndex() == stepIndex).toList();
        }
    }

    static final class FakeDeviceEnrollmentReadPort implements DeviceEnrollmentReadPort {
        DeviceEnrollmentState state = DeviceEnrollmentState.ENROLLED;
        boolean disabled = false;

        @Override
        public Optional<DeviceEnrollmentSnapshot> findEnrollment(String deviceId) {
            return Optional.of(new DeviceEnrollmentSnapshot(deviceId, state, disabled));
        }
    }

    static final class FakeDeviceRepository implements DeviceRepository {
        Optional<DeviceConfirmFacts> confirmFacts = Optional.empty();
        Optional<DeviceRecord> deviceRecord = Optional.empty();

        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            return deviceRecord;
        }

        @Override
        public Optional<EndpointRecord> findEndpoint(String endpointId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<EndpointRecord> findEndpointByDeviceId(String deviceId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState,
                DeviceEnrollmentState toState, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId) {
            return confirmFacts;
        }

        @Override
        public List<DeviceSummaryRecord> listAll() {
            // The manifest write consults the coalesced device view for a software version; this
            // fake holds no discovery-joined rows, so "unresolvable" stays unresolvable here.
            return List.of();
        }
    }

    static final class FakeBackupArtefactManifestRepository implements BackupArtefactManifestRepository {
        final List<BackupArtefactManifestRecord> recorded = new ArrayList<>();

        @Override
        public void record(BackupArtefactManifestRecord manifest, String actorFingerprint, String actionId) {
            recorded.add(manifest);
        }

        @Override
        public Optional<PlaintextDigestSummary> findLatestPlaintextDigest(String deviceId, String artefactClass) {
            return recorded.stream()
                    .filter(m -> m.deviceId().equals(deviceId) && m.artefactClass().equals(artefactClass))
                    .reduce((first, second) -> second)
                    .map(m -> new PlaintextDigestSummary(m.artefactId(), m.plaintextSha256()));
        }

        @Override
        public List<BackupArtefactSummary> findByDevice(String deviceId, String artefactClass) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public List<BackupArtefactSummary> findAll(String artefactClass) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<RetrievalManifest> findForRetrieval(String artefactId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    static final class FakeBackupEndpointEligibilityRepository implements BackupEndpointEligibilityRepository {
        final Map<String, String> ineligible = new HashMap<>();

        @Override
        public boolean isIneligible(String deviceId) {
            return ineligible.containsKey(deviceId);
        }

        @Override
        public void markIneligible(String deviceId, String reason, String actorFingerprint, String actionId) {
            ineligible.put(deviceId, reason);
        }

        @Override
        public void clear(String deviceId, String actorFingerprint, String actionId) {
            ineligible.remove(deviceId);
        }
    }
}
