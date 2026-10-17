-- A readiness run lives only as long as its job. When the job reaches a terminal state while the run is
-- still open (cancelled, rejected, failed, reconciled or completed before the worker recorded a result),
-- the run is closed in the same transaction as that job transition: STOPPED (it ended before its procedure
-- finished), outcome UNKNOWN (no result), message JOB_ENDED_WITHOUT_RESULT. This covers every writer of
-- jobs.state, including an operator's direct SQL; before V162 the 2026-10-12 cancellation of 50 queued
-- readiness jobs left their runs PLANNED and every later readiness request returned RUN_ALREADY_ACTIVE.
-- Readiness only: execution runs (run_kind='FAILOVER') keep their V136/V140 incident semantics.
-- The audit row for the run carries the job transition's own actor and action (same transaction).
-- No device commands are added and this migration writes no rows.
CREATE FUNCTION ui2_failover_readiness_job_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.state IN ('COMPLETED','FAILED','REJECTED','CANCELLED','OUTCOME_UNKNOWN','RECONCILED')
    AND EXISTS (SELECT 1 FROM failover_run r WHERE r.job_id=NEW.job_id AND r.run_kind='READINESS'
                AND r.state NOT IN ('DONE','STOPPED')) THEN
  UPDATE failover_run SET state='STOPPED',outcome='UNKNOWN',message='JOB_ENDED_WITHOUT_RESULT',finished_at=now()
   WHERE job_id=NEW.job_id AND run_kind='READINESS' AND state NOT IN ('DONE','STOPPED');
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_readiness_job_terminal AFTER UPDATE OF state ON jobs
 FOR EACH ROW WHEN (NEW.state IS DISTINCT FROM OLD.state)
 EXECUTE FUNCTION ui2_failover_readiness_job_terminal();
