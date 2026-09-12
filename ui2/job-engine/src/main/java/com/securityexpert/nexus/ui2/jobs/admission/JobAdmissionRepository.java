package com.securityexpert.nexus.ui2.jobs.admission;

import java.util.Optional;

/** The persistence-facing half of admission (job-row creation only; the checks themselves are {@link JobAdmissionService}'s). */
public interface JobAdmissionRepository {

    Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
            String targetDeviceId, String actionClassId, String actorFingerprint, String actionId);

    Optional<String> findByIdempotencyKey(String idempotencyKey);
}
