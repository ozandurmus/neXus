-- A switch cancellation is terminal only when no transport invocation began.
ALTER TABLE failover_dispatch_intent DROP CONSTRAINT failover_dispatch_intent_delivery_check;
ALTER TABLE failover_dispatch_intent ADD CONSTRAINT failover_dispatch_intent_delivery_check
    CHECK (delivery IN ('MAY_HAVE_BEEN_SENT','REPLY_RECEIVED','UNKNOWN','NOT_SENT'));
ALTER TABLE failover_dispatch_intent ADD CONSTRAINT failover_dispatch_intent_not_sent_observation_check
    CHECK (delivery<>'NOT_SENT' OR observation='NOT_OBSERVED');

CREATE OR REPLACE FUNCTION ui2_failover_dispatch_admission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE incident_dispatch_ref TEXT;
BEGIN
 PERFORM pg_advisory_xact_lock(136, 1);
 -- This trigger runs before failover_run_guard: preserve its more actionable incident cause.
 IF NEW.run_kind='FAILOVER' AND NEW.state NOT IN ('DONE','STOPPED') THEN
  SELECT i.nonce INTO incident_dispatch_ref FROM failover_quarantine q
   LEFT JOIN failover_dispatch_intent i ON i.run_id=q.execution_id AND i.observation<>'CONFIRMED'
   WHERE q.active AND (q.cluster_ref=NEW.cluster_ref OR EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(q.quarantined_member_ids) AS m(member_id)
    WHERE NEW.target_member_ids @> jsonb_build_array(m.member_id)))
   ORDER BY i.nonce NULLS LAST,q.execution_id LIMIT 1;
  IF FOUND THEN
   RAISE EXCEPTION 'OPEN_INCIDENT'
    USING DETAIL=coalesce('dispatchRef=' || incident_dispatch_ref,'OPEN_INCIDENT');
  END IF;
 END IF;
 IF NEW.run_kind='FAILOVER' AND NEW.state NOT IN ('DONE','STOPPED') AND EXISTS (
     SELECT 1 FROM failover_dispatch_intent i WHERE i.run_id<>NEW.run_id AND i.delivery<>'NOT_SENT'
      AND (i.observation='NOT_OBSERVED' OR (i.observation='OUTCOME_UNKNOWN' AND EXISTS (
       SELECT 1 FROM failover_quarantine q WHERE q.execution_id=i.run_id AND q.active)))) THEN
  RAISE EXCEPTION 'UNRESOLVED_DISPATCH';
 END IF;
 RETURN NEW;
END;
$$;

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
        AND i.delivery<>'NOT_SENT' AND i.observation<>'CONFIRMED')) THEN
  INSERT INTO failover_quarantine(cluster_ref,execution_id,reason,quarantined_member_ids)
   VALUES(NEW.cluster_ref,NEW.run_id,'MUTATION_STOPPED',NEW.target_member_ids)
   ON CONFLICT(cluster_ref,execution_id) DO NOTHING;
 END IF;
 RETURN NEW;
END;
$$;
