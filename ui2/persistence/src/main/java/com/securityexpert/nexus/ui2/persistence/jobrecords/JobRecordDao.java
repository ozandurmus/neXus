package com.securityexpert.nexus.ui2.persistence.jobrecords;

import java.util.Optional;

/**
 * Job-request creation (C2 §2.3, adjudication F4: "the idempotency key of
 * C2 §2.3 belongs to B1-4 and runs before a job row reaches REQUESTED").
 * {@link #insertRequestedIfAbsent} is the one and only INSERT path for a
 * new job row -- a collision on {@code idempotency_key} is de-duplication,
 * never an error surfaced to the caller (C2 §2.3: "a retried POST... creates
 * at most one job row for the same key").
 */
public interface JobRecordDao {

    record DiagnosticAdmission(String kind, String jobId) {
    }

    record DiagnosticJob(String jobId, String targetDeviceId, String port, String state,
            String statusToken, boolean statusPresent, Integer lineCount, String shapeId, String maskedOutput) {
    }

    /** @return the inserted row's own {@code job_id} if this key was new, {@code empty} on a duplicate key. */
    Optional<String> insertRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
            String targetDeviceId, String actionClass, String jobType, String actorFingerprint, String actionId);

    /**
     * DR-1: the discovery-run sibling of {@link #insertRequestedIfAbsent} --
     * {@code target_kind = 'discovery_run'}, {@code target_ref = targetRunId},
     * {@code target_device_id} left {@code NULL} (V14 {@code chk_jobs_target_shape}).
     *
     * @return the inserted row's own {@code job_id} if this key was new, {@code empty} on a duplicate key.
     */
    Optional<String> insertRequestedIfAbsentForRun(String jobId, String idempotencyKey, String capabilityId,
            String targetRunId, String actionClass, String jobType, String actorFingerprint, String actionId);

    Optional<String> findJobIdByIdempotencyKey(String idempotencyKey);

    Optional<JobRow> find(String jobId);

    /** GET /devices/{id}'s {@code job} field: the most recently submitted job targeting this device, if any. */
    Optional<JobRow> findMostRecentByTargetDeviceId(String targetDeviceId);

    /** Atomic target rate limit, idempotency, and typed port admission for the closed diagnostic capability. */
    default DiagnosticAdmission insertDiagnostic(String jobId, String idempotencyKey, String targetDeviceId,
            String port, String actorFingerprint, String actionId) {
        return new DiagnosticAdmission("UNSUPPORTED", null);
    }

    default Optional<DiagnosticJob> findDiagnostic(String jobId) {
        return Optional.empty();
    }

    default boolean writeDiagnosticResult(String jobId, String statusToken, int lineCount, String shapeId, String maskedOutput) {
        return false;
    }
}
