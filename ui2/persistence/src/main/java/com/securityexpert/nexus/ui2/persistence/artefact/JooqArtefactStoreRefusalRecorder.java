package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** Writes one {@code artefact_store_refusal} row (audited by trigger) per refused write, as {@code actorFingerprint}. */
public final class JooqArtefactStoreRefusalRecorder implements ArtefactStoreRefusalRecorder {

    public static final String ACTION_ID = "artefact_store_quota_refused";
    private static final Logger LOG = Logger.getLogger(JooqArtefactStoreRefusalRecorder.class.getName());

    private final AuditedTransactionBoundary audited;
    private final String writer;
    private final String actorFingerprint;

    /** @param writer the pod or component that writes (worker, policy_worker, service_report) */
    public JooqArtefactStoreRefusalRecorder(TransactionBoundary transactionBoundary, String writer, String actorFingerprint) {
        this.audited = new AuditedTransactionBoundary(Objects.requireNonNull(transactionBoundary, "transactionBoundary"));
        this.writer = Objects.requireNonNull(writer, "writer");
        this.actorFingerprint = Objects.requireNonNull(actorFingerprint, "actorFingerprint");
    }

    @Override
    public void record(Refusal r) {
        try {
            audited.inTransaction(actorFingerprint, ACTION_ID, dsl -> dsl.execute(
                    "insert into artefact_store_refusal(refusal_id, store_name, scope, used_bytes, quota_bytes, "
                            + "attempted_bytes, writer, device_id, job_id) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8})",
                    UUID.randomUUID().toString(), r.store(), r.scope(), r.usedBytes(), r.quotaBytes(), r.attemptedBytes(),
                    writer, r.deviceId(), r.jobId()));
        } catch (RuntimeException e) {
            // The refusal itself already failed the write; a database blip must not hide or replace it.
            LOG.log(Level.WARNING, "could not record an artefact store refusal: " + e.getMessage());
        }
    }
}
