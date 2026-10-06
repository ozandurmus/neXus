-- PR 5 allocation; V136 may be renumbered at merge. No device commands are added.
-- Retain every incident, including released incidents; re-engagement is idempotent.
ALTER TABLE failover_quarantine DROP CONSTRAINT failover_quarantine_pkey;
ALTER TABLE failover_quarantine ADD PRIMARY KEY (cluster_ref, execution_id);

ALTER TABLE failover_run ADD COLUMN target_member_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE failover_run ADD COLUMN mutation_possible BOOLEAN NOT NULL DEFAULT false;

-- Conservative first-release ceiling: queued/scheduled mutations also reserve the fleet.
-- A legacy active mutation requires explicit reconciliation before this index can be installed.
CREATE UNIQUE INDEX uq_failover_fleet_mutation ON failover_run ((1))
 WHERE run_kind='FAILOVER' AND state NOT IN ('DONE','STOPPED');

CREATE FUNCTION ui2_failover_incident_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(136, 1);
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'INCIDENT_HISTORY_IMMUTABLE'; END IF;
 IF TG_OP='UPDATE' THEN
  IF NOT OLD.active OR NEW.active
    OR ROW(NEW.cluster_ref,NEW.execution_id,NEW.reason,NEW.quarantined_member_ids,NEW.quarantined_at)
      IS DISTINCT FROM ROW(OLD.cluster_ref,OLD.execution_id,OLD.reason,OLD.quarantined_member_ids,OLD.quarantined_at)
    OR NEW.acknowledged_at IS NULL OR NEW.acknowledged_by IS NULL
    OR NEW.second_approver_id IS NULL OR NEW.review_notes IS NULL THEN
   RAISE EXCEPTION 'INCIDENT_HISTORY_IMMUTABLE';
  END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_incident_guard BEFORE INSERT OR UPDATE OR DELETE ON failover_quarantine
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_incident_guard();

CREATE FUNCTION ui2_failover_run_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(136, 1);
 IF TG_OP='UPDATE' THEN
  IF ROW(NEW.cluster_ref,NEW.vs_id,NEW.vendor,NEW.run_kind,NEW.target_member_ids)
     IS DISTINCT FROM ROW(OLD.cluster_ref,OLD.vs_id,OLD.vendor,OLD.run_kind,OLD.target_member_ids)
     OR (OLD.mutation_possible AND NOT NEW.mutation_possible) THEN
   RAISE EXCEPTION 'FAILOVER_TARGET_IMMUTABLE';
  END IF;
  IF OLD.state IN ('DONE','STOPPED') AND NEW.state NOT IN ('DONE','STOPPED') THEN
   RAISE EXCEPTION 'FAILOVER_TERMINAL';
  END IF;
 END IF;
 IF NEW.run_kind='FAILOVER' AND NEW.state NOT IN ('DONE','STOPPED') THEN
  IF jsonb_typeof(NEW.target_member_ids)<>'array' OR jsonb_array_length(NEW.target_member_ids)<>2
     OR jsonb_typeof(NEW.target_member_ids->0)<>'string' OR jsonb_typeof(NEW.target_member_ids->1)<>'string'
     OR NEW.target_member_ids->0 = NEW.target_member_ids->1 THEN RAISE EXCEPTION 'TARGET_NOT_BOUND'; END IF;
  IF EXISTS (SELECT 1 FROM failover_quarantine q WHERE q.active AND
      (q.cluster_ref=NEW.cluster_ref OR EXISTS (
       SELECT 1 FROM jsonb_array_elements_text(q.quarantined_member_ids) AS m(member_id)
       WHERE NEW.target_member_ids @> jsonb_build_array(m.member_id)))) THEN
   RAISE EXCEPTION 'OPEN_INCIDENT';
  END IF;
 END IF;
 IF NEW.run_kind='FAILOVER' AND NEW.state='STOPPED' AND NEW.mutation_possible THEN
  INSERT INTO failover_quarantine(cluster_ref,execution_id,reason,quarantined_member_ids)
   VALUES(NEW.cluster_ref,NEW.run_id,'MUTATION_STOPPED',NEW.target_member_ids)
   ON CONFLICT(cluster_ref,execution_id) DO NOTHING;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_run_guard BEFORE INSERT OR UPDATE ON failover_run
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_run_guard();

-- Both the JdbcTemplate service store and worker transitions use this same incident table.
GRANT SELECT, INSERT, UPDATE, DELETE ON failover_quarantine TO ui2_app;

CREATE FUNCTION ui2_release_failover_incident(unit_ref TEXT, incident_ref TEXT,
 operator_ref TEXT, approver_ref TEXT, notes TEXT) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(136, 1);
 UPDATE failover_quarantine SET active=false, acknowledged_at=now(), acknowledged_by=operator_ref,
  second_approver_id=approver_ref, review_notes=notes
  WHERE cluster_ref=unit_ref AND execution_id=incident_ref AND active;
 RETURN FOUND;
END;
$$;
REVOKE ALL ON FUNCTION ui2_release_failover_incident(TEXT,TEXT,TEXT,TEXT,TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ui2_release_failover_incident(TEXT,TEXT,TEXT,TEXT,TEXT) TO ui2_app;

-- Failure recorded by the job engine (including a worker exiting after a possible send)
-- must create the same incident in that transaction, even if worker cleanup never ran.
CREATE FUNCTION ui2_failover_job_failure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.state IN ('FAILED','OUTCOME_UNKNOWN') AND NEW.state IS DISTINCT FROM OLD.state THEN
  PERFORM pg_advisory_xact_lock(136, 1);
  UPDATE failover_run SET state='STOPPED',outcome=NEW.state,message='MUTATION_JOB_FAILED',finished_at=now()
   WHERE job_id=NEW.job_id AND run_kind='FAILOVER' AND mutation_possible AND state<>'STOPPED';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_job_failure AFTER UPDATE OF state ON jobs
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_job_failure();
