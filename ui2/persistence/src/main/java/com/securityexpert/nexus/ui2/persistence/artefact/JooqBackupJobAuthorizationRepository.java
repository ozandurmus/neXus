package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ-backed {@link BackupJobAuthorizationRepository} (migration V23). */
public final class JooqBackupJobAuthorizationRepository implements BackupJobAuthorizationRepository {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqBackupJobAuthorizationRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public void record(String jobId, String deviceId, String actorFingerprint, String reason, String actionId) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "insert into backup_job_authorization(job_id, device_id, actor_fingerprint, reason) "
                        + "values ({0}, {1}, {2}, {3})",
                jobId, deviceId, actorFingerprint, reason));
    }

    @Override
    public Optional<BackupJobAuthorizationRecord> find(String jobId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetchOptional("select job_id, device_id, "
                        + "actor_fingerprint, reason from backup_job_authorization where job_id = {0}", jobId)
                .map(row -> new BackupJobAuthorizationRecord(row.get("job_id", String.class),
                        row.get("device_id", String.class), row.get("actor_fingerprint", String.class),
                        row.get("reason", String.class))));
    }
}
