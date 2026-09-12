package com.securityexpert.nexus.ui2.persistence.jobrecords;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ-backed {@link JobStepAttemptDao}. Every write carries the reserved worker actor's audit context (F3). */
public final class JooqJobStepAttemptDao implements JobStepAttemptDao {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqJobStepAttemptDao(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = transactionBoundary;
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public String insertPreContact(String jobId, long leaseEpoch, int stepIndex, String stepKind,
            String actionClass, int attemptNumber) {
        String attemptId = UUID.randomUUID().toString();
        auditedTransactionBoundary.inTransaction("system:worker", "job_step_attempt_pre_contact", dsl -> dsl.execute(
                "insert into job_step_attempt(attempt_id, job_id, lease_epoch, step_index, step_kind, "
                        + "attempt_number, action_class, mutation_boundary_crossed) "
                        + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, false)",
                attemptId, jobId, leaseEpoch, stepIndex, stepKind, attemptNumber, actionClass));
        return attemptId;
    }

    @Override
    public boolean markBoundaryCrossed(String attemptId, long leaseEpoch) {
        int updated = auditedTransactionBoundary.inTransaction("system:worker", "job_step_attempt_boundary_crossed",
                dsl -> dsl.execute(
                        "update job_step_attempt set mutation_boundary_crossed = true, sent_at = now() "
                                + "where attempt_id = {0} and lease_epoch = {1} and mutation_boundary_crossed = false",
                        attemptId, leaseEpoch));
        return updated == 1;
    }

    @Override
    public boolean writeOutcome(String attemptId, long leaseEpoch, String outcome, String errorClass,
            long outputBytes, long outputLines, String fingerprintSha256) {
        int updated = auditedTransactionBoundary.inTransaction("system:worker", "job_step_attempt_outcome",
                dsl -> dsl.execute(
                        "update job_step_attempt set outcome = {0}, error_class = {1}, output_bytes = {2}, "
                                + "output_lines = {3}, fingerprint_sha256 = {4} "
                                + "where attempt_id = {5} and lease_epoch = {6}",
                        outcome, errorClass, outputBytes, outputLines, fingerprintSha256, attemptId, leaseEpoch));
        return updated == 1;
    }

    @Override
    public Optional<StepAttemptRow> find(String attemptId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from job_step_attempt where attempt_id = {0}", attemptId)
                .stream().findFirst().map(JooqJobStepAttemptDao::toRow));
    }

    @Override
    public List<StepAttemptRow> findByJobAndStep(String jobId, int stepIndex) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from job_step_attempt where job_id = {0} and step_index = {1} order by attempt_number",
                jobId, stepIndex).stream().map(JooqJobStepAttemptDao::toRow).toList());
    }

    private static StepAttemptRow toRow(Record row) {
        return new StepAttemptRow(
                row.get("attempt_id", String.class),
                row.get("job_id", String.class),
                row.get("lease_epoch", Long.class),
                row.get("step_index", Integer.class),
                row.get("attempt_number", Integer.class),
                row.get("step_kind", String.class),
                row.get("action_class", String.class),
                Boolean.TRUE.equals(row.get("mutation_boundary_crossed", Boolean.class)),
                row.get("outcome", String.class),
                row.get("error_class", String.class));
    }
}
