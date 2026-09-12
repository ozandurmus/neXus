package com.securityexpert.nexus.ui2.persistence.jobrecords;

public record StepAttemptRow(
        String attemptId,
        String jobId,
        long leaseEpoch,
        int stepIndex,
        int attemptNumber,
        String stepKind,
        String actionClass,
        boolean mutationBoundaryCrossed,
        String outcome,
        String errorClass) {
}
