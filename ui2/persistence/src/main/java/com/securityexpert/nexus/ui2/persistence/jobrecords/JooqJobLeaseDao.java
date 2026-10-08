package com.securityexpert.nexus.ui2.persistence.jobrecords;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
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
            WITH inventory_claim_lock AS (
                SELECT pg_advisory_xact_lock(294611)
            ), owner_control AS MATERIALIZED (
                SELECT m.* FROM module_runtime_control m, inventory_claim_lock
                ORDER BY m.module FOR UPDATE OF m
            )
            UPDATE jobs
            SET state = 'CLAIMED',
                lease_worker_id = {0},
                lease_epoch = lease_epoch + 1,
                lease_expires_at = now() + ({1} || ' seconds')::interval,
                last_heartbeat_at = now(),
                lease_owner_generation = (SELECT generation FROM owner_control WHERE module=ui2_job_module(jobs.capability_id))
            WHERE job_id = (
                SELECT job_id FROM jobs, inventory_claim_lock
                WHERE state = 'REQUESTED'
                  AND clock_timestamp() < CAST({3} AS timestamptz)
                  AND capability_id = ANY(string_to_array({2}, ','))
                  AND job_type = ANY(string_to_array({2}, ','))
                  AND job_type = capability_id
                  AND (admission_not_before IS NULL OR admission_not_before <= now())
                  AND ((SELECT count(*) FROM jobs WHERE state IN ('CLAIMED', 'EXECUTING'))
                    + (SELECT count(*) FROM runtime_task_lease WHERE task_key='fleet.failover.execution' AND owner_role='service')) < 10
                  AND EXISTS (SELECT 1 FROM owner_control m JOIN owner_control r ON r.module=m.effective_owner
                    WHERE m.module=ui2_job_module(jobs.capability_id)
                      AND m.effective_owner=split_part({0}, '-', 1)
                      AND NOT m.drain_requested AND NOT r.drain_requested
                      AND (r.owner_instance IS NULL OR (r.owner_instance={0} AND r.owner_heartbeat_at>now()-interval '60 seconds')))
                  AND (capability_id <> ALL(ARRAY['cp_inventory_collect', 'pan_inventory_collect'])
                    OR (SELECT count(*) FROM jobs
                        WHERE state IN ('CLAIMED', 'EXECUTING')
                          AND capability_id = ANY(ARRAY['cp_inventory_collect', 'pan_inventory_collect'])) < 10)
                ORDER BY CASE
                    WHEN capability_id = 'diagnostic_read' THEN 0
                    WHEN capability_id = ANY(ARRAY['cp_inventory_collect', 'pan_inventory_collect']) THEN 1
                    WHEN capability_id = ANY(ARRAY['cp_configuration_collect', 'pan_configuration_collect']) THEN 2
                    WHEN capability_id = ANY(ARRAY['cp_discovery_enumerate', 'pan_discovery_enumerate']) THEN 3
                    ELSE 4
                END, submitted_at, job_id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING job_id, lease_epoch
            """;

    private final JobWindowPolicy windows;
    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqJobLeaseDao(TransactionBoundary transactionBoundary) {
        this(transactionBoundary, JobWindowPolicy.SYSTEM);
    }

    public JooqJobLeaseDao(TransactionBoundary transactionBoundary, JobWindowPolicy windows) {
        this.windows = windows;
        this.transactionBoundary = transactionBoundary;
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public Optional<ClaimedJobRow> claimNext(String workerId, List<String> eligibleCapabilityIds,
            Duration leaseDuration) {
        if (!windows.isOpen() || eligibleCapabilityIds.isEmpty()) {
            // Never issues the statement against an empty eligible set --
            // string_to_array('', ',') would produce a one-element array
            // containing the empty string, a silent near-miss rather than
            // "nothing is eligible."
            return Optional.empty();
        }
        String joined = String.join(",", eligibleCapabilityIds);
        return auditedTransactionBoundary.inTransaction("system:worker", "job_claim", dsl -> {
            if (!windows.isOpen()) return Optional.empty();
            Result<Record> rows = dsl.fetch(CLAIM_SQL, workerId, String.valueOf(leaseDuration.toSeconds()), joined,
                    windows.slotStart(windows.now()).plusMinutes(windows.windowMinutes()).toOffsetDateTime());
            return rows.stream().findFirst()
                    .map(row -> new ClaimedJobRow(row.get("job_id", String.class), row.get("lease_epoch", Long.class)));
        });
    }

    @Override
    public boolean cancellationRequested(String jobId, long leaseEpoch) {
        return transactionBoundary.inTransaction(db -> !db.fetch(
            "select job_id from jobs where job_id = {0} and lease_epoch = {1} and cancel_requested and state = 'EXECUTING'",
            jobId, leaseEpoch).isEmpty());
    }

    @Override
    public List<ClaimedJobRow> findExpiredCancellationRequests() {
        return transactionBoundary.inTransaction(db -> db.fetch(
            "select job_id, lease_epoch from jobs where state = 'EXECUTING' and cancel_requested and lease_expires_at < now() "
                + "and not exists(select 1 from failover_dispatch_intent i where i.job_id=jobs.job_id)")
            .stream().map(JooqJobLeaseDao::toClaimedJobRow).toList());
    }

    @Override
    public long retryBudgetUsed(String jobId, long epoch) {
        return transactionBoundary.inTransaction(db -> {
            var row = db.fetchOne("select greatest(0,lease_epoch-admission_deferrals) from jobs where job_id={0} and lease_epoch={1}", jobId, epoch);
            return row == null ? epoch : row.get(0, Long.class);
        });
    }

    @Override
    public boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration) {
        int updated = auditedTransactionBoundary.inTransaction("system:worker", "job_heartbeat", dsl -> dsl.execute(
                "update jobs set lease_expires_at = now() + ({0} || ' seconds')::interval, "
                        + "last_heartbeat_at = now() "
                        + "where job_id = {1} and lease_epoch = {2} and state in ('CLAIMED', 'EXECUTING') and ui2_job_owner_valid(job_id,lease_epoch)",
                String.valueOf(leaseDuration.toSeconds()), jobId, leaseEpoch));
        return updated == 1;
    }

    @Override
    public boolean transitionState(String jobId, long leaseEpoch, String expectedFromState, String toState,
            String actorFingerprint, String actionId) {
        return transitionState(jobId, leaseEpoch, expectedFromState, toState, actorFingerprint, actionId, null);
    }

    @Override
    public boolean transitionState(String jobId, long leaseEpoch, String expectedFromState, String toState,
            String actorFingerprint, String actionId, String terminalReason) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update jobs set state = {0}, outcome = case when {0} in ('COMPLETED', 'FAILED', 'REJECTED', "
                        + "'CANCELLED', 'OUTCOME_UNKNOWN', 'RECONCILED') then {0} else outcome end, "
                        + "terminal_reason = case when {0} in ('COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', "
                        + "'OUTCOME_UNKNOWN', 'RECONCILED') then {4} else terminal_reason end, "
                        + "finished_at = case when {0} in ('COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', "
                        + "'OUTCOME_UNKNOWN', 'RECONCILED') then now() else finished_at end "
                        + "where job_id = {1} and lease_epoch = {2} and state = {3} "
                        + "and (not cancel_requested or {0} = 'CANCELLED') "
                        + "and ({5} like 'job_reconcile%' or ui2_job_owner_valid(job_id,lease_epoch)) "
                        + "and ({5} not like 'job_reconcile%' or not exists "
                        + "(select 1 from failover_dispatch_intent i where i.job_id=jobs.job_id))",
                toState, jobId, leaseEpoch, expectedFromState, com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateWarnings.appendTo(
                        terminalReason == null ? toState : terminalReason), actionId));
        return updated == 1;
    }

    @Override
    public List<ClaimedJobRow> findExpiredWithNoAttempt() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select j.job_id, j.lease_epoch from jobs j where j.state = 'CLAIMED' and j.lease_expires_at < now() and not j.cancel_requested "
                        + "and not exists(select 1 from failover_dispatch_intent i where i.job_id=j.job_id) "
                        + "and not exists (select 1 from job_step_attempt a "
                        + "where a.job_id = j.job_id and a.lease_epoch = j.lease_epoch)")
                .stream().map(JooqJobLeaseDao::toClaimedJobRow).toList());
    }

    @Override
    public List<ClaimedJobRow> findExpiredAllBoundaryNo() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select j.job_id, j.lease_epoch from jobs j where j.state = 'EXECUTING' and j.lease_expires_at < now() and not j.cancel_requested "
                        + "and not exists(select 1 from failover_dispatch_intent i where i.job_id=j.job_id) "
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
                "select j.job_id, j.lease_epoch from jobs j where j.state = 'EXECUTING' and j.lease_expires_at < now() and not j.cancel_requested "
                        + "and not exists(select 1 from failover_dispatch_intent i where i.job_id=j.job_id) "
                        + "and exists (select 1 from job_step_attempt a "
                        + "where a.job_id = j.job_id and a.lease_epoch = j.lease_epoch "
                        + "and a.mutation_boundary_crossed = true and a.outcome is null)")
                .stream().map(JooqJobLeaseDao::toClaimedJobRow).toList());
    }

    private static ClaimedJobRow toClaimedJobRow(Record row) {
        return new ClaimedJobRow(row.get("job_id", String.class), row.get("lease_epoch", Long.class));
    }
}
