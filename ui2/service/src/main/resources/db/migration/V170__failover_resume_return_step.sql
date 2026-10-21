-- V170 -- Check Point failover: resume the return step of a stopped run
-- (docs/design/CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md, RATIFIED 2026-10-20).
-- No new command row and no new purpose: the resume sends the run's own gate cp_failover_up (V101, command string
-- unchanged) once, then the run's return confirmation and post-return checks. This migration adds:
--   1. failover_resume: one append-only request per run (single use), its approval, business-hours
--      acknowledgements, job and terminal state. The run row itself stays STOPPED (V136 FAILOVER_TERMINAL kept).
--   2. The fleet fence counts an admitted, non-terminal resume like a non-terminal FAILOVER run (both directions).
--   3. "At most once per run, counting the resume" (contract §3.2 item 6): UNIQUE(run_id, step) of the dispatch
--      intents is narrowed to intents with delivery<>'NOT_SENT', so a V140-proven NOT_SENT RETURNING intent does not
--      block the resume and "may have been sent" stays at most one per run and step.
--   4. A resume whose job ends without the worker closing it is stopped in the same transaction.
--   5. The two PO-approved gate-row field amendments (contract §8 Q2, Q2b), no other field changed.
-- Deploy after V169. Dry-run in BEGIN/ROLLBACK on the live database before rollout.

CREATE TABLE failover_resume (
    resume_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL UNIQUE REFERENCES failover_run(run_id),
    cluster_ref TEXT NOT NULL,
    vendor TEXT NOT NULL DEFAULT 'check_point' CHECK (vendor = 'check_point'),
    former_active_ref TEXT NOT NULL,
    peer_ref TEXT NOT NULL,
    target_member_ids JSONB NOT NULL,
    member_set_revision TEXT NOT NULL,
    expected_incident_id TEXT NOT NULL,
    policy TEXT NOT NULL CHECK (policy IN ('ADMIN_SINGLE', 'OPERATION_ADMIN_TWO_PERSON')),
    initiated_by TEXT NOT NULL,
    reason TEXT NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    requested_in_business_hours BOOLEAN NOT NULL,
    business_hours_ack_by TEXT,
    business_hours_ack_at TIMESTAMPTZ,
    approval_nonce TEXT NOT NULL UNIQUE,
    approved_by TEXT,
    approved_at TIMESTAMPTZ,
    approved_in_business_hours BOOLEAN,
    approval_ack_by TEXT,
    approval_ack_at TIMESTAMPTZ,
    job_id TEXT UNIQUE REFERENCES jobs(job_id),
    state TEXT NOT NULL CHECK (state IN ('PENDING_APPROVAL', 'QUEUED', 'READING_STATE', 'RETURNING',
        'POST_RETURN', 'DONE', 'STOPPED')),
    step TEXT NOT NULL,
    outcome TEXT,
    failed_check TEXT,
    message TEXT,
    mutation_possible BOOLEAN NOT NULL DEFAULT false,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CHECK (former_active_ref <> peer_ref),
    CHECK (jsonb_typeof(target_member_ids) = 'array' AND jsonb_array_length(target_member_ids) = 2
        AND target_member_ids @> jsonb_build_array(former_active_ref)
        AND target_member_ids @> jsonb_build_array(peer_ref)),
    CHECK (expires_at > requested_at),
    CHECK ((business_hours_ack_by IS NULL) = (business_hours_ack_at IS NULL)),
    CHECK (NOT requested_in_business_hours OR business_hours_ack_by = initiated_by),
    CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CHECK (approved_by IS NULL OR (policy = 'ADMIN_SINGLE' AND approved_by = initiated_by)
        OR (policy = 'OPERATION_ADMIN_TWO_PERSON' AND approved_by <> initiated_by)),
    CHECK ((approval_ack_by IS NULL) = (approval_ack_at IS NULL)),
    CHECK (approved_in_business_hours IS NULL OR policy = 'OPERATION_ADMIN_TWO_PERSON'),
    CHECK (NOT coalesce(approved_in_business_hours, false) OR approval_ack_by = approved_by),
    CHECK (state IN ('PENDING_APPROVAL', 'STOPPED') OR (approved_by IS NOT NULL AND job_id IS NOT NULL)),
    CHECK ((state IN ('DONE', 'STOPPED')) = (finished_at IS NOT NULL))
);
-- The fleet fence: at most one admitted, non-terminal resume anywhere (a pending request reserves nothing).
CREATE UNIQUE INDEX uq_failover_resume_fleet ON failover_resume ((1))
    WHERE state IN ('QUEUED', 'READING_STATE', 'RETURNING', 'POST_RETURN');

CREATE TRIGGER trg_audit_failover_resume AFTER INSERT OR UPDATE OR DELETE ON failover_resume
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('resume_id');
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('failover_resume', 'reason', 2, 'operator free text (resume reason)'),
    ('failover_resume', 'message', 2, 'worker step message, as failover_run.message');
GRANT SELECT, INSERT, UPDATE ON failover_resume TO ui2_app;

-- Append-only like V136/V137: bindings never change, each decision is written once, terminal is final.
CREATE FUNCTION ui2_failover_resume_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(136, 1);
 IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'RESUME_HISTORY_IMMUTABLE'; END IF;
 IF TG_OP = 'INSERT' THEN
  IF NEW.state <> 'PENDING_APPROVAL' AND NEW.state <> 'QUEUED' THEN RAISE EXCEPTION 'RESUME_HISTORY_IMMUTABLE'; END IF;
  IF NOT EXISTS (SELECT 1 FROM failover_run r WHERE r.run_id = NEW.run_id AND r.run_kind = 'FAILOVER'
      AND r.vendor = 'check_point' AND r.vs_id IS NULL AND r.state = 'STOPPED'
      AND r.cluster_ref = NEW.cluster_ref AND r.target_member_ids @> NEW.target_member_ids
      AND NEW.target_member_ids @> r.target_member_ids) THEN
   RAISE EXCEPTION 'RESUME_RUN_NOT_ELIGIBLE';
  END IF;
 END IF;
 IF TG_OP = 'UPDATE' THEN
  IF OLD.state IN ('DONE', 'STOPPED') THEN RAISE EXCEPTION 'RESUME_TERMINAL'; END IF;
  IF ROW(NEW.resume_id, NEW.run_id, NEW.cluster_ref, NEW.vendor, NEW.former_active_ref, NEW.peer_ref,
         NEW.target_member_ids, NEW.member_set_revision, NEW.expected_incident_id, NEW.policy, NEW.initiated_by,
         NEW.reason, NEW.requested_at, NEW.expires_at, NEW.requested_in_business_hours, NEW.business_hours_ack_by,
         NEW.business_hours_ack_at, NEW.approval_nonce)
     IS DISTINCT FROM
     ROW(OLD.resume_id, OLD.run_id, OLD.cluster_ref, OLD.vendor, OLD.former_active_ref, OLD.peer_ref,
         OLD.target_member_ids, OLD.member_set_revision, OLD.expected_incident_id, OLD.policy, OLD.initiated_by,
         OLD.reason, OLD.requested_at, OLD.expires_at, OLD.requested_in_business_hours, OLD.business_hours_ack_by,
         OLD.business_hours_ack_at, OLD.approval_nonce)
     OR (OLD.approved_by IS NOT NULL AND ROW(NEW.approved_by, NEW.approved_at, NEW.approved_in_business_hours,
         NEW.approval_ack_by, NEW.approval_ack_at) IS DISTINCT FROM ROW(OLD.approved_by, OLD.approved_at,
         OLD.approved_in_business_hours, OLD.approval_ack_by, OLD.approval_ack_at))
     OR (OLD.job_id IS NOT NULL AND NEW.job_id IS DISTINCT FROM OLD.job_id)
     OR (OLD.started_at IS NOT NULL AND NEW.started_at IS DISTINCT FROM OLD.started_at)
     OR (OLD.mutation_possible AND NOT NEW.mutation_possible)
     OR (OLD.state <> 'PENDING_APPROVAL' AND NEW.state = 'PENDING_APPROVAL') THEN
   RAISE EXCEPTION 'RESUME_HISTORY_IMMUTABLE';
  END IF;
 END IF;
 IF NEW.state IN ('QUEUED', 'READING_STATE', 'RETURNING', 'POST_RETURN') AND EXISTS (
     SELECT 1 FROM failover_run r WHERE r.run_kind = 'FAILOVER' AND r.state NOT IN ('DONE', 'STOPPED')) THEN
  RAISE EXCEPTION 'FLEET_MUTATION_ACTIVE';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_resume_guard BEFORE INSERT OR UPDATE OR DELETE ON failover_resume
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_resume_guard();

-- The other direction of the fleet fence: no FAILOVER run is admitted while a resume is in flight.
CREATE FUNCTION ui2_failover_run_resume_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.run_kind = 'FAILOVER' AND NEW.state NOT IN ('DONE', 'STOPPED') THEN
  PERFORM pg_advisory_xact_lock(136, 1);
  IF EXISTS (SELECT 1 FROM failover_resume s WHERE s.state IN ('QUEUED', 'READING_STATE', 'RETURNING', 'POST_RETURN')) THEN
   RAISE EXCEPTION 'FLEET_MUTATION_ACTIVE';
  END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_run_resume_fence BEFORE INSERT OR UPDATE ON failover_run
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_run_resume_fence();

-- At most one possibly-sent intent per run and step, summed over the run and its resume.
ALTER TABLE failover_dispatch_intent DROP CONSTRAINT failover_dispatch_intent_run_id_step_key;
CREATE UNIQUE INDEX uq_failover_dispatch_intent_step_sent ON failover_dispatch_intent (run_id, step)
    WHERE delivery <> 'NOT_SENT';

-- A resume job that ends (failed, cancelled, rejected, outcome unknown, reconciled) closes its resume in the same
-- transaction; the run's incident stays open. Mirrors V136 ui2_failover_job_failure for the run.
CREATE FUNCTION ui2_failover_resume_job_end() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.state IN ('COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', 'OUTCOME_UNKNOWN', 'RECONCILED')
    AND NEW.state IS DISTINCT FROM OLD.state THEN
  PERFORM pg_advisory_xact_lock(136, 1);
  UPDATE failover_resume SET state = 'STOPPED', outcome = NEW.state, message = 'RESUME_JOB_ENDED',
      finished_at = clock_timestamp()
   WHERE job_id = NEW.job_id AND state NOT IN ('DONE', 'STOPPED');
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_resume_job_end AFTER UPDATE OF state ON jobs
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_resume_job_end();

-- Contract §8 Q2 and Q2b (RATIFIED 2026-10-20): the only two field amendments.
SELECT set_config('app.actor_fingerprint', 'migration:V170_failover_resume_return_step', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);
UPDATE gate_registry SET session_reuse_rule =
    'one SSH session per member per run, and one new session per member for its single resumed return; commands serial'
WHERE gate_id = 'cp_failover_up';
UPDATE gate_registry SET max_frequency = max_frequency
    || '; a resumed return adds one read per member before UP and at most 20 polls every 3 s after UP'
WHERE gate_id = 'cp_inventory_cphaprob_stat'
  AND max_frequency NOT LIKE '%a resumed return adds one read per member before UP%';
