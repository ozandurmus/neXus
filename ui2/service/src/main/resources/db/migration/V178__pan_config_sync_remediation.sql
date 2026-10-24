-- V178 -- Palo Alto HA: configuration sync from the active member when readiness check 4 is FAIL
-- (docs/design/PO_DECISION_RECORD_2026_10_24_PAN_CONFIG_SYNC.md, RATIFIED: the PO answered "Onaylıyorum (Önerilen akış)"
-- on 2026-10-24). Design: docs/design/PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md.
--   1. ui2_job_module: V166's mapping byte-for-byte plus pan_failover_config_sync in the failover branch (without a
--      module the claim statement never selects the job and ui2_job_owner_valid never admits its writes).
--   2. Gate row pan_failover_config_sync: the exact type=op literal, configuration-write, SIGNED_OFF, never retried.
--   3. pan_inventory_show_ha_state gains the config-sync purpose in its max_frequency (command, timeout, retry unchanged).
--   4. pan_config_sync: one audited record per request (opaque references only, no device output), with a guard that
--      keeps bindings and terminal states immutable, and at most one open record per cluster.
--   5. A config-sync job that ends without the worker closing its record closes it in the same transaction.
--   6. Argon review 2026-10-24 P0-1 (docs/design/ARGON_REVIEW_2026_10_24_PAN_CONFIG_SYNC.md): the job never takes the
--      estate-wide 'fleet.failover.execution' task lease. Its endpoint leases use their own purpose CONFIGURATION_WRITE
--      (per endpoint, never estate-wide); cluster mutual exclusion is advisory lock 136 plus the pan_config_sync,
--      failover_run, failover_resume and failover_dispatch_intent checks in the repository. A QUARANTINED (or expired)
--      CONFIGURATION_WRITE endpoint lease whose job and record have ended is released, audited, by the sweep every
--      admission, heartbeat and drain barrier already runs, so an uncertain outcome never holds a lease that blocks a
--      module drain (the 2026-10-22 incident, V174).
--   7. P1-3: mutation_possible falls back to false only for a write proven never sent (the switch off, or a refusal of
--      the transport before contact), together with that terminal outcome.
-- Re-running is a no-op for the seed data (ON CONFLICT DO NOTHING, guarded update); DDL uses IF NOT EXISTS or
-- drop-then-create so the dry run can execute the file twice inside one rolled-back transaction.
SELECT set_config('app.actor_fingerprint', 'migration:V178_pan_config_sync_remediation', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

CREATE OR REPLACE FUNCTION ui2_job_module(capability TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT CASE
 WHEN capability IN ('cp_policy_collect','pan_policy_collect') THEN 'policy'
 WHEN capability IN ('cp_gateway_backup','cp_spark_sftp_backup','cp_gaia_snapshot','pan_device_state_backup','cp_mds_export','https_vendor_backup','rdw_cc_config_backup','asa_config_backup','fgt_config_backup') THEN 'backup'
 WHEN capability IN ('device_confirm_check_point','device_confirm_palo_alto','device_confirm_https','device_confirm_cisco_asa','device_confirm_fortigate','cp_inventory_collect','pan_inventory_collect','https_inventory_collect','asa_inventory_collect','fgt_inventory_collect','cp_discovery_enumerate','pan_discovery_enumerate','rdw_discovery_enumerate','fmg_discovery_enumerate','bcmc_discovery_enumerate') THEN 'inventory'
 WHEN capability IN ('cp_failover_readiness','pan_failover_readiness','cp_cluster_failover','pan_cluster_failover','pan_failover_config_sync') THEN 'failover'
 WHEN capability IN ('diagnostic_read','fmg_interface_detail','pan_syslog_measure','cp_system_log_collect','pan_system_log_collect','cp_spike_layout_review') THEN 'diagnostics'
 WHEN capability IN ('cp_configuration_collect','pan_configuration_collect','fgt_configuration_collect','asa_configuration_collect','proxysg_configuration_collect') THEN 'configuration'
 WHEN capability = 'packet_analysis_run' THEN 'packet_analysis'
 ELSE NULL END
$$;

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('pan_failover_config_sync', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<request><high-availability><sync-to-remote><running-config/></sync-to-remote></high-availability></request>',
     'configuration-write', 'SIGNED_OFF', 60, 'none',
     'once per config-sync job, on the member that is active in the job''s own fresh HA-state read, only while readiness check 4 is FAIL',
     'one key per member per job',
     'status other than success or error, no reply or an oversized body = OUTCOME_UNKNOWN (never resent)',
     'none', '["deviceStatus"]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_24_PAN_CONFIG_SYNC.md (RATIFIED); docs/design/PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md section 2')
ON CONFLICT (gate_id) DO NOTHING;

UPDATE gate_registry SET max_frequency = max_frequency
    || '; config sync (PO 2026-10-24): once per member per config-sync job, immediately before its one write'
WHERE gate_id = 'pan_inventory_show_ha_state'
  AND max_frequency NOT LIKE '%config sync (PO 2026-10-24)%';

CREATE TABLE IF NOT EXISTS pan_config_sync (
    sync_id TEXT PRIMARY KEY,
    cluster_ref TEXT NOT NULL,
    vendor TEXT NOT NULL DEFAULT 'palo_alto' CHECK (vendor = 'palo_alto'),
    source_member_ref TEXT NOT NULL,
    target_member_ref TEXT NOT NULL,
    member_set_revision TEXT NOT NULL,
    readiness_run_id TEXT NOT NULL REFERENCES failover_run(run_id),
    initiated_by TEXT NOT NULL,
    initiator_role TEXT NOT NULL CHECK (initiator_role IN ('security_admin', 'operation_admin')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    requested_in_business_hours BOOLEAN NOT NULL,
    business_hours_ack_at TIMESTAMPTZ,
    job_id TEXT NOT NULL UNIQUE REFERENCES jobs(job_id),
    state TEXT NOT NULL CHECK (state IN ('QUEUED', 'READING_STATE', 'SYNCING', 'DONE', 'STOPPED')),
    outcome TEXT,
    device_status TEXT CHECK (device_status IS NULL OR device_status IN ('success', 'error')),
    mutation_possible BOOLEAN NOT NULL DEFAULT false,
    followup_readiness_run_id TEXT REFERENCES failover_run(run_id),
    followup_refusal TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CHECK (source_member_ref <> target_member_ref),
    CHECK ((state IN ('DONE', 'STOPPED')) = (finished_at IS NOT NULL)),
    CHECK (NOT requested_in_business_hours OR business_hours_ack_at IS NOT NULL),
    CHECK (state <> 'DONE' OR (device_status = 'success' AND mutation_possible)),
    CHECK (device_status IS NULL OR mutation_possible)
);
-- At most one open config sync per cluster.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pan_config_sync_open ON pan_config_sync (cluster_ref)
    WHERE state IN ('QUEUED', 'READING_STATE', 'SYNCING');
CREATE INDEX IF NOT EXISTS ix_pan_config_sync_cluster ON pan_config_sync (cluster_ref, requested_at DESC);

DROP TRIGGER IF EXISTS trg_audit_pan_config_sync ON pan_config_sync;
CREATE TRIGGER trg_audit_pan_config_sync AFTER INSERT OR UPDATE OR DELETE ON pan_config_sync
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('sync_id');
GRANT SELECT, INSERT, UPDATE ON pan_config_sync TO ui2_app;

-- Append-only: the binding never changes, a terminal record is final, mutation_possible never falls back.
CREATE OR REPLACE FUNCTION ui2_pan_config_sync_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'CONFIG_SYNC_HISTORY_IMMUTABLE'; END IF;
 IF TG_OP = 'INSERT' THEN
  IF NEW.state <> 'QUEUED' OR NEW.mutation_possible OR NEW.device_status IS NOT NULL THEN
   RAISE EXCEPTION 'CONFIG_SYNC_HISTORY_IMMUTABLE';
  END IF;
  RETURN NEW;
 END IF;
 IF OLD.state IN ('DONE', 'STOPPED') AND (ROW(NEW.state, NEW.outcome, NEW.device_status, NEW.mutation_possible,
     NEW.finished_at) IS DISTINCT FROM ROW(OLD.state, OLD.outcome, OLD.device_status, OLD.mutation_possible,
     OLD.finished_at)
     OR (OLD.followup_readiness_run_id IS NOT NULL AND NEW.followup_readiness_run_id IS DISTINCT FROM OLD.followup_readiness_run_id)
     OR (OLD.followup_refusal IS NOT NULL AND NEW.followup_refusal IS DISTINCT FROM OLD.followup_refusal)) THEN
  RAISE EXCEPTION 'CONFIG_SYNC_TERMINAL';
 END IF;
 IF ROW(NEW.sync_id, NEW.cluster_ref, NEW.vendor, NEW.source_member_ref, NEW.target_member_ref,
        NEW.member_set_revision, NEW.readiness_run_id, NEW.initiated_by, NEW.initiator_role, NEW.requested_at,
        NEW.requested_in_business_hours, NEW.business_hours_ack_at, NEW.job_id)
    IS DISTINCT FROM
    ROW(OLD.sync_id, OLD.cluster_ref, OLD.vendor, OLD.source_member_ref, OLD.target_member_ref,
        OLD.member_set_revision, OLD.readiness_run_id, OLD.initiated_by, OLD.initiator_role, OLD.requested_at,
        OLD.requested_in_business_hours, OLD.business_hours_ack_at, OLD.job_id)
    OR (OLD.mutation_possible AND NOT NEW.mutation_possible AND NOT (OLD.state = 'SYNCING' AND NEW.state = 'STOPPED'
        AND NEW.device_status IS NULL AND NEW.outcome IN ('MUTATION_DISABLED_BEFORE_SEND', 'NOT_SENT_BEFORE_CONTACT')))
    OR (OLD.started_at IS NOT NULL AND NEW.started_at IS DISTINCT FROM OLD.started_at)
    OR (OLD.device_status IS NOT NULL AND NEW.device_status IS DISTINCT FROM OLD.device_status) THEN
  RAISE EXCEPTION 'CONFIG_SYNC_HISTORY_IMMUTABLE';
 END IF;
 RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS pan_config_sync_guard ON pan_config_sync;
CREATE TRIGGER pan_config_sync_guard BEFORE INSERT OR UPDATE OR DELETE ON pan_config_sync
 FOR EACH ROW EXECUTE FUNCTION ui2_pan_config_sync_guard();

-- A config-sync job that ends (failed, cancelled, rejected, outcome unknown, reconciled) closes its open record in the
-- same transaction. The device's recorded reply always wins (Argon P0-2: a cancel after the send never hides or
-- rewrites it): success -> DONE / CONFIG_SYNC_REQUESTED, error -> DEVICE_REPORTED_ERROR; otherwise OUTCOME_UNKNOWN once
-- the write was prepared, JOB_ENDED_WITHOUT_RESULT before that.
CREATE OR REPLACE FUNCTION ui2_pan_config_sync_job_end() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.state IN ('COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', 'OUTCOME_UNKNOWN', 'RECONCILED')
    AND NEW.state IS DISTINCT FROM OLD.state THEN
  UPDATE pan_config_sync SET
      state = CASE WHEN device_status = 'success' THEN 'DONE' ELSE 'STOPPED' END,
      outcome = CASE WHEN device_status = 'success' THEN 'CONFIG_SYNC_REQUESTED'
          WHEN device_status = 'error' THEN 'DEVICE_REPORTED_ERROR'
          WHEN mutation_possible THEN 'OUTCOME_UNKNOWN' ELSE 'JOB_ENDED_WITHOUT_RESULT' END,
      finished_at = clock_timestamp()
   WHERE job_id = NEW.job_id AND state NOT IN ('DONE', 'STOPPED');
 END IF;
 RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS pan_config_sync_job_end ON jobs;
CREATE TRIGGER pan_config_sync_job_end AFTER UPDATE OF state ON jobs
 FOR EACH ROW EXECUTE FUNCTION ui2_pan_config_sync_job_end();

-- 6. The CONFIGURATION_WRITE endpoint purpose (V128's list plus one value).
ALTER TABLE endpoint_admission DROP CONSTRAINT IF EXISTS endpoint_admission_purpose_class_check;
ALTER TABLE endpoint_admission ADD CONSTRAINT endpoint_admission_purpose_class_check CHECK (purpose_class IN
    ('ENROLLMENT','INVENTORY','DISCOVERY','POLICY','BACKUP','CONFIGURATION','DIAGNOSTIC','FAILOVER_READINESS',
     'FAILOVER_EXECUTION','CONFIGURATION_WRITE'));

-- Settled: the config-sync job and its record have ended; nothing about it needs its endpoint any more. An uncertain
-- outcome is recorded on the record, its attempt and its job; it opens no incident and holds no fence (design section 4).
CREATE OR REPLACE FUNCTION ui2_config_sync_lease_settled(settled_job TEXT) RETURNS BOOLEAN
LANGUAGE SQL STABLE AS $$
 SELECT settled_job IS NOT NULL
 AND EXISTS (SELECT 1 FROM jobs j WHERE j.job_id=settled_job AND j.capability_id='pan_failover_config_sync'
   AND j.state NOT IN ('REQUESTED','CLAIMED','EXECUTING'))
 AND EXISTS (SELECT 1 FROM pan_config_sync s WHERE s.job_id=settled_job AND s.state IN ('DONE','STOPPED'))
$$;

-- V174's audit trigger plus one accepted release reason, for CONFIGURATION_WRITE endpoint leases only.
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
 ELSIF reason IS NOT DISTINCT FROM 'QUARANTINE_RELEASED_SYNC_SETTLED' AND TG_TABLE_NAME='endpoint_admission'
    AND to_jsonb(OLD)->>'purpose_class'='CONFIGURATION_WRITE' THEN
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

-- Release the settled CONFIGURATION_WRITE endpoint leases. An expired LEASED row is quarantined first so that every
-- release goes through the audit trigger.
CREATE OR REPLACE FUNCTION ui2_release_settled_config_sync_leases() RETURNS INTEGER
LANGUAGE plpgsql AS $$
DECLARE released INTEGER; previous_action TEXT;
BEGIN
 UPDATE endpoint_admission SET state='QUARANTINED'
  WHERE purpose_class='CONFIGURATION_WRITE' AND state='LEASED' AND expires_at<=now()
  AND ui2_config_sync_lease_settled(job_id);
 previous_action := current_setting('app.action_id',true);
 PERFORM set_config('app.action_id','QUARANTINE_RELEASED_SYNC_SETTLED',true);
 DELETE FROM endpoint_admission
  WHERE purpose_class='CONFIGURATION_WRITE' AND state='QUARANTINED' AND ui2_config_sync_lease_settled(job_id);
 GET DIAGNOSTICS released = ROW_COUNT;
 PERFORM set_config('app.action_id',coalesce(previous_action,''),true);
 RETURN released;
END;
$$;

-- V174's sweep, unchanged, plus the settled config-sync release after it.
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
 released := released + ui2_release_settled_config_sync_leases();
 RETURN released+tasks;
END;
$$;

REVOKE ALL ON FUNCTION ui2_config_sync_lease_settled(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ui2_release_settled_config_sync_leases() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ui2_config_sync_lease_settled(TEXT),ui2_release_settled_config_sync_leases() TO ui2_app;
