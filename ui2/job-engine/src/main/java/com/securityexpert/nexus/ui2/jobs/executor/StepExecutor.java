package com.securityexpert.nexus.ui2.jobs.executor;

import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.evidence.EvidenceWriterPort;
import com.securityexpert.nexus.ui2.jobs.evidence.ProvenanceRecordData;
import com.securityexpert.nexus.ui2.jobs.evidence.StepAttemptOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.parsing.ParsedStepResult;
import com.securityexpert.nexus.ui2.jobs.parsing.ParserRunner;
import com.securityexpert.nexus.ui2.jobs.parsing.StepParser;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.DeviceStatePort;
import com.securityexpert.nexus.ui2.platform.WorkerActor;

/**
 * The step executor (contract §4). This class is deliberately ignorant of
 * <em>why</em> a fenced write returns zero rows -- a stale epoch and an
 * illegal transition surface identically, and both mean "stop, do not
 * contact the device again" (C2 §4.2). It is equally ignorant of crash
 * recovery: if this process is killed mid-step, nothing here ever runs
 * again for that attempt -- {@link JobReconciler}, reading only durable
 * state, is what later classifies the crash point (contract §8, C2 §8).
 * This separation is what makes the executor's own logic simple enough to
 * audit for the one property that matters: <b>the pre-contact attempt row
 * commits, and for a write step its {@code mutation_boundary_crossed} flip
 * commits, strictly before {@link DeviceTransport} is ever invoked</b>
 * (contract §8 test 7).
 */
public final class StepExecutor {

    /** C2 §5.3's proposed default: up to 2 retries (3 attempts total) for a class-0 pre-response transport failure. */
    public static final int CLASS_ZERO_RETRY_BUDGET = 2;

    private final JobLeaseRepository leaseRepository;
    private final JobStepAttemptRepository attemptRepository;
    private final EvidenceWriterPort evidenceWriter;
    private final DeviceEnrollmentReadPort deviceEnrollmentReadPort;
    private final DeviceTransport transport;
    private final DeviceStatePort deviceStatePort;
    private final Duration connectTimeoutDefault;

    public StepExecutor(JobLeaseRepository leaseRepository, JobStepAttemptRepository attemptRepository,
            EvidenceWriterPort evidenceWriter, DeviceEnrollmentReadPort deviceEnrollmentReadPort,
            DeviceTransport transport, DeviceStatePort deviceStatePort) {
        // Contract §11-4, adjudication F11: "the connect timeout default is
        // 10 seconds, owned by B1-4 as adapter configuration and
        // overridable per endpoint." 10s here is only the constructor's own
        // fallback; a caller that wires a worker-level configured default
        // (per adjudication F11, a tunable operational value) uses the
        // other constructor instead.
        this(leaseRepository, attemptRepository, evidenceWriter, deviceEnrollmentReadPort, transport,
                deviceStatePort, Duration.ofSeconds(10));
    }

    public StepExecutor(JobLeaseRepository leaseRepository, JobStepAttemptRepository attemptRepository,
            EvidenceWriterPort evidenceWriter, DeviceEnrollmentReadPort deviceEnrollmentReadPort,
            DeviceTransport transport, DeviceStatePort deviceStatePort, Duration connectTimeoutDefault) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.evidenceWriter = Objects.requireNonNull(evidenceWriter, "evidenceWriter");
        this.deviceEnrollmentReadPort = Objects.requireNonNull(deviceEnrollmentReadPort, "deviceEnrollmentReadPort");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.deviceStatePort = deviceStatePort;
        this.connectTimeoutDefault = Objects.requireNonNull(connectTimeoutDefault, "connectTimeoutDefault");
    }

    /**
     * Runs a freshly-claimed job to a terminal state (or a stop-point:
     * {@link JobOutcome.ZombieStopped}/{@link JobOutcome.Rejected} are not
     * job-state terminals by themselves at the C2 §3.1 level except
     * REJECTED, which is terminal). Caller supplies the already-resolved
     * capability and connection details; this method holds none of the
     * registry/gate-resolution logic itself (that already ran at claim
     * admission and at claim-time re-resolution, contract §3/§6).
     */
    public JobOutcome execute(String jobId, long leaseEpoch, Capability capability, String targetDeviceId,
            ConnectionTarget connectionTarget, ConnectSpec connectSpec, Function<CapabilityStep, StepParser> parserFactory) {

        // F6, second enforcement point: re-check device enrollment at claim
        // time, independent of admission's own check (F4). A device that
        // went DRAFT/disabled between admission and claim is refused here,
        // before EXECUTING, before any device contact.
        var enrollment = deviceEnrollmentReadPort.findEnrollment(targetDeviceId);
        if (enrollment.isEmpty() || !enrollment.get().permitsReadCollection()) {
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.REJECTED,
                    WorkerActor.RESERVED_ACTOR_FINGERPRINT, "job_claim_check_device_enrollment");
            return new JobOutcome.Rejected("DEVICE_NOT_ELIGIBLE_AT_CLAIM_TIME");
        }

        if (!leaseRepository.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.EXECUTING,
                WorkerActor.RESERVED_ACTOR_FINGERPRINT, "job_claim_to_executing")) {
            return new JobOutcome.ZombieStopped();
        }

        TransportSession session = null;
        List<CapabilityStep> allSteps = capability.allSteps();
        for (int stepIndex = 0; stepIndex < allSteps.size(); stepIndex++) {
            CapabilityStep step = allSteps.get(stepIndex);
            ActionClass actionClass = Optional.ofNullable(capability.resolvedActionClass(step))
                    .orElse(ActionClass.CLASS_0_READ);

            StepRunOutcome runOutcome = runStepWithBoundedRetry(jobId, leaseEpoch, stepIndex, step, actionClass,
                    session, connectionTarget, connectSpec, parserFactory);

            if (runOutcome.newSession != null) {
                session = runOutcome.newSession;
            }

            switch (runOutcome.kind) {
                case ZOMBIE -> {
                    return new JobOutcome.ZombieStopped();
                }
                case OUTCOME_UNKNOWN -> {
                    // The executor itself observed an ambiguous response
                    // after a write step's boundary crossed (C2 §3.3: "the
                    // claiming worker itself, if it observes an ambiguous
                    // response before losing its lease"). Stop entirely --
                    // no finally steps run, because that would itself be an
                    // automatic second device contact (contract §4: "no
                    // component this movement ships ever issues a second
                    // device contact for that job_id").
                    leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.OUTCOME_UNKNOWN,
                            WorkerActor.RESERVED_ACTOR_FINGERPRINT, "job_outcome_unknown_observed_inline");
                    return new JobOutcome.OutcomeUnknown(runOutcome.detail);
                }
                case DEFINITE_FAILURE -> {
                    leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED,
                            WorkerActor.RESERVED_ACTOR_FINGERPRINT, "job_failed_step_definite_failure");
                    recordDeviceContactOutcome(targetDeviceId, false);
                    return new JobOutcome.Failed(runOutcome.detail);
                }
                case MATCHED -> {
                    // continue to next step
                }
                default -> throw new IllegalStateException("unhandled step outcome kind: " + runOutcome.kind);
            }
        }

        leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.COMPLETED,
                WorkerActor.RESERVED_ACTOR_FINGERPRINT, "job_completed");
        recordDeviceContactOutcome(targetDeviceId, true);
        return new JobOutcome.Completed();
    }

    /**
     * Adjudication F7: "the executor emits the transition after the
     * attempt record is written... through the device-state port B1-4b
     * already provides." Called only after the job has already reached its
     * own terminal write above -- never before, and never a second time for
     * the same run. A device-state change from this call can never abort a
     * run already {@code EXECUTING} (F7's own invariant): by construction,
     * this method only runs once the run is already finished.
     */
    private void recordDeviceContactOutcome(String targetDeviceId, boolean contactSucceeded) {
        if (deviceStatePort == null) {
            return;
        }
        deviceStatePort.recordContactOutcome(targetDeviceId, contactSucceeded,
                WorkerActor.RESERVED_ACTOR_FINGERPRINT, "job_device_contact_outcome");
    }

    private enum StepOutcomeKind {
        MATCHED, DEFINITE_FAILURE, OUTCOME_UNKNOWN, ZOMBIE
    }

    private record StepRunOutcome(StepOutcomeKind kind, String detail, TransportSession newSession) {
    }

    private StepRunOutcome runStepWithBoundedRetry(String jobId, long leaseEpoch, int stepIndex,
            CapabilityStep step, ActionClass actionClass, TransportSession currentSession,
            ConnectionTarget connectionTarget, ConnectSpec connectSpec, Function<CapabilityStep, StepParser> parserFactory) {

        boolean isWriteStep = actionClass == ActionClass.CLASS_1_RECOVERY_WRITE
                || actionClass == ActionClass.CLASS_1B_CONTROLLED_RESTORE_WRITE;
        int attemptNumber = 1;
        TransportSession session = currentSession;

        while (true) {
            String attemptId = attemptRepository.insertPreContact(jobId, leaseEpoch, stepIndex,
                    step.kind().name(), actionClass.id(), attemptNumber);

            if (isWriteStep) {
                // The durable fact the crash matrix depends on (contract
                // §4, §8 test 7): this write commits BEFORE the transport
                // call below is ever issued. A zero-row result here means
                // this worker's fencing token is already stale -- it must
                // not proceed to contact the device at all.
                if (!attemptRepository.markBoundaryCrossed(attemptId, leaseEpoch)) {
                    return new StepRunOutcome(StepOutcomeKind.ZOMBIE, "fenced boundary-cross write affected zero rows",
                            session);
                }
            }

            StepContactResult contact = invokeTransport(step, session, connectionTarget, connectSpec);
            if (contact.newSession != null) {
                session = contact.newSession;
            }

            if (contact.transientTransportFailure && !isWriteStep && attemptNumber <= CLASS_ZERO_RETRY_BUDGET) {
                // C2 §5.3: bounded automatic retry, class-0 only, only for
                // a pre-response transport failure -- new attempt row, same
                // step_index, incremented attempt_number.
                attemptNumber++;
                continue;
            }

            ParsedStepResult parsed = evaluateStep(step, contact, parserFactory);
            return finalizeAttempt(jobId, leaseEpoch, attemptId, parsed, isWriteStep, session);
        }
    }

    private StepRunOutcome finalizeAttempt(String jobId, long leaseEpoch, String attemptId, ParsedStepResult parsed,
            boolean isWriteStep, TransportSession session) {
        String outcome;
        String errorClass;
        StepOutcomeKind kind;
        String detail;

        if (parsed instanceof ParsedStepResult.Matched) {
            outcome = "MATCHED";
            errorClass = null;
            kind = StepOutcomeKind.MATCHED;
            detail = null;
        } else if (parsed instanceof ParsedStepResult.ExpectationUnmet unmet) {
            outcome = "EXPECTATION_UNMET";
            errorClass = unmet.errorClass();
            kind = StepOutcomeKind.DEFINITE_FAILURE;
            detail = unmet.detail();
        } else if (parsed instanceof ParsedStepResult.Ambiguous ambiguous) {
            outcome = null; // C1 §3.4: UNKNOWN is NULL, never a sentinel string.
            errorClass = ambiguous.errorClass();
            detail = ambiguous.detail();
            // A read step's ambiguity is a parser problem, not a device
            // uncertainty -- C2 §5.3: "OUTCOME_UNKNOWN never applies to a
            // pure class-0 job." Only a write step's ambiguity, observed
            // after its boundary already crossed, reaches OUTCOME_UNKNOWN.
            kind = isWriteStep ? StepOutcomeKind.OUTCOME_UNKNOWN : StepOutcomeKind.DEFINITE_FAILURE;
        } else {
            throw new IllegalStateException("unreachable: unknown ParsedStepResult variant " + parsed);
        }

        boolean written = attemptRepository.writeOutcome(attemptId, leaseEpoch, outcome, errorClass, 0L, 0L,
                fingerprintOf(String.valueOf(parsed)));
        if (!written) {
            return new StepRunOutcome(StepOutcomeKind.ZOMBIE, "fenced outcome write affected zero rows", session);
        }

        evidenceWriter.writeStepEvidence(attemptId, leaseEpoch,
                new ProvenanceRecordData(attemptId, jobId, attemptId, "UNKNOWN", capabilityVersionPlaceholder(),
                        Optional.empty(), "device-response", Optional.empty(), fingerprintOf(String.valueOf(parsed))),
                new StepAttemptOutcome(String.valueOf(outcome), Optional.ofNullable(errorClass), 0L, 0L,
                        fingerprintOf(String.valueOf(parsed)), Instant.now()));

        return new StepRunOutcome(kind, detail, session);
    }

    private record StepContactResult(String rawOutput, boolean transientTransportFailure, TransportSession newSession) {
    }

    private StepContactResult invokeTransport(CapabilityStep step, TransportSession session,
            ConnectionTarget connectionTarget, ConnectSpec connectSpec) {
        Duration timeout = step.timeoutS().map(Duration::ofSeconds).orElse(connectTimeoutDefault);
        return switch (step.kind()) {
            case CONNECT -> {
                ConnectResult result = transport.connect(connectionTarget, connectSpec, timeout);
                if (result instanceof ConnectResult.Authenticated authenticated) {
                    yield new StepContactResult("CONNECTED", false, authenticated.session());
                }
                if (result instanceof ConnectResult.TimedOut) {
                    yield new StepContactResult(null, true, null);
                }
                // AuthenticationFailed/HostKeyRejected: a definite failure,
                // never OUTCOME_UNKNOWN (contract §5: "a mismatch is a
                // definite ConnectResult failure... since no mutation
                // boundary was approached").
                yield new StepContactResult(null, false, null);
            }
            case EXEC -> {
                if (session == null) {
                    throw new IllegalStateException("exec step reached with no open transport session");
                }
                ExecResult result = transport.exec(session, new ExecSpec(step.commandTemplate()), timeout);
                if (result instanceof ExecResult.Completed completed) {
                    yield new StepContactResult(completed.output(), false, null);
                }
                if (result instanceof ExecResult.TimedOut) {
                    // A timeout is never a success (C2 §5.2) and, for a
                    // class-0 step, is treated as a transient pre-response
                    // failure eligible for the bounded retry.
                    yield new StepContactResult(null, true, null);
                }
                yield new StepContactResult(null, true, null);
            }
            case DISCONNECT -> {
                if (session != null) {
                    transport.disconnect(session);
                }
                yield new StepContactResult("DISCONNECTED", false, null);
            }
            case VERIFY -> new StepContactResult("VERIFIED", false, null);
            default -> throw new UnsupportedOperationException(
                    step.kind() + " has no executor-level implementation at this movement "
                            + "(only connect/exec/disconnect/verify are exercised by the shipped capability)");
        };
    }

    private ParsedStepResult evaluateStep(CapabilityStep step, StepContactResult contact,
            Function<CapabilityStep, StepParser> parserFactory) {
        if (contact.transientTransportFailure) {
            return new ParsedStepResult.ExpectationUnmet("transport did not respond before timeout/failure");
        }
        // connect/disconnect/verify have no capability-authored parser
        // semantics to run (C4 §2.3: connect's expectation is auth+banner,
        // evaluated by the transport itself; disconnect/verify have no
        // expectation gate at all) -- only a kind that actually sends a
        // command whose response needs interpreting (exec today; poll/
        // xml_api_call in a later slice) goes through the parser framework.
        if (step.kind() != StepKind.EXEC) {
            return new ParsedStepResult.Matched(java.util.Map.of());
        }
        StepParser parser = parserFactory.apply(step);
        return ParserRunner.run(parser, contact.rawOutput());
    }

    private static String capabilityVersionPlaceholder() {
        return "UNKNOWN";
    }

    private static String fingerprintOf(String value) {
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
}
