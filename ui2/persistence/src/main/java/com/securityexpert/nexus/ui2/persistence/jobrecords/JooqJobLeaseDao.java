package com.securityexpert.nexus.ui2.persistence.jobrecords;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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
 * one candidate-selection/mutation statement -- no separate {@code SELECT}
 * to find a candidate followed by a separate {@code UPDATE} (contract §8
 * test 1, AC-3).
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
                  AND CASE WHEN
                    (submitted_by_actor_fingerprint = {7} AND capability_id = ANY(string_to_array({6}, ',')))
                    OR (submitted_by_actor_fingerprint = {8} AND capability_id = {9})
                  THEN clock_timestamp() >= CAST({4} AS timestamptz)
                    AND clock_timestamp() < CAST({5} AS timestamptz)
                    AND submitted_at >= CAST({4} AS timestamptz)
                    AND submitted_at < CAST({5} AS timestamptz)
                  WHEN window_bypass_role IS NOT NULL THEN true
                  ELSE clock_timestamp() < CAST({3} AS timestamptz) END
                  AND capability_id = ANY(string_to_array({2}, ','))
                  AND job_type = ANY(string_to_array({2}, ','))
                  AND job_type = capability_id
                  AND (admission_not_before IS NULL OR admission_not_before <= now())
                  AND (jobs.target_device_id IS NULL OR NOT EXISTS (
                      SELECT 1 FROM jobs active
                      WHERE active.target_device_id = jobs.target_device_id
                        AND active.state IN ('CLAIMED', 'EXECUTING')
                        AND (active.lease_expires_at IS NULL OR active.lease_expires_at >= now())
                  ))
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
        // A stored-data worker (packet analysis) claims only stored-data job types, so no device job can ride on it.
        boolean storedData = JobWindowPolicy.storedDataOnly(eligibleCapabilityIds);
        if (eligibleCapabilityIds.isEmpty()) {
            // Never issues the statement against an empty eligible set --
            // string_to_array('', ',') would produce a one-element array
            // containing the empty string, a silent near-miss rather than
            // "nothing is eligible."
            return Optional.empty();
        }
        String joined = String.join(",", eligibleCapabilityIds);
        // Outside every window only an administrator's manual job (PO 2026-10-20, window_bypass_role) is claimable;
        // with none queued the claim statement is not issued at all.
        if (!storedData && !windows.isOpen() && !ScheduledBackupWindowPolicy.isOpen(windows.now())
                && !transactionBoundary.inTransaction(dsl -> bypassQueued(dsl, joined))) {
            return Optional.empty();
        }
        return auditedTransactionBoundary.inTransaction("system:worker", "job_claim", dsl -> {
            // Acquire before the claim statement so a waiting worker gets a fresh snapshot.
            dsl.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
            dsl.execute("SELECT pg_advisory_xact_lock(294611)");
            var now = windows.now();
            if (!storedData && !windows.isOpen(now) && !ScheduledBackupWindowPolicy.isOpen(now)
                    && !bypassQueued(dsl, joined)) return Optional.empty();
            var backupStart = ScheduledBackupWindowPolicy.start(now);
            // Same statement; for stored-data work the window end is simply never reached during this claim.
            var windowEnd = storedData ? now.atZone(JobWindowPolicy.ZONE_ID).plusDays(1)
                    : windows.slotStart(now).plusMinutes(windows.windowMinutes());
            Result<Record> rows = dsl.fetch(CLAIM_SQL, workerId, String.valueOf(leaseDuration.toSeconds()), joined,
                    windowEnd.toOffsetDateTime(),
                    backupStart.toOffsetDateTime(), backupStart.plusHours(1).toOffsetDateTime(),
                    String.join(",", ScheduledBackupWindowPolicy.FLEET_JOB_TYPES), ScheduledBackupWindowPolicy.ACTOR,
                    ScheduledBackupWindowPolicy.MDS_ACTOR, ScheduledBackupWindowPolicy.MDS_EXPORT);
            var claimed = rows.stream().findFirst()
                    .map(row -> new ClaimedJobRow(row.get("job_id", String.class), row.get("lease_epoch", Long.class)));
            // Argon review 2026-10-20 P2-1: an administrator's job submitted outside business hours carries no
            // acknowledgement; claimed once business hours have begun, it does not run. It ends REJECTED with
            // BUSINESS_HOURS_CONFIRM_REQUIRED in this same claim transaction (the established claim-check refusal), so
            // no executor ever sees it; the administrator submits it again and confirms.
            if (claimed.isPresent() && !storedData && windows.insideBusinessHours()
                    && dsl.execute(BUSINESS_HOURS_UNACKNOWLEDGED_REFUSAL_SQL, claimed.get().jobId(),
                            claimed.get().leaseEpoch(), String.join(",", BUSINESS_HOURS_CLAIM_EXEMPT)) == 1) {
                return Optional.empty();
            }
            return claimed;
        });
    }

    /**
     * Not refused at claim: a failover execution job, whose run's own fence ({@code mutationAdmission}) rechecks
     * business hours before its first device write and stops the run (and its job) through the run's lifecycle; and
     * stored-data work, which sends nothing to a device.
     */
    static final List<String> BUSINESS_HOURS_CLAIM_EXEMPT = Stream.concat(
            Stream.of("cp_cluster_failover", "pan_cluster_failover"), JobWindowPolicy.STORED_DATA_JOB_TYPES.stream())
            .sorted().toList();

    /**
     * The claim-time business-hours refusal of one just-claimed job ({0} job, {1} lease epoch): a window-bypass job
     * submitted outside business hours without the acknowledgement (V168 columns). Same terminal shape as every
     * claim-check refusal ({@code transitionState(..., CLAIMED, REJECTED, ...)}); audited by the claim transaction.
     */
    static final String BUSINESS_HOURS_UNACKNOWLEDGED_REFUSAL_SQL = "update jobs set state = 'REJECTED', "
            + "outcome = 'REJECTED', terminal_reason = '" + ManualAdmission.CONFIRM_CODE + "', finished_at = now() "
            + "where job_id = {0} and lease_epoch = {1} and state = 'CLAIMED' and window_bypass_role is not null "
            + "and submitted_in_business_hours is not true and business_hours_ack_at is null "
            + "and capability_id <> all(string_to_array({2}, ','))";

    /** A cheap read (partial index jobs_window_bypass_requested), never a claim: the claim stays the one statement. */
    static final String BYPASS_QUEUED_SQL = "select 1 as queued from jobs where state = 'REQUESTED' "
            + "and window_bypass_role is not null and capability_id = any(string_to_array({0}, ',')) limit 1";

    private static boolean bypassQueued(org.jooq.DSLContext dsl, String joined) {
        return dsl.fetchOne(BYPASS_QUEUED_SQL, joined) != null;
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
