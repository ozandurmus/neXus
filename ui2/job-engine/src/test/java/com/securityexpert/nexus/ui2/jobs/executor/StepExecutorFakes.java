package com.securityexpert.nexus.ui2.jobs.executor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.jobs.evidence.EvidenceWriterPort;
import com.securityexpert.nexus.ui2.jobs.evidence.ProvenanceRecordData;
import com.securityexpert.nexus.ui2.jobs.evidence.StepAttemptOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.StepAttempt;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.DeviceStatePort;
import com.securityexpert.nexus.ui2.platform.Result;

/**
 * Shared, no-database test doubles for {@link StepExecutor} tests
 * (contract §8 tests 5*, 7, 8, 9, 13 -- everything provable without a
 * container). {@code sharedCallOrder}, when supplied, is a single list
 * every fake appends to, in real invocation order, so a test can assert
 * cross-fake ordering (e.g. "the attempt repository's write precedes the
 * transport's invocation") without a wall clock.
 */
final class StepExecutorFakes {

    private StepExecutorFakes() {
    }

    static final class FakeLeaseRepository implements JobLeaseRepository {
        final List<String> transitions = new ArrayList<>();
        boolean fenceBroken = false;

        @Override
        public Optional<com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob> claimNext(String workerId,
                List<String> eligibleCapabilityIds, Duration leaseDuration) {
            throw new UnsupportedOperationException("not exercised by StepExecutor tests");
        }

        @Override
        public boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration) {
            return true;
        }

        @Override
        public boolean transitionState(String jobId, long leaseEpoch, JobState expectedFrom, JobState to,
                String actorFingerprint, String actionId) {
            transitions.add(expectedFrom + "->" + to);
            if (fenceBroken) {
                return false;
            }
            return true;
        }

        @Override
        public List<com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob> findExpiredWithNoAttempt() {
            return List.of();
        }

        @Override
        public List<com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob> findExpiredAllBoundaryNo() {
            return List.of();
        }

        @Override
        public List<com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob> findExpiredUnconfirmedYes() {
            return List.of();
        }
    }

    static final class FakeStepAttemptRepository implements JobStepAttemptRepository {
        final List<String> callOrder;
        final Map<String, StepAttempt> attempts = new HashMap<>();
        final AtomicInteger idSeq = new AtomicInteger();
        boolean boundaryCrossWriteSucceeds = true;
        boolean outcomeWriteSucceeds = true;

        FakeStepAttemptRepository(List<String> callOrder) {
            this.callOrder = callOrder;
        }

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
            callOrder.add("BOUNDARY_CROSSED:" + attemptId);
            return boundaryCrossWriteSucceeds;
        }

        @Override
        public boolean writeOutcome(String attemptId, long leaseEpoch, String outcome, String errorClass,
                long outputBytes, long outputLines, String fingerprintSha256) {
            callOrder.add("OUTCOME_WRITTEN:" + attemptId + ":" + outcome);
            return outcomeWriteSucceeds;
        }

        @Override
        public Optional<StepAttempt> find(String attemptId) {
            return Optional.ofNullable(attempts.get(attemptId));
        }

        @Override
        public List<StepAttempt> findByJobAndStep(String jobId, int stepIndex) {
            return attempts.values().stream()
                    .filter(a -> a.jobId().equals(jobId) && a.stepIndex() == stepIndex)
                    .sorted(java.util.Comparator.comparingInt(StepAttempt::attemptNumber))
                    .toList();
        }
    }

    static final class FakeEvidenceWriterPort implements EvidenceWriterPort {
        final List<ProvenanceRecordData> provenanceWrites = new ArrayList<>();
        final List<StepAttemptOutcome> outcomeWrites = new ArrayList<>();

        @Override
        public void writeStepEvidence(String attemptId, long leaseEpoch, ProvenanceRecordData provenance,
                StepAttemptOutcome outcome) {
            provenanceWrites.add(provenance);
            outcomeWrites.add(outcome);
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

    static final class FakeDeviceStatePort implements DeviceStatePort {
        final List<Boolean> outcomesRecorded = new ArrayList<>();

        @Override
        public Result<DeviceEnrollmentState> recordContactOutcome(String deviceId, boolean contactSucceeded,
                String actorFingerprint, String actionId) {
            outcomesRecorded.add(contactSucceeded);
            return Result.ok(contactSucceeded ? DeviceEnrollmentState.ENROLLED : DeviceEnrollmentState.UNREACHABLE);
        }
    }

    /** A transport double whose {@code exec} responses are scripted per call, in order, and whose invocations are recorded. */
    static final class FakeDeviceTransport implements DeviceTransport {
        final List<String> callOrder;
        final Deque<ExecResult> scriptedExecResults = new ArrayDeque<>();
        int connectCalls = 0;
        int execCalls = 0;

        FakeDeviceTransport(List<String> callOrder) {
            this.callOrder = callOrder;
        }

        @Override
        public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            connectCalls++;
            callOrder.add("TRANSPORT_CONNECT");
            return new ConnectResult.Authenticated(() -> "fake-session");
        }

        @Override
        public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            execCalls++;
            callOrder.add("TRANSPORT_EXEC:" + spec.command());
            if (!scriptedExecResults.isEmpty()) {
                return scriptedExecResults.poll();
            }
            return new ExecResult.Completed("default output", 0);
        }

        @Override
        public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void disconnect(TransportSession session) {
            callOrder.add("TRANSPORT_DISCONNECT");
        }
    }
}
