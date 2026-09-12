package com.securityexpert.nexus.ui2.jobs.stepattempt;

import java.util.List;
import java.util.Optional;

/**
 * {@code job_step_attempt} persistence (C2 §5.1). {@link
 * #insertPreContact} must be called, and its write committed, strictly
 * before the executor invokes {@code DeviceTransport} for that step
 * (contract §7, §8 test 7) -- this interface does not enforce that
 * ordering itself (a Java interface cannot), but {@code StepExecutor} is
 * structured so the call sequence makes the alternative unreachable, and
 * {@code StepAttemptCommittedBeforeTransportInvocationTest} proves it with
 * an instrumented double.
 */
public interface JobStepAttemptRepository {

    String insertPreContact(String jobId, long leaseEpoch, int stepIndex, String stepKind, String actionClass,
            int attemptNumber);

    boolean markBoundaryCrossed(String attemptId, long leaseEpoch);

    boolean writeOutcome(String attemptId, long leaseEpoch, String outcome, String errorClass, long outputBytes,
            long outputLines, String fingerprintSha256);

    Optional<StepAttempt> find(String attemptId);

    List<StepAttempt> findByJobAndStep(String jobId, int stepIndex);
}
