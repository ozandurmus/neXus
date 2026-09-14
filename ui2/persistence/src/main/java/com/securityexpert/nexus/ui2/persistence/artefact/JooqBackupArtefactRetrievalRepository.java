package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ-backed {@link BackupArtefactRetrievalRepository} (migration V18, 14I OR-3). */
public final class JooqBackupArtefactRetrievalRepository implements BackupArtefactRetrievalRepository {

    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqBackupArtefactRetrievalRepository(TransactionBoundary transactionBoundary) {
        Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public void record(String retrievalId, String artefactId, String reason, String destinationPath,
            String actorFingerprint, String actionId) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "insert into backup_artefact_retrieval(retrieval_id, artefact_id, actor_fingerprint, reason, "
                        + "destination_path) values ({0}, {1}, {2}, {3}, {4})",
                retrievalId, artefactId, actorFingerprint, reason, destinationPath));
    }
}
