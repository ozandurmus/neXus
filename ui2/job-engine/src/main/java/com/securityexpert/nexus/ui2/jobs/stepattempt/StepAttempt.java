package com.securityexpert.nexus.ui2.jobs.stepattempt;

import java.util.Optional;

/** One {@code job_step_attempt} row (C2 §5.1), job-engine's own view. */
public record StepAttempt(
        String attemptId,
        String jobId,
        long leaseEpoch,
        int stepIndex,
        int attemptNumber,
        String stepKind,
        String actionClass,
        boolean mutationBoundaryCrossed,
        Optional<String> outcome,
        Optional<String> errorClass) {
}
