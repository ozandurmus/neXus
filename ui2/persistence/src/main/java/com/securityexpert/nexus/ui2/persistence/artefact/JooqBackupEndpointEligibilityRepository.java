package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ-backed {@link BackupEndpointEligibilityRepository} (migration V18). */
public final class JooqBackupEndpointEligibilityRepository implements BackupEndpointEligibilityRepository {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqBackupEndpointEligibilityRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public boolean isIneligible(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetchOptional(
                "select device_id from backup_endpoint_ineligibility where device_id = {0}", deviceId).isPresent());
    }

    @Override
    public void markIneligible(String deviceId, String reason, String actorFingerprint, String actionId) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "insert into backup_endpoint_ineligibility(device_id, reason) values ({0}, {1}) "
                        + "on conflict (device_id) do update set reason = excluded.reason, marked_at = now()",
                deviceId, reason));
    }

    @Override
    public void clear(String deviceId, String actorFingerprint, String actionId) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "delete from backup_endpoint_ineligibility where device_id = {0}", deviceId));
    }
}
