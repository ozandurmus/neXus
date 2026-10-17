-- One-time repair: close readiness runs left open by a job that already ended.
-- Status: OPS SQL, not a Flyway migration. Apply only with Product Owner approval.
--
-- Why: on 2026-10-12 the queued readiness jobs (34 cp_failover_readiness, 16 pan_failover_readiness) were
-- cancelled directly in the database while the worker was stopped. Cancelling a job never closed its
-- failover_run, so 50 READINESS runs stayed PLANNED and every later readiness request for those units returned
-- RUN_ALREADY_ACTIVE. V162 closes such a run together with its job from now on, and readiness admission plus
-- the scheduler sweep close any older orphan; this file repairs the rows that exist before V162 is deployed.
--
-- What it changes: every failover_run with run_kind='READINESS', state not DONE/STOPPED, whose job is in a
-- terminal state, becomes state='STOPPED', outcome='UNKNOWN', message='JOB_ENDED_WITHOUT_RESULT',
-- finished_at = the job's finished_at. Nothing else: no execution run (run_kind='FAILOVER'), no job row, no
-- check result. The same statement is what the application runs (JooqCpFailoverRepository#closeOrphanReadiness).
-- Idempotent: a second run matches no row.
--
-- How to run (psql, as a role allowed to update failover_run):
--   dry run:  BEGIN; \i 2026_10_18_close_orphan_readiness_runs.sql  ROLLBACK;
--   apply:    BEGIN; \i 2026_10_18_close_orphan_readiness_runs.sql  COMMIT;
-- The file opens no transaction itself. Outside a transaction block the audit context below lasts for one
-- statement only, so the UPDATE fails with audit_context_missing and changes nothing.
-- Output is counts and states only; no names or addresses.

-- 1. Before: open readiness runs by vendor and job state.
SELECT r.vendor, j.state AS job_state, count(*) AS open_readiness_runs
FROM failover_run r JOIN jobs j ON j.job_id = r.job_id
WHERE r.run_kind = 'READINESS' AND r.state NOT IN ('DONE', 'STOPPED')
GROUP BY 1, 2 ORDER BY 1, 2;

-- 2. Audit context, transaction-local, set the way AuditedTransactionBoundary sets it.
SELECT set_config('app.actor_fingerprint', 'ops:readiness-orphan-repair', true);
SELECT set_config('app.action_id', 'failover_readiness_orphan_repair', true);

-- 3. Close the orphans; prints the closed count per vendor.
WITH closed AS (
    UPDATE failover_run r
    SET state = 'STOPPED', outcome = 'UNKNOWN', message = 'JOB_ENDED_WITHOUT_RESULT',
        finished_at = coalesce(j.finished_at, now())
    FROM jobs j
    WHERE j.job_id = r.job_id
      AND r.run_kind = 'READINESS'
      AND r.state NOT IN ('DONE', 'STOPPED')
      AND j.state IN ('COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', 'OUTCOME_UNKNOWN', 'RECONCILED')
    RETURNING r.vendor
)
SELECT vendor, count(*) AS closed_readiness_runs FROM closed GROUP BY 1 ORDER BY 1;

-- 4. After: must return no row. A row left here has a job that is still queued or running (not an orphan).
SELECT r.vendor, j.state AS job_state, count(*) AS still_open
FROM failover_run r JOIN jobs j ON j.job_id = r.job_id
WHERE r.run_kind = 'READINESS' AND r.state NOT IN ('DONE', 'STOPPED')
  AND j.state IN ('COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', 'OUTCOME_UNKNOWN', 'RECONCILED')
GROUP BY 1, 2;
