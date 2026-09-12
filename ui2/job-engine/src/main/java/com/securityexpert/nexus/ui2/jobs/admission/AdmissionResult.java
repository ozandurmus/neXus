package com.securityexpert.nexus.ui2.jobs.admission;

/**
 * The outcome of {@link JobAdmissionService#submit} (adjudication F4/F6).
 * {@link Refused} names exactly which check failed -- there is no generic
 * "admission denied" without a reason, so a caller (and a test) can tell
 * apart a capability-gate refusal from a device-state refusal.
 */
public sealed interface AdmissionResult {

    /** A brand-new job row reached {@code REQUESTED}. */
    record Admitted(String jobId) implements AdmissionResult {
    }

    /** An existing job's idempotency key was reused -- no second row was created (C2 §2.3). */
    record Deduplicated(String jobId) implements AdmissionResult {
    }

    record Refused(String code, String reason) implements AdmissionResult {
    }
}
