-- V174 -- failover pilot follow-up (2026-10-21/22): a quarantined fleet failover task lease bound to a settled run
-- must never block a module drain. V174 is reserved by another branch; deploy V174 before V174.
--
-- What happened: a CP failover run STOPPED with mutation_possible; its job went OUTCOME_UNKNOWN, so the worker kept the
-- 'fleet.failover.execution' task lease (EndpointRuntime.close releases it only when the outcome is certain) and the
-- lease expired into QUARANTINED. The PO released the incident, but V131 releases a quarantine only when its owner
-- instance is gone; the owning worker was alive, so the general drain barrier (tools/delivery/module_deploy.py) waited
-- forever and the window's jobs stayed REQUESTED.
--
-- Rule added here: a QUARANTINED (or expired LEASED) fleet failover lease is released, audited, when the job it is
-- bound to is settled: the job is terminal, every failover run and resume of that job is terminal, none of their
-- incidents is still active and no write of theirs is still unresolved (sent, never observed). The incident release
-- is the operator's attestation that the outcome was established out of band; this release follows it. Owner-gone
-- release (V131) is unchanged. No device command is added.

-- 1. Settled: nothing about the bound job can still need the estate-wide failover fence.
CREATE FUNCTION ui2_failover_lease_settled(settled_job TEXT) RETURNS BOOLEAN
LANGUAGE SQL STABLE AS $$
 SELECT settled_job IS NOT NULL
 AND EXISTS (SELECT 1 FROM jobs j WHERE j.job_id=settled_job
   AND j.state NOT IN ('REQUESTED','CLAIMED','EXECUTING'))
 AND (EXISTS (SELECT 1 FROM failover_run r WHERE r.job_id=settled_job)
   OR EXISTS (SELECT 1 FROM failover_resume s WHERE s.job_id=settled_job))
 AND NOT EXISTS (SELECT 1 FROM failover_run r WHERE r.job_id=settled_job AND r.state NOT IN ('DONE','STOPPED'))
 AND NOT EXISTS (SELECT 1 FROM failover_resume s WHERE s.job_id=settled_job
   AND s.state NOT IN ('DONE','STOPPED','EXPIRED'))
 AND NOT EXISTS (SELECT 1 FROM failover_quarantine q WHERE q.active AND q.execution_id IN (
   SELECT r.run_id FROM failover_run r WHERE r.job_id=settled_job
   UNION SELECT s.run_id FROM failover_resume s WHERE s.job_id=settled_job))
 AND NOT EXISTS (SELECT 1 FROM failover_dispatch_intent i
   WHERE i.delivery<>'NOT_SENT' AND i.observation='NOT_OBSERVED' AND (i.job_id=settled_job OR i.run_id IN (
   SELECT r.run_id FROM failover_run r WHERE r.job_id=settled_job
   UNION SELECT s.run_id FROM failover_resume s WHERE s.job_id=settled_job)))
$$;

-- 2. The audit trigger also accepts the settled-run release. It is the one release a person causes (the incident
--    release), so it records that session's actor; it applies to the fleet failover task lease only. Every other
--    release still requires a system actor, exactly as in V131. Endpoints, sessions, instances and tokens are
--    still never copied into the audit log.
CREATE OR REPLACE FUNCTION ui2_audit_quarantine_release() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
 actor TEXT := current_setting('app.actor_fingerprint',true);
 reason TEXT := current_setting('app.action_id',true);
BEGIN
 IF reason IS NOT DISTINCT FROM 'QUARANTINE_RELEASED_RUN_SETTLED' AND TG_TABLE_NAME='runtime_task_lease'
    AND to_jsonb(OLD)->>'task_key'='fleet.failover.execution' THEN
  IF actor IS NULL OR btrim(actor)='' THEN
   RAISE EXCEPTION 'quarantine_release_audit_context_missing';
  END IF;
 ELSE
  IF actor IS NULL OR actor NOT IN ('system:worker','system:release') THEN
   RAISE EXCEPTION 'quarantine_release_audit_context_missing';
  END IF;
  IF reason IS DISTINCT FROM 'QUARANTINE_RELEASED_OWNER_GONE' THEN
   reason := 'QUARANTINE_RELEASED_TRANSPORT_CLOSED';
  END IF;
 END IF;
 INSERT INTO public.audit_log(table_name,row_pk,operation,actor_fingerprint,action_id,before_state,correlation_run_id)
 VALUES (TG_TABLE_NAME,to_jsonb(OLD)->>TG_ARGV[0],'DELETE',actor,reason,
   jsonb_build_object('state','QUARANTINED','owner_role',OLD.owner_role,'reason',reason),
   current_setting('app.correlation_run_id',true));
 RETURN OLD;
END;
$$;
REVOKE ALL ON FUNCTION ui2_audit_quarantine_release() FROM PUBLIC;

-- 3. Release the settled fleet failover leases: those bound to one job (the incident release), or all (NULL, the
--    sweep). An expired LEASED row is quarantined first so that every release goes through the audit trigger.
CREATE FUNCTION ui2_release_settled_failover_leases(p_job TEXT) RETURNS INTEGER
LANGUAGE plpgsql AS $$
DECLARE released INTEGER; previous_action TEXT;
BEGIN
 UPDATE runtime_task_lease SET state='QUARANTINED'
  WHERE task_key='fleet.failover.execution' AND state='LEASED' AND expires_at<=now()
  AND bound_job_id IS NOT NULL AND (p_job IS NULL OR bound_job_id=p_job)
  AND ui2_failover_lease_settled(bound_job_id);
 previous_action := current_setting('app.action_id',true);
 PERFORM set_config('app.action_id','QUARANTINE_RELEASED_RUN_SETTLED',true);
 DELETE FROM runtime_task_lease
  WHERE task_key='fleet.failover.execution' AND state='QUARANTINED'
  AND bound_job_id IS NOT NULL AND (p_job IS NULL OR bound_job_id=p_job)
  AND ui2_failover_lease_settled(bound_job_id);
 GET DIAGNOSTICS released = ROW_COUNT;
 PERFORM set_config('app.action_id',coalesce(previous_action,''),true);
 RETURN released;
END;
$$;

-- 4. The sweep every heartbeat, admission and drain barrier already runs (V131) now also releases settled fleet
--    leases, after the owner-gone evaluation so that evaluation is exactly as before. Otherwise identical to V131.
CREATE OR REPLACE FUNCTION ui2_release_orphan_quarantines() RETURNS INTEGER
LANGUAGE plpgsql AS $$
DECLARE released INTEGER; tasks INTEGER; previous_action TEXT;
BEGIN
 -- Same ordering as task/claim admission: ownership first, endpoint capacity second.
 PERFORM pg_advisory_xact_lock(294611);
 PERFORM pg_advisory_xact_lock(294612);
 UPDATE endpoint_admission SET state='QUARANTINED' WHERE state='LEASED' AND expires_at<=now();
 UPDATE runtime_task_lease SET state='QUARANTINED' WHERE state='LEASED' AND expires_at<=now();
 previous_action := current_setting('app.action_id',true);
 PERFORM set_config('app.action_id','QUARANTINE_RELEASED_OWNER_GONE',true);
 -- Evaluate both tables before deleting either: a recent task heartbeat protects endpoints too.
 WITH endpoints AS MATERIALIZED (
  SELECT request_id FROM endpoint_admission
   WHERE state='QUARANTINED' AND ui2_quarantine_owner_gone(owner_instance)
 ), task_rows AS MATERIALIZED (
  SELECT task_key FROM runtime_task_lease
   WHERE state='QUARANTINED' AND ui2_quarantine_owner_gone(owner_instance)
 ), deleted_endpoints AS (
  DELETE FROM endpoint_admission WHERE request_id IN (SELECT request_id FROM endpoints) RETURNING request_id
 ), deleted_tasks AS (
  DELETE FROM runtime_task_lease WHERE task_key IN (SELECT task_key FROM task_rows) RETURNING task_key
 ) SELECT (SELECT count(*) FROM deleted_endpoints),(SELECT count(*) FROM deleted_tasks) INTO released,tasks;
 PERFORM set_config('app.action_id',coalesce(previous_action,''),true);
 tasks := tasks + ui2_release_settled_failover_leases(NULL);
 RETURN released+tasks;
END;
$$;

REVOKE ALL ON FUNCTION ui2_failover_lease_settled(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ui2_release_settled_failover_leases(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ui2_failover_lease_settled(TEXT),ui2_release_settled_failover_leases(TEXT) TO ui2_app;
