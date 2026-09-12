package com.securityexpert.nexus.ui2.jobs.admission;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;

/** {@link JobAdmissionRepository} adapter over {@code persistence}'s {@link JobRecordDao} (DIR-7: no jOOQ import here). */
public final class PersistenceJobAdmissionRepository implements JobAdmissionRepository {

    private final JobRecordDao dao;

    public PersistenceJobAdmissionRepository(JobRecordDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    @Override
    public Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
            String targetDeviceId, String actionClassId, String actorFingerprint, String actionId) {
        // job_type mirrors capability_id (C2 §2.1: "job_type / capability_id
        // -- reference into the C4 capability registry," treated as one
        // field); this movement introduces no separate job_type taxonomy.
        return dao.insertRequestedIfAbsent(jobId, idempotencyKey, capabilityId, targetDeviceId, actionClassId,
                capabilityId, actorFingerprint, actionId);
    }

    @Override
    public Optional<String> findByIdempotencyKey(String idempotencyKey) {
        return dao.findJobIdByIdempotencyKey(idempotencyKey);
    }
}
