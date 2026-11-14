package com.securityexpert.nexus.ui2.persistence.jobrecords;

import java.util.Set;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * C2 section 3.5: the human step that closes an {@code OUTCOME_UNKNOWN} job. Writing the {@code job_reconciliation}
 * row and moving the job to {@code RECONCILED} happen in one audited transaction. This records a decision only: it
 * never contacts a device and never makes the job claimable again.
 */
public class JobReconciliationRepository {
    public static final Set<String> OUTCOMES =
        Set.of("RECONCILED_SUCCESS", "RECONCILED_FAILURE", "RECONCILED_STILL_UNKNOWN");
    public static final int EVIDENCE_MAX = 2000;
    public static final String RECONCILED = "RECONCILED";
    public static final String NOT_FOUND = "JOB_NOT_FOUND";
    public static final String NOT_OUTCOME_UNKNOWN = "JOB_NOT_OUTCOME_UNKNOWN";
    /** A failover run's unknown sends hold the admission fence; only the incident release (audited) may clear them. */
    public static final String FAILOVER_JOB = "JOB_BELONGS_TO_FAILOVER_RUN";
    public static final String ACTION = "job_reconcile_record";

    private final AuditedTransactionBoundary tx;
    public JobReconciliationRepository(TransactionBoundary tx) { this.tx = new AuditedTransactionBoundary(tx); }

    /** @return {@link #RECONCILED} or the refusal code; throws IllegalArgumentException for an invalid outcome/evidence. */
    public String record(String jobId, String outcome, String evidence, String actor) {
        if (jobId == null || actor == null || actor.isBlank() || outcome == null || !OUTCOMES.contains(outcome)
                || evidence == null || evidence.isBlank() || evidence.length() > EVIDENCE_MAX)
            throw new IllegalArgumentException("JOB_RECONCILIATION_INVALID");
        return tx.inTransaction(actor, ACTION, db -> {
            var row = db.fetchOne("select state from jobs where job_id = {0} for update", jobId);
            if (row == null) return NOT_FOUND;
            if (!"OUTCOME_UNKNOWN".equals(row.get("state", String.class))) return NOT_OUTCOME_UNKNOWN;
            if (!db.fetch("select 1 from failover_dispatch_intent where job_id = {0} limit 1", jobId).isEmpty())
                return FAILOVER_JOB;
            db.execute("insert into job_reconciliation(job_id, evidence, recorded_by, reconciled_outcome) "
                + "values ({0}, {1}, {2}, {3})", jobId, evidence, actor, outcome);
            if (db.execute("update jobs set state = 'RECONCILED', reconciliation_ref = {0} "
                    + "where job_id = {0} and state = 'OUTCOME_UNKNOWN'", jobId) != 1)
                throw new IllegalStateException("JOB_RECONCILIATION_RACE");
            return RECONCILED;
        });
    }
}
