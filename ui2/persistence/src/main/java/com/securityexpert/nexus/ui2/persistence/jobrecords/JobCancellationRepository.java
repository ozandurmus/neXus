package com.securityexpert.nexus.ui2.persistence.jobrecords;

import java.util.Optional;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** Atomic cancellation request, serialized with claim and terminal transitions. */
public class JobCancellationRepository {
    private final AuditedTransactionBoundary tx;
    public JobCancellationRepository(TransactionBoundary tx) { this.tx = new AuditedTransactionBoundary(tx); }

    public Optional<String> request(String jobId, String actor) {
        return tx.inTransaction(actor, "job_cancel", db -> db.fetch(
            "update jobs set cancel_requested = true, "
            + "state = case when state in ('REQUESTED','CLAIMED') then 'CANCELLED' else state end, "
            + "outcome = case when state in ('REQUESTED','CLAIMED') then 'CANCELLED' else outcome end, "
            + "terminal_reason = case when state in ('REQUESTED','CLAIMED') then 'CANCELLED' else terminal_reason end, "
            + "finished_at = case when state in ('REQUESTED','CLAIMED') then now() else finished_at end "
            + "where job_id = {0} and state in ('REQUESTED','CLAIMED','EXECUTING') returning state", jobId)
            .stream().findFirst().map(row -> row.get("state", String.class)));
    }
}
