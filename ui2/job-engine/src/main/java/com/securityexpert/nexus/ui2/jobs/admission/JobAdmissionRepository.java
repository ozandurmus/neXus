package com.securityexpert.nexus.ui2.jobs.admission;

import java.util.Optional;

/** The persistence-facing half of admission (job-row creation only; the checks themselves are {@link JobAdmissionService}'s). */
public interface JobAdmissionRepository {

    Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
            String targetDeviceId, String actionClassId, String actorFingerprint, String actionId);

    /** 14F DR-1: the discovery-run sibling of {@link #createRequestedIfAbsent}. */
    Optional<String> createRequestedIfAbsentForRun(String jobId, String idempotencyKey, String capabilityId,
            String targetRunId, String actionClassId, String actorFingerprint, String actionId);

    Optional<String> findByIdempotencyKey(String idempotencyKey);

    default AdmissionResult createDiagnosticIfAllowed(String jobId, String idempotencyKey, String targetDeviceId,
            String port, String actorFingerprint, String actionId) {
        return new AdmissionResult.Refused("DIAGNOSTIC_UNSUPPORTED", "diagnostic admission is not configured");
    }
}
