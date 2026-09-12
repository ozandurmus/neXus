package com.securityexpert.nexus.ui2.persistence.jobrecords;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ-backed {@link JobRecordDao}. */
public final class JooqJobRecordDao implements JobRecordDao {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqJobRecordDao(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = transactionBoundary;
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public Optional<String> insertRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
            String targetDeviceId, String actionClass, String jobType, String actorFingerprint, String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            org.jooq.Result<Record> rows = dsl.fetch(
                    "insert into jobs(job_id, job_type, capability_id, target_device_id, "
                            + "submitted_by_actor_fingerprint, submitted_at, idempotency_key, action_class, state) "
                            + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, 'REQUESTED') "
                            + "on conflict (idempotency_key) do nothing "
                            + "returning job_id",
                    jobId, jobType, capabilityId, targetDeviceId, actorFingerprint, Timestamp.from(Instant.now()),
                    idempotencyKey, actionClass);
            return rows.stream().findFirst().map(r -> r.get("job_id", String.class));
        });
    }

    @Override
    public Optional<String> findJobIdByIdempotencyKey(String idempotencyKey) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select job_id from jobs where idempotency_key = {0}", idempotencyKey)
                .stream().findFirst().map(r -> r.get("job_id", String.class)));
    }

    @Override
    public Optional<JobRow> find(String jobId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from jobs where job_id = {0}", jobId)
                .stream().findFirst().map(JooqJobRecordDao::toRow));
    }

    private static JobRow toRow(Record row) {
        return new JobRow(
                row.get("job_id", String.class),
                row.get("capability_id", String.class),
                row.get("target_device_id", String.class),
                row.get("action_class", String.class),
                row.get("state", String.class),
                row.get("lease_worker_id", String.class),
                row.get("lease_epoch", Long.class) == null ? 0L : row.get("lease_epoch", Long.class),
                row.get("outcome", String.class),
                row.get("terminal_reason", String.class));
    }
}
