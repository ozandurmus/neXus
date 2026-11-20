-- V258: Script Execution slice 2 -- broker (ui2-script-runner) and per-run execution pods, script_run job,
-- scheduler slot fence, run ledger, audited output download and the broker's own database role
-- (docs/design/SCRIPT_EXECUTION_CONTRACT.md sections 3.2-3.4, 5, 7, Amendment 2026-11-18 and Amendment 2026-11-18 (b);
-- docs/design/PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md).
--
-- Numbered V258 after route R0's V252 (static_route_preview), V253, V254 (CP per-VS cpstat gate), V255/V256 (VSX
-- chassis lane), and V257 (approver recheck): ui2_job_module below is R0's V252 mapping plus script_run -> script_runner.
-- Dry-run in BEGIN/ROLLBACK on the live database before rollout.
SELECT set_config('app.actor_fingerprint', 'migration:V258_script_execution_slice2', true);
SELECT set_config('app.action_id', 'script_execution_slice2_migration', true);

ALTER TABLE script
    ADD COLUMN IF NOT EXISTS last_scheduled_slot TIMESTAMPTZ;

CREATE TABLE script_run (
    job_id                  TEXT        PRIMARY KEY REFERENCES jobs(job_id),
    script_id               TEXT        NOT NULL REFERENCES script(script_id) ON DELETE RESTRICT,
    version                 INTEGER     NOT NULL CHECK (version >= 1),
    state                   TEXT        NOT NULL CHECK (state IN ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED')),
    outcome                 TEXT        CHECK (outcome IS NULL OR outcome IN ('SUCCESS', 'FAILURE', 'TIMED_OUT', 'CANCELLED')),
    trigger_kind            TEXT        NOT NULL DEFAULT 'MANUAL' CHECK (trigger_kind IN ('MANUAL', 'SCHEDULED')),
    scheduled_slot          TIMESTAMPTZ,
    run_as                  TEXT        NOT NULL CHECK (run_as IN ('role:operator', 'role:viewer', 'role:replay_viewer')),
    requested_by            TEXT        NOT NULL CHECK (btrim(requested_by) <> ''),
    requested_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at              TIMESTAMPTZ,
    finished_at             TIMESTAMPTZ,
    duration_ms             BIGINT      CHECK (duration_ms IS NULL OR duration_ms >= 0),
    exit_code               INTEGER,
    stdout_bytes            INTEGER     NOT NULL DEFAULT 0 CHECK (stdout_bytes BETWEEN 0 AND 262144),
    stderr_bytes            INTEGER     NOT NULL DEFAULT 0 CHECK (stderr_bytes BETWEEN 0 AND 262144),
    stdout_truncated        BOOLEAN     NOT NULL DEFAULT FALSE,
    stderr_truncated        BOOLEAN     NOT NULL DEFAULT FALSE,
    output_artefact_ref     TEXT,
    output_key_id           TEXT,
    output_wrapped_data_key BYTEA,
    output_sha256           TEXT        CHECK (output_sha256 IS NULL OR output_sha256 ~ '^[0-9a-f]{64}$'),
    output_size_bytes       BIGINT      CHECK (output_size_bytes IS NULL OR output_size_bytes >= 0),
    output_tail             TEXT        CHECK (output_tail IS NULL OR octet_length(output_tail) <= 4096),
    egress_declared         TEXT[]      NOT NULL DEFAULT '{}',
    egress_device_targets   TEXT[]      NOT NULL DEFAULT '{}',
    credential_refs_used    TEXT[]      NOT NULL DEFAULT '{}',
    session_id              TEXT,
    dispatch_epoch          BIGINT,
    failure_reason          TEXT,
    FOREIGN KEY (script_id, version) REFERENCES script_version(script_id, version)
);

CREATE INDEX idx_script_run_script_requested
    ON script_run(script_id, requested_at DESC, job_id DESC);

CREATE UNIQUE INDEX uq_script_run_scheduled_slot
    ON script_run(script_id, scheduled_slot)
    WHERE scheduled_slot IS NOT NULL;

CREATE TRIGGER trg_audit_script_run
    AFTER INSERT OR UPDATE OR DELETE ON script_run
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('job_id');

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('script_run', 'output_wrapped_data_key', 1, 'V258: wrapped data key for encrypted script output'),
    ('script_run', 'egress_declared',         2, 'V258: declared egress host:port list may name internal addresses'),
    ('script_run', 'output_tail',             2, 'V258: inline script output tail may contain sensitive operational text'),
    ('script_run', 'credential_refs_used',    2, 'V258: credential reference ids resolved for this run; as script.credential_refs')
ON CONFLICT (table_name, column_name) DO NOTHING;

-- Admit script_run jobs (target_kind = 'script', target_ref = '<script_id>@<version>', target_device_id IS NULL).
-- V154's shapes are kept verbatim; the script shape is added for script_run only.
ALTER TABLE jobs DROP CONSTRAINT chk_jobs_target_shape;
ALTER TABLE jobs ADD CONSTRAINT chk_jobs_target_shape
    CHECK (
        (target_kind = 'device' AND target_device_id IS NOT NULL AND target_ref IS NULL)
        OR (target_kind = 'discovery_run' AND target_ref IS NOT NULL AND target_device_id IS NULL)
        OR (target_kind = 'packet_analysis_case' AND target_ref IS NOT NULL AND target_device_id IS NULL
            AND capability_id = 'packet_analysis_run')
        OR (target_kind = 'script' AND target_ref IS NOT NULL AND target_device_id IS NULL
            AND capability_id = 'script_run')
    );

-- Route R0's V252 mapping byte-for-byte (V247 plus static_route_preview in diagnostics), plus script_run -> script_runner.
CREATE OR REPLACE FUNCTION ui2_job_module(capability TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT CASE
 WHEN capability IN ('cp_policy_collect','pan_policy_collect') THEN 'policy'
 WHEN capability IN ('cp_gateway_backup','cp_spark_sftp_backup','cp_gaia_snapshot','pan_device_state_backup','cp_mds_export','https_vendor_backup','rdw_cc_config_backup','asa_config_backup','fgt_config_backup','credential_canary') THEN 'backup'
 WHEN capability IN ('device_confirm_check_point','device_confirm_palo_alto','device_confirm_https','device_confirm_cisco_asa','device_confirm_fortigate','cp_inventory_collect','pan_inventory_collect','https_inventory_collect','asa_inventory_collect','fgt_inventory_collect','cp_discovery_enumerate','pan_discovery_enumerate','rdw_discovery_enumerate','fmg_discovery_enumerate','bcmc_discovery_enumerate') THEN 'inventory'
 WHEN capability IN ('cp_failover_readiness','pan_failover_readiness','cp_cluster_failover','pan_cluster_failover','pan_failover_config_sync','asa_cluster_failover','fgt_failover_readiness') THEN 'failover'
 WHEN capability IN ('diagnostic_read','fmg_interface_detail','pan_syslog_measure','cp_system_log_collect','pan_system_log_collect','cp_spike_layout_review','path_validation','static_route_preview') THEN 'diagnostics'
 WHEN capability IN ('cp_configuration_collect','pan_configuration_collect','fgt_configuration_collect','asa_configuration_collect','proxysg_configuration_collect') THEN 'configuration'
 WHEN capability = 'packet_analysis_run' THEN 'packet_analysis'
 WHEN capability = 'script_run' THEN 'script_runner'
 ELSE NULL END
$$;

ALTER TABLE module_runtime_control DROP CONSTRAINT IF EXISTS module_runtime_control_module_check;
ALTER TABLE module_runtime_control ADD CONSTRAINT module_runtime_control_module_check
    CHECK (module IN ('policy','backup','inventory','failover','diagnostics','configuration','compliance','scheduler',
                      'general','packet_analysis','script_runner'));

INSERT INTO module_runtime_control(module, effective_owner, fallback_enabled, last_reason)
VALUES ('script_runner', 'script_runner', FALSE, 'V258: dedicated script runner pod; owns script_run only')
ON CONFLICT (module) DO NOTHING;

-- run_as is a role from the allowlist only (Amendment 2026-11-18 (b) item 9; ScriptExecutionJob.RUN_AS_ALLOWLIST).
-- Slice 1 is not released, so no row should need the update; it maps any other value to the default.
UPDATE script SET run_as = 'role:operator', updated_at = now()
 WHERE run_as NOT IN ('role:operator', 'role:viewer', 'role:replay_viewer');
DO $runas$
DECLARE v_name TEXT;
BEGIN
    FOR v_name IN SELECT conname FROM pg_constraint
                  WHERE conrelid = 'public.script'::regclass AND contype = 'c'
                    AND pg_get_constraintdef(oid) LIKE '%run_as%' LOOP
        EXECUTE format('ALTER TABLE script DROP CONSTRAINT %I', v_name);
    END LOOP;
END
$runas$;
ALTER TABLE script ADD CONSTRAINT chk_script_run_as_allowlist
    CHECK (run_as IN ('role:operator', 'role:viewer', 'role:replay_viewer'));

-- One audit row per output download, written by the service before the first byte (the audit_report_download pattern).
CREATE OR REPLACE FUNCTION audit_script_output_download(p_job_id TEXT)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_actor  TEXT := current_setting('app.actor_fingerprint', true);
    v_action TEXT := current_setting('app.action_id', true);
    v_row    JSONB;
BEGIN
    IF v_actor IS NULL OR v_actor = '' OR v_action IS NULL OR v_action = '' THEN
        RAISE EXCEPTION 'audit_context_missing on script_run.job_id: actor or action not set in this transaction';
    END IF;
    IF v_action <> 'script_output_download' THEN
        RAISE EXCEPTION 'invalid script output audit action';
    END IF;
    SELECT fn_audit_redact('script_run', to_jsonb(r)) INTO v_row FROM script_run r WHERE r.job_id = p_job_id;
    IF v_row IS NULL THEN
        RAISE EXCEPTION 'script_run_not_found';
    END IF;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, before_state, after_state,
                          correlation_run_id)
    VALUES ('script_run', p_job_id, 'UPDATE', v_actor, v_action, v_row, v_row,
            current_setting('app.correlation_run_id', true));
END;
$$;
REVOKE ALL ON FUNCTION audit_script_output_download(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_script_output_download(TEXT) TO ui2_app;

-- The service admits runs, starts them (session, body release), stores their output and closes them; the scheduler
-- admits scheduled runs. Nothing deletes a run. The general worker has no script_run executor and gets no grant.
GRANT SELECT, INSERT, UPDATE ON script_run TO ui2_app;

DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT, UPDATE', 'script_run'),
        ('ui2_scheduler', 'SELECT, INSERT, UPDATE', 'script_run'),
        ('ui2_scheduler', 'SELECT, UPDATE', 'script')
    ) AS g(role_name, privs, tbl_name)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.%I TO %I', v_rec.privs, v_rec.tbl_name, v_rec.role_name);
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ui2_service') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION public.audit_script_output_download(TEXT) TO ui2_service';
    END IF;
END
$grants$;

-- The broker's own database login role ui2_script_runner (Amendment 2026-11-18 (b) item 5; the V242 per-module role
-- pattern). Created NOLOGIN on first start (by deploy/ui2/10-configmap.yaml create-app-role.sh or below when the
-- migration role holds CREATEROLE); tools/delivery/script_runner_db_login.py sets LOGIN and its SCRAM verifier on the
-- database pod's local socket and calls this function, and V258 calls it here too so grants apply in either bootstrap
-- order. The broker reads and writes only the job lease, its step attempts, its own module
-- row, and what the shared module heartbeat touches (runtime_instance_heartbeat, runtime_task_lease and, through
-- ui2_release_orphan_quarantines(), expired endpoint_admission quarantines); proved by
-- ScriptRunnerDatabaseRoleIntegrationTest on PostgreSQL 16. It never reads script, script_version, script_run, credentials or sessions: the service releases what a run
-- needs over the internal port. failover_run, failover_resume and pan_config_sync are granted ONLY because the
-- SECURITY INVOKER triggers on jobs.state (V136, V162, V170, V178) update them as the invoking role; remove these three
-- rows when design DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md Step 0 makes those triggers SECURITY DEFINER.
CREATE OR REPLACE FUNCTION ui2_apply_script_runner_role_grants(p_role TEXT DEFAULT 'ui2_script_runner')
RETURNS INTEGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
DECLARE
    v_rec RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = p_role) THEN
        BEGIN
            EXECUTE format('CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', p_role);
        EXCEPTION WHEN insufficient_privilege OR duplicate_object THEN
            NULL;
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = p_role) THEN
        RAISE NOTICE 'database role % does not exist; its grants are skipped until it exists', p_role;
        RETURN 1;
    END IF;
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', p_role);
    BEGIN
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), p_role);
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE NOTICE 'CONNECT on database % was not granted to % (not the database owner)', current_database(), p_role;
    END;
    FOR v_rec IN SELECT * FROM (VALUES
        ('jobs', 'SELECT, UPDATE'),
        ('job_step_attempt', 'SELECT, INSERT, UPDATE'),
        ('module_runtime_control', 'SELECT'),
        ('runtime_task_lease', 'SELECT, UPDATE, DELETE'),
        ('endpoint_admission', 'SELECT, UPDATE, DELETE'),
        ('runtime_instance_heartbeat', 'SELECT, INSERT, UPDATE'),
        ('failover_dispatch_intent', 'SELECT'),
        ('failover_run', 'SELECT, UPDATE'),
        ('failover_resume', 'SELECT, UPDATE'),
        ('pan_config_sync', 'SELECT, UPDATE')
    ) AS g(tbl_name, privs)
    LOOP
        IF to_regclass('public.' || v_rec.tbl_name) IS NOT NULL THEN
            EXECUTE format('GRANT %s ON TABLE public.%I TO %I', v_rec.privs, v_rec.tbl_name, p_role);
        END IF;
    END LOOP;
    -- Called by the module heartbeat (ModuleRuntimeRepository.heartbeat) and, inside it, by the quarantine release.
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_release_orphan_quarantines()'),
        ('ui2_quarantine_owner_gone(text)'),
        ('ui2_release_settled_failover_leases(text)'),
        ('ui2_failover_lease_settled(text)'),
        ('ui2_release_settled_config_sync_leases()'),
        ('ui2_config_sync_lease_settled(text)')
    ) AS f(signature)
    LOOP
        IF to_regprocedure('public.' || v_rec.signature) IS NOT NULL THEN
            EXECUTE format('GRANT EXECUTE ON FUNCTION public.%s TO %I', v_rec.signature, p_role);
        END IF;
    END LOOP;
    EXECUTE format('GRANT UPDATE (admission_grants, admission_wait_ms, drain_ack_at, drain_ack_generation, '
        || 'drain_generation, drain_requested, owner_heartbeat_at, owner_instance, pool_active, pool_idle, pool_pending, '
        || 'pool_timeouts, pool_wait_nanos) ON TABLE public.module_runtime_control TO %I', p_role);
    RETURN 0;
END
$fn$;
REVOKE ALL ON FUNCTION ui2_apply_script_runner_role_grants(TEXT) FROM PUBLIC;

SELECT ui2_apply_script_runner_role_grants();
