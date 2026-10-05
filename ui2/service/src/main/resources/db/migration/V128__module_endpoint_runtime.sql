-- Policy split groundwork. Additive; no command-gate changes.
CREATE FUNCTION ui2_job_module(capability TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT CASE
 WHEN capability IN ('cp_policy_collect','pan_policy_collect') THEN 'policy'
 WHEN capability IN ('cp_gateway_backup','cp_spark_sftp_backup','cp_gaia_snapshot','pan_device_state_backup','cp_mds_export','https_vendor_backup','rdw_cc_config_backup','asa_config_backup','fgt_config_backup') THEN 'backup'
 WHEN capability IN ('device_confirm_check_point','device_confirm_palo_alto','device_confirm_https','device_confirm_cisco_asa','device_confirm_fortigate','cp_inventory_collect','pan_inventory_collect','https_inventory_collect','asa_inventory_collect','fgt_inventory_collect','cp_discovery_enumerate','pan_discovery_enumerate','rdw_discovery_enumerate','fmg_discovery_enumerate','bcmc_discovery_enumerate') THEN 'inventory'
 WHEN capability IN ('cp_failover_readiness','pan_failover_readiness','cp_cluster_failover','pan_cluster_failover') THEN 'failover'
 WHEN capability IN ('diagnostic_read','fmg_interface_detail') THEN 'diagnostics'
 WHEN capability IN ('cp_configuration_collect','pan_configuration_collect','fgt_configuration_collect','asa_configuration_collect','proxysg_configuration_collect') THEN 'configuration'
 ELSE NULL END
$$;
CREATE TABLE module_runtime_control (
 module TEXT PRIMARY KEY CHECK (module IN ('policy','backup','inventory','failover','diagnostics','configuration','compliance','scheduler','general')),
 effective_owner TEXT NOT NULL,
 fallback_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
 drain_requested BOOLEAN NOT NULL DEFAULT FALSE,
 drain_generation BIGINT NOT NULL DEFAULT 0,
 drain_ack_at TIMESTAMPTZ, drain_ack_generation BIGINT,
 owner_instance TEXT,
 owner_heartbeat_at TIMESTAMPTZ,
 pool_active INTEGER, pool_idle INTEGER, pool_pending INTEGER, pool_timeouts BIGINT, pool_wait_nanos BIGINT,
 admission_wait_ms BIGINT NOT NULL DEFAULT 0, admission_grants BIGINT NOT NULL DEFAULT 0,
 last_authorization_ref TEXT, last_reason TEXT, last_snapshot_id TEXT,
 CHECK ((fallback_enabled AND effective_owner = 'general' AND module <> 'general')
     OR (NOT fallback_enabled AND effective_owner = module))
);
INSERT INTO module_runtime_control(module,effective_owner,fallback_enabled) VALUES
 ('policy','policy',FALSE),('backup','general',TRUE),('inventory','general',TRUE),
 ('failover','general',TRUE),('diagnostics','general',TRUE),('configuration','general',TRUE),
 ('compliance','compliance',FALSE),('scheduler','scheduler',FALSE),('general','general',FALSE);
GRANT SELECT, INSERT, UPDATE, DELETE ON module_runtime_control TO ui2_app;
ALTER TABLE jobs ADD COLUMN admission_not_before TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN admission_deferrals BIGINT NOT NULL DEFAULT 0;
ALTER TABLE jobs ADD COLUMN lease_owner_generation BIGINT;
CREATE SEQUENCE endpoint_permit_epoch;
GRANT USAGE, SELECT ON SEQUENCE endpoint_permit_epoch TO ui2_app;
CREATE TABLE endpoint_admission (
 request_id TEXT PRIMARY KEY,
 endpoint_ref TEXT NOT NULL,
 purpose_class TEXT NOT NULL CHECK (purpose_class IN ('ENROLLMENT','INVENTORY','DISCOVERY','POLICY','BACKUP','CONFIGURATION','DIAGNOSTIC','FAILOVER_READINESS','FAILOVER_EXECUTION')),
 job_id TEXT REFERENCES jobs(job_id), job_epoch BIGINT,
 operation_ref TEXT, operation_epoch BIGINT,
 session_ref TEXT NOT NULL,
 owner_role TEXT NOT NULL CHECK (owner_role IN ('policy','general','service')),
 owner_instance TEXT NOT NULL, owner_generation BIGINT NOT NULL CHECK (owner_generation > 0),
 lease_token TEXT NOT NULL, permit_epoch BIGINT NOT NULL DEFAULT nextval('endpoint_permit_epoch') CHECK (permit_epoch > 0),
 state TEXT NOT NULL CHECK (state IN ('WAITING','LEASED','QUARANTINED')),
 parallel_policy BOOLEAN NOT NULL DEFAULT FALSE,
 requested_at TIMESTAMPTZ NOT NULL DEFAULT now(), not_before TIMESTAMPTZ,
 heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '60 seconds',
 CHECK ((job_id IS NOT NULL AND job_epoch IS NOT NULL AND job_epoch > 0 AND operation_ref IS NULL AND operation_epoch IS NULL)
     OR (job_id IS NULL AND job_epoch IS NULL AND operation_ref IS NOT NULL AND operation_epoch IS NOT NULL AND operation_epoch > 0)),
 UNIQUE (job_id,session_ref), UNIQUE (operation_ref,session_ref)
);
CREATE INDEX endpoint_admission_fifo ON endpoint_admission(endpoint_ref,state,requested_at,request_id);
CREATE INDEX endpoint_admission_expiry ON endpoint_admission(state,expires_at);
CREATE INDEX endpoint_admission_owner ON endpoint_admission(owner_role,owner_generation,state);
GRANT SELECT, INSERT, UPDATE, DELETE ON endpoint_admission TO ui2_app;
CREATE TABLE runtime_task_lease (
 task_key TEXT PRIMARY KEY, owner_role TEXT NOT NULL, owner_instance TEXT NOT NULL,
 owner_generation BIGINT NOT NULL CHECK (owner_generation > 0), token TEXT NOT NULL,
 epoch BIGINT NOT NULL CHECK (epoch > 0), state TEXT NOT NULL CHECK (state IN ('LEASED','QUARANTINED')),
 heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL,
 bound_job_id TEXT REFERENCES jobs(job_id), bound_job_epoch BIGINT,
 CHECK ((bound_job_id IS NULL AND bound_job_epoch IS NULL) OR (bound_job_id IS NOT NULL AND bound_job_epoch IS NOT NULL AND bound_job_epoch>0))
);
CREATE INDEX runtime_task_lease_expiry ON runtime_task_lease(expires_at);
GRANT SELECT, INSERT, UPDATE, DELETE ON runtime_task_lease TO ui2_app;

CREATE TRIGGER module_runtime_control_audit AFTER UPDATE ON module_runtime_control
 FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('module');

-- Compatibility bootstrap: even an old all-purpose claim statement cannot race the drain barrier.
CREATE FUNCTION ui2_fence_claim() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_module TEXT; v_role TEXT; v_generation BIGINT;
BEGIN
 IF NEW.state='CLAIMED' AND OLD.state='REQUESTED' THEN
  PERFORM pg_advisory_xact_lock(294611);
  v_module := ui2_job_module(NEW.capability_id);
  v_role := split_part(NEW.lease_worker_id,'-',1);
  IF v_role='worker' THEN v_role := 'general'; END IF;
  SELECT m.generation INTO v_generation FROM module_runtime_control m
   JOIN module_runtime_control r ON r.module=m.effective_owner
   WHERE m.module=v_module AND m.effective_owner=v_role AND NOT m.drain_requested AND NOT r.drain_requested
    AND (r.owner_instance IS NULL OR r.owner_instance=NEW.lease_worker_id)
   FOR UPDATE OF m,r;
  IF v_generation IS NULL OR NEW.job_type<>NEW.capability_id OR
     ((SELECT count(*) FROM jobs WHERE state IN ('CLAIMED','EXECUTING'))
      + (SELECT count(*) FROM runtime_task_lease WHERE task_key='fleet.failover.execution' AND owner_role='service')) >= 10 THEN
   RAISE EXCEPTION 'CLAIM_OWNER_OR_CAPACITY_DENIED';
  END IF;
  NEW.lease_owner_generation := v_generation;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER jobs_module_claim_fence BEFORE UPDATE OF state ON jobs
 FOR EACH ROW EXECUTE FUNCTION ui2_fence_claim();
-- Workers may update their liveness/drain fields, never deployment ownership or fallback flags.
REVOKE INSERT, UPDATE, DELETE ON module_runtime_control FROM ui2_app;
GRANT UPDATE(owner_instance,owner_heartbeat_at,drain_requested,drain_generation,drain_ack_at,drain_ack_generation,pool_active,pool_idle,pool_pending,pool_timeouts,pool_wait_nanos,admission_wait_ms,admission_grants)
 ON module_runtime_control TO ui2_app;

CREATE FUNCTION ui2_job_owner_valid(id TEXT, epoch BIGINT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM jobs j WHERE j.job_id=id AND j.lease_epoch=epoch
  AND j.state<>'REQUESTED' AND (j.lease_owner_generation IS NULL OR
   (EXISTS(SELECT 1 FROM module_runtime_control m
    JOIN module_runtime_control r ON r.module=m.effective_owner
    WHERE m.module=ui2_job_module(j.capability_id) AND m.generation=j.lease_owner_generation
     AND m.effective_owner=split_part(j.lease_worker_id,'-',1)
     AND (r.owner_instance IS NULL OR (r.owner_instance=j.lease_worker_id AND r.owner_heartbeat_at>now()-interval '60 seconds' AND j.lease_expires_at>now()))))))
$$;

-- Parsed policy units only; raw vendor responses are never retained.
CREATE TABLE policy_unit_checkpoint (
 job_id TEXT NOT NULL REFERENCES jobs(job_id), unit_ref TEXT NOT NULL,
 source_version TEXT NOT NULL, snapshot JSONB NOT NULL, complete BOOLEAN NOT NULL,
 chunk_generation TEXT, chunk_count INTEGER CHECK (chunk_count >= 0),
 CHECK ((chunk_generation IS NULL) = (chunk_count IS NULL)),
 PRIMARY KEY(job_id,unit_ref)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON policy_unit_checkpoint TO ui2_app;
