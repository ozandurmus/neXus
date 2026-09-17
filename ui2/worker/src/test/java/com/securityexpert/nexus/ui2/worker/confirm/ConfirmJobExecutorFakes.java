package com.securityexpert.nexus.ui2.worker.confirm;

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
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * No-database test doubles for {@link ConfirmJobExecutor}, mirroring
 * {@code job-engine}'s own {@code executor.StepExecutorFakes} idiom (same
 * shared-call-order convention, same "track a mutable state machine, don't
 * just always return true" fencing behavior needed here so the same
 * {@link FakeLeaseRepository} instance can drive both {@link
 * ConfirmJobExecutor} and (in the worker-dies test) {@code JobReconciler}
 * against a realistic {@code REQUESTED->CLAIMED->EXECUTING->...} history.
 */
final class ConfirmJobExecutorFakes {

    private ConfirmJobExecutorFakes() {
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
        public Optional<ClaimedJob> claimNext(String workerId, List<String> eligibleCapabilityIds,
                Duration leaseDuration) {
            throw new UnsupportedOperationException("not exercised by ConfirmJobExecutor tests");
        }

        @Override
        public boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration) {
            return true;
        }

        String lastTerminalReason;

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
            this.lastTerminalReason = terminalReason;
            return transitionState(jobId, leaseEpoch, expectedFrom, to, actorFingerprint, actionId);
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
        final List<String> callOrder = new ArrayList<>();
        final AtomicInteger idSeq = new AtomicInteger();

        @Override
        public String insertPreContact(String jobId, long leaseEpoch, int stepIndex, String stepKind,
                String actionClass, int attemptNumber) {
            String attemptId = "attempt-" + idSeq.incrementAndGet();
            callOrder.add("PRE_CONTACT_WRITTEN:" + attemptId);
            attempts.put(attemptId, new StepAttempt(attemptId, jobId, leaseEpoch, stepIndex, attemptNumber, stepKind,
                    actionClass, false, Optional.empty(), Optional.empty()));
            return attemptId;
        }

        @Override
        public boolean markBoundaryCrossed(String attemptId, long leaseEpoch) {
            throw new UnsupportedOperationException(
                    "the confirm is a pure CLASS_0_READ job -- it never crosses a mutation boundary");
        }

        @Override
        public boolean writeOutcome(String attemptId, long leaseEpoch, String outcome, String errorClass,
                long outputBytes, long outputLines, String fingerprintSha256) {
            callOrder.add("OUTCOME_WRITTEN:" + attemptId + ":" + outcome);
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
        DeviceEnrollmentState state = DeviceEnrollmentState.DRAFT;
        boolean disabled = false;

        @Override
        public Optional<DeviceEnrollmentSnapshot> findEnrollment(String deviceId) {
            return Optional.of(new DeviceEnrollmentSnapshot(deviceId, state, disabled));
        }
    }

    /** Only {@link #recordConfirmSuccess}/{@link #find} are meaningful here; every other method is unused by these tests. */
    static final class FakeDeviceRepository implements DeviceRepository {
        final Map<String, DeviceRecord> byId = new HashMap<>();
        DeviceConfirmFacts lastRecordedFacts;
        boolean recordConfirmSuccessCalled = false;

        void put(String deviceId, DeviceEnrollmentState state) {
            byId.put(deviceId, new DeviceRecord(deviceId, "gateway", "check_point", "manual_registration",
                    java.time.Instant.now(), false, state, false, "cred-ref-1"));
        }

        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            return Optional.ofNullable(byId.get(deviceId));
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
            throw new UnsupportedOperationException("not used by this test -- recordConfirmSuccess is the one "
                    + "write path a confirm job ever uses to leave DRAFT (EC-J3)");
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
            recordConfirmSuccessCalled = true;
            DeviceRecord existing = byId.get(deviceId);
            if (existing == null || existing.enrollmentState() != DeviceEnrollmentState.DRAFT) {
                return false;
            }
            lastRecordedFacts = facts;
            byId.put(deviceId, new DeviceRecord(existing.deviceId(), existing.role(), existing.vendorHint(),
                    existing.registrationSource(), existing.createdAt(), existing.isTestTarget(),
                    DeviceEnrollmentState.ENROLLED, existing.disabled(), existing.credentialReferenceId()));
            return true;
        }

        @Override
        public Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId) {
            return Optional.ofNullable(lastRecordedFacts);
        }

        @Override
        public List<com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord> listAll() {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
