package com.securityexpert.nexus.ui2.persistence.jobrecords;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link JobLeaseDao}. {@link #CLAIM_SQL} must stay
 * byte-for-byte identical to {@code job-engine}'s {@code
 * com.securityexpert.nexus.ui2.jobs.lease.ClaimStatementText#SQL} --
 * {@code persistence} cannot depend on {@code job-engine} to share the
 * constant directly (DIR-3), so
 * {@code ClaimIsAtomicNoReadThenWriteTest} (persistence) reads both source
 * files textually and asserts they match, rather than trusting a comment.
 * {@link #claimNext} is the <b>only</b> method in this class that mutates
 * {@code jobs.state} out of a {@code REQUESTED} row, and it issues exactly
 * one statement -- no separate {@code SELECT} to find a candidate followed
 * by a separate {@code UPDATE} (contract §8 test 1, AC-3).
 */
public final class JooqJobLeaseDao implements JobLeaseDao {

    static final String CLAIM_SQL = """
            UPDATE jobs
            SET state = 'CLAIMED',
                lease_worker_id = {0},
                lease_epoch = lease_epoch + 1,
                lease_expires_at = now() + ({1} || ' seconds')::interval,
                last_heartbeat_at = now()
            WHERE job_id = (
                SELECT job_id FROM jobs
                WHERE state = 'REQUESTED'
                  AND capability_id = ANY(string_to_array({2}, ','))
                ORDER BY submitted_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING job_id, lease_epoch
            """;

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqJobLeaseDao(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = transactionBoundary;
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public Optional<ClaimedJobRow> claimNext(String workerId, List<String> eligibleCapabilityIds,
            Duration leaseDuration) {
        if (eligibleCapabilityIds.isEmpty()) {
            // Never issues the statement against an empty eligible set --
            // string_to_array('', ',') would produce a one-element array
            // containing the empty string, a silent near-miss rather than
            // "nothing is eligible."
            return Optional.empty();
        }
        String joined = String.join(",", eligibleCapabilityIds);
        return auditedTransactionBoundary.inTransaction("system:worker", "job_claim", dsl -> {
            Result<Record> rows = dsl.fetch(CLAIM_SQL, workerId, String.valueOf(leaseDuration.toSeconds()), joined);
            return rows.stream().findFirst()
                    .map(row -> new ClaimedJobRow(row.get("job_id", String.class), row.get("lease_epoch", Long.class)));
        });
    }

    @Override
    public boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration) {
        int updated = auditedTransactionBoundary.inTransaction("system:worker", "job_heartbeat", dsl -> dsl.execute(
                "update jobs set lease_expires_at = now() + ({0} || ' seconds')::interval, "
                        + "last_heartbeat_at = now() "
                        + "where job_id = {1} and lease_epoch = {2} and state in ('CLAIMED', 'EXECUTING')",
                String.valueOf(leaseDuration.toSeconds()), jobId, leaseEpoch));
        return updated == 1;
    }

    @Override
    public boolean transitionState(String jobId, long leaseEpoch, String expectedFromState, String toState,
            String actorFingerprint, String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update jobs set state = {0} where job_id = {1} and lease_epoch = {2} and state = {3}",
                toState, jobId, leaseEpoch, expectedFromState));
        return updated == 1;
    }

    @Override
    public List<ClaimedJobRow> findExpiredWithNoAttempt() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select j.job_id, j.lease_epoch from jobs j where j.state = 'CLAIMED' and j.lease_expires_at < now() "
                        + "and not exists (select 1 from job_step_attempt a "
                        + "where a.job_id = j.job_id and a.lease_epoch = j.lease_epoch)")
                .stream().map(JooqJobLeaseDao::toClaimedJobRow).toList());
    }

    @Override
    public List<ClaimedJobRow> findExpiredAllBoundaryNo() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select j.job_id, j.lease_epoch from jobs j where j.state = 'EXECUTING' and j.lease_expires_at < now() "
                        + "and exists (select 1 from job_step_attempt a "
                        + "where a.job_id = j.job_id and a.lease_epoch = j.lease_epoch) "
                        + "and not exists (select 1 from job_step_attempt a "
                        + "where a.job_id = j.job_id and a.lease_epoch = j.lease_epoch "
                        + "and a.mutation_boundary_crossed = true)")
                .stream().map(JooqJobLeaseDao::toClaimedJobRow).toList());
    }

    @Override
    public List<ClaimedJobRow> findExpiredUnconfirmedYes() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select j.job_id, j.lease_epoch from jobs j where j.state = 'EXECUTING' and j.lease_expires_at < now() "
                        + "and exists (select 1 from job_step_attempt a "
                        + "where a.job_id = j.job_id and a.lease_epoch = j.lease_epoch "
                        + "and a.mutation_boundary_crossed = true and a.outcome is null)")
                .stream().map(JooqJobLeaseDao::toClaimedJobRow).toList());
    }

    private static ClaimedJobRow toClaimedJobRow(Record row) {
        return new ClaimedJobRow(row.get("job_id", String.class), row.get("lease_epoch", Long.class));
    }
}
