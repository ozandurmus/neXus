package com.securityexpert.nexus.ui2.worker.configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationArtefactRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationNotificationRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRun;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRunView;
import com.securityexpert.nexus.ui2.persistence.device.configuration.DeviceConfigurationRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/** No-database test doubles for {@link ConfigurationJobExecutor}, mirroring {@code worker.inventory.InventoryJobExecutorFakes}. */
final class ConfigurationJobExecutorFakes {

    private ConfigurationJobExecutorFakes() {
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
            throw new UnsupportedOperationException("not exercised by ConfigurationJobExecutor tests");
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
            throw new UnsupportedOperationException("configuration_collect is a pure CLASS_0_READ job");
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
            return attempts.values().stream()
                    .filter(a -> a.jobId().equals(jobId) && a.stepIndex() == stepIndex)
                    .toList();
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

    /** Only {@link #findConfirmFacts} is meaningful here; every other method is unused by these tests. */
    static final class FakeDeviceRepository implements DeviceRepository {
        Optional<DeviceConfirmFacts> confirmFacts = Optional.empty();

        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            throw new UnsupportedOperationException("not used by this test");
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
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    static final class FakeDeviceConfigurationRepository implements DeviceConfigurationRepository {
        final List<ConfigurationRun> recordedRuns = new ArrayList<>();
        final List<ConfigurationArtefactRecord> recordedArtefacts = new ArrayList<>();
        boolean recordRunCalled = false;
        RuntimeException throwOnRecordRun;
        private final Map<String, Map<String, ConfigurationRun>> latestByDeviceAndReadKind = new LinkedHashMap<>();

        @Override
        public void recordRun(ConfigurationRun run, ConfigurationArtefactRecord artefact, String actorFingerprint,
                String actionId) {
            recordRunCalled = true;
            if (throwOnRecordRun != null) {
                throw throwOnRecordRun;
            }
            recordedRuns.add(run);
            recordedArtefacts.add(artefact);
            latestByDeviceAndReadKind.computeIfAbsent(run.deviceId(), key -> new LinkedHashMap<>())
                    .put(run.readKind(), run);
        }

        @Override
        public Optional<ConfigurationRun> findLatestRun(String deviceId, String readKind) {
            Map<String, ConfigurationRun> byReadKind = latestByDeviceAndReadKind.get(deviceId);
            return byReadKind == null ? Optional.empty() : Optional.ofNullable(byReadKind.get(readKind));
        }

        @Override
        public List<ConfigurationRun> findLatestRuns(List<String> deviceIds) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public ConfigurationRunView findRunView(String deviceId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    static final class FakeConfigurationNotificationRepository implements ConfigurationNotificationRepository {
        record RecordedNotification(String deviceId, String runId, String summary, List<String> overridePaths) {
        }

        final List<RecordedNotification> recorded = new ArrayList<>();

        @Override
        public void record(String deviceId, String runId, String summary, List<String> overridePaths,
                String actorFingerprint, String actionId) {
            recorded.add(new RecordedNotification(deviceId, runId, summary, List.copyOf(overridePaths)));
        }

        @Override
        public List<com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationNotification> listRecent(
                int limit) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
