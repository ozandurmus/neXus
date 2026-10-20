-- V169 -- failover pilot P0 closure (docs/design/PO_DECISION_RECORD_2026_10_20_FAILOVER_PILOT_P0.md).
-- V168 is reserved by lane/admin-window-bypass; deploy V168 before V169. No device command is added.

-- 1. Readiness/failover check 15 "Recovery mode": the ClusterXL recovery suffix that `cphaprob stat`
--    (gate cp_inventory_cphaprob_stat, already issued as check 1) prints on its "Cluster Mode:" line.
--    A parse-scope extension of an approved read; same command, session, timeout and frequency.
ALTER TABLE failover_check_result DROP CONSTRAINT failover_check_result_check_no_check;
ALTER TABLE failover_check_result ADD CONSTRAINT failover_check_result_check_no_check
    CHECK (check_no IN (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15));

-- 2. An incident release is audited. failover_incident_guard (V136) admits only the release update, so
--    this trigger records exactly the release: who, when and the reason (the reason is redacted below).
--    Inserts stay unaudited here: they are written by the run guard inside the audited worker transaction.
CREATE TRIGGER trg_audit_failover_quarantine
    AFTER UPDATE ON failover_quarantine
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('execution_id');
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('failover_quarantine', 'review_notes', 2, 'operator free text (incident release reason)');

-- 3. Recovery-contract review §9: a run stopped by the mutation switch at the UP step, after a DOWN that was
--    sent, left the member down with no incident. The NOT_SENT exemption now applies only when no write of
--    the run was ever sent (every dispatch intent of the run is NOT_SENT); otherwise the incident is created
--    exactly like any stop after the first write. Otherwise identical to V140.
CREATE OR REPLACE FUNCTION ui2_failover_run_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
 IF NEW.run_kind='FAILOVER' AND NEW.state='STOPPED' AND NEW.mutation_possible
    AND NOT (NEW.outcome IS NOT DISTINCT FROM 'MUTATION_DISABLED_BEFORE_SEND' AND EXISTS (
      SELECT 1 FROM failover_dispatch_intent i WHERE i.run_id=NEW.run_id AND i.delivery='NOT_SENT')
      AND NOT EXISTS (SELECT 1 FROM failover_dispatch_intent i WHERE i.run_id=NEW.run_id
        AND i.delivery<>'NOT_SENT')) THEN
  INSERT INTO failover_quarantine(cluster_ref,execution_id,reason,quarantined_member_ids)
   VALUES(NEW.cluster_ref,NEW.run_id,'MUTATION_STOPPED',NEW.target_member_ids)
   ON CONFLICT(cluster_ref,execution_id) DO NOTHING;
 END IF;
 RETURN NEW;
END;
$$;
