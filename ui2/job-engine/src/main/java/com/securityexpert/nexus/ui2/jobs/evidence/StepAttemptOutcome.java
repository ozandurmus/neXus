package com.securityexpert.nexus.ui2.jobs.evidence;

import java.time.Instant;
import java.util.Optional;

/**
 * {@code job_step_attempt}'s post-contact fields (C2 §5.1). Written in the
 * same transaction as the {@link ProvenanceRecordData} row and the
 * pre-contact attempt row's completion (contract §7: "in one transaction
 * with the step's terminal attempt write"). {@code outcome} is never a raw
 * transcript -- {@code outputBytes}/{@code outputLines}/{@code
 * fingerprintSha256} are its only trace (C2 §5.1, raw-evidence law).
 */
public record StepAttemptOutcome(
        String outcome,
        Optional<String> errorClass,
        long outputBytes,
        long outputLines,
        String fingerprintSha256,
        Instant recordedAt) {

    public StepAttemptOutcome {
        errorClass = errorClass == null ? Optional.empty() : errorClass;
    }
}
