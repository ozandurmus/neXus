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

    /** @return the inserted row's own {@code job_id} if this key was new, {@code empty} on a duplicate key. */
    Optional<String> insertRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
            String targetDeviceId, String actionClass, String jobType, String actorFingerprint, String actionId);

    Optional<String> findJobIdByIdempotencyKey(String idempotencyKey);

    Optional<JobRow> find(String jobId);
}
