-- Retain each process's last heartbeat even when deployment replaces/clears its module owner.
CREATE TABLE runtime_instance_heartbeat (
 owner_instance TEXT PRIMARY KEY,
 heartbeat_at TIMESTAMPTZ NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON runtime_instance_heartbeat TO ui2_app;
INSERT INTO runtime_instance_heartbeat(owner_instance,heartbeat_at)
 SELECT owner_instance,max(owner_heartbeat_at) FROM module_runtime_control
 WHERE owner_instance IS NOT NULL AND owner_heartbeat_at IS NOT NULL GROUP BY owner_instance;

CREATE FUNCTION ui2_record_instance_heartbeat() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO runtime_instance_heartbeat(owner_instance,heartbeat_at)
 SELECT instance,max(heartbeat) FROM (VALUES
  (OLD.owner_instance,OLD.owner_heartbeat_at),(NEW.owner_instance,NEW.owner_heartbeat_at)
 ) observations(instance,heartbeat)
 WHERE instance IS NOT NULL AND heartbeat IS NOT NULL GROUP BY instance
 ON CONFLICT(owner_instance) DO UPDATE
 SET heartbeat_at=greatest(runtime_instance_heartbeat.heartbeat_at,EXCLUDED.heartbeat_at);
 RETURN NEW;
END;
$$;
CREATE TRIGGER module_instance_heartbeat AFTER UPDATE OF owner_instance,owner_heartbeat_at ON module_runtime_control
 FOR EACH ROW EXECUTE FUNCTION ui2_record_instance_heartbeat();

-- TTL is not closure evidence. Reconcile only an unowned instance with no recent liveness.
CREATE FUNCTION ui2_quarantine_owner_gone(instance TEXT) RETURNS BOOLEAN
LANGUAGE SQL STABLE AS $$
 SELECT NOT EXISTS (SELECT 1 FROM module_runtime_control WHERE owner_instance=instance)
 AND NOT EXISTS (SELECT 1 FROM runtime_instance_heartbeat WHERE owner_instance=instance
   AND heartbeat_at>=now()-interval '120 seconds')
 AND NOT EXISTS (SELECT 1 FROM jobs WHERE lease_worker_id=instance
   AND (last_heartbeat_at>=now()-interval '120 seconds'
     OR (state IN ('CLAIMED','EXECUTING') AND lease_expires_at>now())))
 AND NOT EXISTS (SELECT 1 FROM endpoint_admission WHERE owner_instance=instance
   AND heartbeat_at>=now()-interval '120 seconds')
 AND NOT EXISTS (SELECT 1 FROM runtime_task_lease WHERE owner_instance=instance
   AND heartbeat_at>=now()-interval '120 seconds')
$$;

-- Do not copy endpoints, sessions, instance identities or tokens into the audit log.
CREATE FUNCTION ui2_audit_quarantine_release() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
 actor TEXT := current_setting('app.actor_fingerprint',true);
 reason TEXT := current_setting('app.action_id',true);
BEGIN
 IF actor IS NULL OR actor NOT IN ('system:worker','system:release') THEN
  RAISE EXCEPTION 'quarantine_release_audit_context_missing';
 END IF;
 IF reason IS DISTINCT FROM 'QUARANTINE_RELEASED_OWNER_GONE' THEN
  reason := 'QUARANTINE_RELEASED_TRANSPORT_CLOSED';
 END IF;
 INSERT INTO public.audit_log(table_name,row_pk,operation,actor_fingerprint,action_id,before_state,correlation_run_id)
 VALUES (TG_TABLE_NAME,to_jsonb(OLD)->>TG_ARGV[0],'DELETE',actor,reason,
   jsonb_build_object('state','QUARANTINED','owner_role',OLD.owner_role,'reason',reason),
   current_setting('app.correlation_run_id',true));
 RETURN OLD;
END;
$$;
REVOKE ALL ON FUNCTION ui2_audit_quarantine_release() FROM PUBLIC;
CREATE TRIGGER endpoint_quarantine_release_audit AFTER DELETE ON endpoint_admission
 FOR EACH ROW WHEN (OLD.state='QUARANTINED') EXECUTE FUNCTION ui2_audit_quarantine_release('request_id');
CREATE TRIGGER task_quarantine_release_audit AFTER DELETE ON runtime_task_lease
 FOR EACH ROW WHEN (OLD.state='QUARANTINED') EXECUTE FUNCTION ui2_audit_quarantine_release('task_key');

CREATE FUNCTION ui2_release_orphan_quarantines() RETURNS INTEGER
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
 RETURN released+tasks;
END;
$$;
REVOKE ALL ON FUNCTION ui2_quarantine_owner_gone(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ui2_release_orphan_quarantines() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ui2_quarantine_owner_gone(TEXT),ui2_release_orphan_quarantines() TO ui2_app;
