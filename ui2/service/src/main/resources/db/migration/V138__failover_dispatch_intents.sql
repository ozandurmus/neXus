-- A committed reservation is never a retry ticket. No device command is added.
CREATE TABLE failover_dispatch_intent (
    nonce TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES failover_run(run_id),
    attempt_id TEXT NOT NULL UNIQUE REFERENCES job_step_attempt(attempt_id),
    job_id TEXT NOT NULL REFERENCES jobs(job_id),
    lease_epoch BIGINT NOT NULL,
    owner_generation BIGINT NOT NULL,
    owner_instance TEXT NOT NULL,
    step TEXT NOT NULL CHECK (step IN ('FAILING_OVER','RETURNING')),
    member_ref TEXT NOT NULL,
    gate_id TEXT NOT NULL,
    dispatch_claimed BOOLEAN NOT NULL DEFAULT false,
    delivery TEXT NOT NULL DEFAULT 'MAY_HAVE_BEEN_SENT'
        CHECK (delivery IN ('MAY_HAVE_BEEN_SENT','REPLY_RECEIVED','UNKNOWN')),
    observation TEXT NOT NULL DEFAULT 'NOT_OBSERVED'
        CHECK (observation IN ('NOT_OBSERVED','CONFIRMED','OUTCOME_UNKNOWN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(run_id,step)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON failover_dispatch_intent TO ui2_app;
CREATE TRIGGER failover_dispatch_audit AFTER INSERT OR UPDATE OR DELETE ON failover_dispatch_intent
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('nonce');

CREATE FUNCTION ui2_failover_dispatch_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'DISPATCH_HISTORY_IMMUTABLE'; END IF;
 IF ROW(NEW.nonce,NEW.run_id,NEW.attempt_id,NEW.job_id,NEW.lease_epoch,NEW.owner_generation,
        NEW.owner_instance,NEW.step,NEW.member_ref,NEW.gate_id,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.nonce,OLD.run_id,OLD.attempt_id,OLD.job_id,OLD.lease_epoch,OLD.owner_generation,
        OLD.owner_instance,OLD.step,OLD.member_ref,OLD.gate_id,OLD.created_at)
    OR (OLD.dispatch_claimed AND NOT NEW.dispatch_claimed)
    OR (OLD.delivery<>'MAY_HAVE_BEEN_SENT' AND NEW.delivery IS DISTINCT FROM OLD.delivery)
    OR (OLD.observation<>'NOT_OBSERVED' AND NEW.observation IS DISTINCT FROM OLD.observation) THEN
  RAISE EXCEPTION 'DISPATCH_HISTORY_IMMUTABLE';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_dispatch_guard BEFORE UPDATE OR DELETE ON failover_dispatch_intent
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_dispatch_guard();

-- Even an accidentally terminal run cannot bypass an unresolved dispatch reservation.
-- Reconciliation records uncertainty and an incident before this fleet fence clears.
CREATE FUNCTION ui2_failover_dispatch_admission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(136, 1);
 IF NEW.run_kind='FAILOVER' AND NEW.state NOT IN ('DONE','STOPPED') AND EXISTS (
     SELECT 1 FROM failover_dispatch_intent i WHERE i.run_id<>NEW.run_id
      AND (i.observation='NOT_OBSERVED' OR (i.observation='OUTCOME_UNKNOWN' AND EXISTS (
       SELECT 1 FROM failover_quarantine q WHERE q.execution_id=i.run_id AND q.active)))) THEN
  RAISE EXCEPTION 'UNRESOLVED_DISPATCH';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_dispatch_admission BEFORE INSERT OR UPDATE ON failover_run
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_dispatch_admission();
