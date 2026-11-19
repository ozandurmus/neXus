-- V252 -- Operations static route -- phase R0 (approved reads, pre-checks, command-plan preview; ZERO writes).
-- Authority:
--   docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md (APPROVED -- PO 2026-11-18, chat verbatim: "go komutlar ok")
--   docs/design/OPERATIONS_STATIC_ROUTE_CHANGE_CONTRACT_DRAFT_2026_11_17.md (sections 1.3, 4.1, 5.1/5.3/5.4, path-confirmation appendix, R0 approval appendix).
--
-- Scope of this migration:
--   1. Seven NEW CLASS_0 read gate rows inserted SIGNED_OFF:
--      - R0-CP-1  cp_route_show_route_static      (clish -c 'show route static', expert, SSH_EXEC, 30 s)
--      - R0-CP-2  cp_route_ip_route_get           (ip -4 route get <DST>, expert, SSH_EXEC, 30 s)
--      - R0-PAN-1 pan_route_show_vr_static_route  (type=config&action=show&xpath=.../static-route, not_applicable, PAN_XML_API, 30 s)
--      - R0-PAN-3 pan_route_check_pending_changes (type=op&cmd=<check><pending-changes/></check>, not_applicable, PAN_XML_API, 30 s)
--      - R0-ASA-3 asa_route_changeto_context      (changeto context <CTX_NAME>, context, SSH_INTERACTIVE, 30 s)
--      - R0-ASA-4 asa_route_show_running_route    (show running-config route, privileged_exec, SSH_INTERACTIVE, 30 s)
--      - R0-ASA-6 asa_route_show_route_host       (show route <HOST>, privileged_exec, SSH_INTERACTIVE, 30 s)
--   2. Widened frequency/caller statements on the nine reused SIGNED_OFF CLASS_0 read gates:
--      cp_inventory_ip_route_show_table_all (R0-CP-3), cp_inventory_cphaprob_stat (R0-CP-4),
--      path_validation_pan_fib_lookup (R0-PAN-2), pan_configuration_effective_running (R0-PAN-4),
--      pan_inventory_show_ha_state (R0-PAN-5), pan_inventory_show_routing_route (R0-PAN-6),
--      asa_show_mode (R0-ASA-1), asa_show_curpriv (R0-ASA-2), asa_show_route (R0-ASA-5).
--      R0-CP-5 (anti-spoofing topology) is NOT added (stays UNKNOWN). FortiGate is out of scope (FortiManager-managed).
--      Zero write gates are added.
--   3. ui2_job_module(capability TEXT): V247's mapping byte-for-byte plus static_route_preview in the diagnostics branch.
--   4. static_route_preview table: stores one R0 read/pre-check/preview record per request (mutation_possible constrained
--      to FALSE; derived pre-checks, parsed before-state path, deterministic command plan and inverse plan with SHA-256;
--      raw vendor output never stored).
-- Dry-run in BEGIN/ROLLBACK on the live database before rollout.
SELECT set_config('app.actor_fingerprint', 'migration:V252_static_route_r0_preview', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_route_show_route_static', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'clish -c ''show route static''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per approved preview or job',
     'one session or API key per member per job',
     'Clish error or lock text (Database is locked) is GAIA_DB_LOCKED, a result not a retry; empty output means no static routes',
     'none',
     '["routeCount", "routeTableSha256", "unsupportedRouteAttribute"]'::jsonb,
     'docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md R0-CP-1 (PO chat approval 2026-11-18: "go komutlar ok")'),
    ('cp_route_ip_route_get', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'ip -4 route get <DST>', 'read', 'SIGNED_OFF', 30, 'none',
     'once before and once after a change per member, preview included',
     'one session or API key per member per job',
     'error, missing route line or unknown route type is UNKNOWN verdict, never a pass',
     'none',
     '["selectedPrefix", "nextHop", "egressInterface", "routeSourceType", "digest"]'::jsonb,
     'docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md R0-CP-2 (PO chat approval 2026-11-18: "go komutlar ok")'),
    ('pan_route_show_vr_static_route', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     'type=config&action=show&xpath=/config/devices/entry[@name=''localhost.localdomain'']/network/virtual-router/entry[@name=''<VR>'']/routing-table/ip/static-route', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per preview or job',
     'one session or API key per member per job',
     'missing VR is ROUTING_CONTEXT_NOT_FOUND; API status other than success is UNKNOWN',
     'none',
     '["routeCount", "digest"]'::jsonb,
     'docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md R0-PAN-1 (PO chat approval 2026-11-18: "go komutlar ok")'),
    ('pan_route_check_pending_changes', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     'type=op&cmd=<check><pending-changes/></check>', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per approved preview or job',
     'one session or API key per member per job',
     'any other answer shape is UNKNOWN and stops the job',
     'none',
     '["pendingChanges"]'::jsonb,
     'docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md R0-PAN-3 (PO chat approval 2026-11-18: "go komutlar ok")'),
    ('asa_route_changeto_context', 'cisco_asa', 'cisco_asa_firewall', 'context', 'SSH_INTERACTIVE',
     'changeto context <CTX_NAME>', 'read', 'SIGNED_OFF', 30, 'none',
     'once per context per preview or job',
     'one session or API key per member per job',
     'any prompt other than <host>/<CTX_NAME># aborts with UNEXPECTED_PROMPT; a refusal ends the read, nothing further is sent',
     'none',
     '["success", "promptShape"]'::jsonb,
     'docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md R0-ASA-3 (PO chat approval 2026-11-18: "go komutlar ok")'),
    ('asa_route_show_running_route', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_INTERACTIVE',
     'show running-config route', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per approved preview or job',
     'one session or API key per member per job',
     'empty output means no static routes in that context; unexpected prompt aborts',
     'none',
     '["routeCount", "digest", "unsupportedRouteAttribute"]'::jsonb,
     'docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md R0-ASA-4 (PO chat approval 2026-11-18: "go komutlar ok")'),
    ('asa_route_show_route_host', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_INTERACTIVE',
     'show route <HOST>', 'read', 'SIGNED_OFF', 30, 'none',
     'once before and once after a change',
     'one session or API key per member per job',
     'unparsed or "not in table" answer gives UNKNOWN verdict',
     'none',
     '["selectedPrefix", "nextHop", "egressInterface", "routeType", "digest"]'::jsonb,
     'docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md R0-ASA-6 (PO chat approval 2026-11-18: "go komutlar ok")')
ON CONFLICT (gate_id) DO NOTHING;

-- Reused gates: widen frequency/caller statements as approved in OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md.
UPDATE gate_registry SET max_frequency = max_frequency || '; static-route preview and change jobs (PO 2026-11-18): once per member per preview and once before and after a route change' WHERE gate_id = 'cp_inventory_ip_route_show_table_all' AND action_class = 'read' AND sign_off_state = 'SIGNED_OFF' AND max_frequency NOT LIKE '%static-route preview%';
UPDATE gate_registry SET max_frequency = max_frequency || '; static-route preview and change jobs (PO 2026-11-18): once per member per preview and once before and after a route change' WHERE gate_id = 'pan_inventory_show_routing_route' AND action_class = 'read' AND sign_off_state = 'SIGNED_OFF' AND max_frequency NOT LIKE '%static-route preview%';
UPDATE gate_registry SET max_frequency = max_frequency || '; static-route preview (PO 2026-11-18): once per member per preview or route-change job' WHERE gate_id = 'cp_inventory_cphaprob_stat' AND action_class = 'read' AND sign_off_state = 'SIGNED_OFF' AND max_frequency NOT LIKE '%static-route preview%';
UPDATE gate_registry SET max_frequency = max_frequency || '; static-route preview (PO 2026-11-18): once per member per preview or route-change job' WHERE gate_id = 'pan_configuration_effective_running' AND action_class = 'read' AND sign_off_state = 'SIGNED_OFF' AND max_frequency NOT LIKE '%static-route preview%';
UPDATE gate_registry SET max_frequency = max_frequency || '; static-route preview (PO 2026-11-18): once per member per preview or route-change job' WHERE gate_id = 'pan_inventory_show_ha_state' AND action_class = 'read' AND sign_off_state = 'SIGNED_OFF' AND max_frequency NOT LIKE '%static-route preview%';
UPDATE gate_registry SET max_frequency = max_frequency || '; static-route preview (PO 2026-11-18): once per member per preview or route-change job' WHERE gate_id = 'asa_show_mode' AND action_class = 'read' AND sign_off_state = 'SIGNED_OFF' AND max_frequency NOT LIKE '%static-route preview%';
UPDATE gate_registry SET max_frequency = max_frequency || '; static-route preview (PO 2026-11-18): once before and once after a route change per member, preview included' WHERE gate_id = 'path_validation_pan_fib_lookup' AND action_class = 'read' AND sign_off_state = 'SIGNED_OFF' AND max_frequency NOT LIKE '%static-route preview%';
UPDATE gate_registry SET max_frequency = max_frequency || '; static-route preview (PO 2026-11-18): once at session start and once after changeto context per preview or route-change job' WHERE gate_id = 'asa_show_curpriv' AND action_class = 'read' AND sign_off_state = 'SIGNED_OFF' AND max_frequency NOT LIKE '%static-route preview%';
UPDATE gate_registry SET max_frequency = max_frequency || '; static-route preview (PO 2026-11-18): once per context per preview and once before and after a route change' WHERE gate_id = 'asa_show_route' AND action_class = 'read' AND sign_off_state = 'SIGNED_OFF' AND max_frequency NOT LIKE '%static-route preview%';

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
 ELSE NULL END
$$;

-- Derived R0 preview record: zero writes exist in R0, and mutation_possible is constrained to FALSE.
-- No audit trigger: the preview submission is recorded in authz_decisions and the worker job through the jobs audit
-- trigger; keeping this table out of trg_audit_* prevents unmasked route prefixes/next-hops from entering audit_log.
CREATE TABLE IF NOT EXISTS static_route_preview (
    preview_id             TEXT        PRIMARY KEY CHECK (preview_id ~ '^srp-[0-9a-f-]{36}$'),
    idempotency_key        TEXT        NOT NULL UNIQUE,
    request_payload_sha256 TEXT        NOT NULL CHECK (request_payload_sha256 ~ '^[0-9a-f]{64}$'),
    requested_by           TEXT        NOT NULL,
    requested_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    unit_id                TEXT        NOT NULL,
    target_device_id       TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    vendor                 TEXT        NOT NULL CHECK (vendor IN ('check_point', 'palo_alto', 'cisco_asa')),
    routing_context        TEXT        NOT NULL,
    operation              TEXT        NOT NULL CHECK (operation IN ('ADD', 'DELETE')),
    destination_cidr       TEXT        NOT NULL CHECK (destination_cidr <> '0.0.0.0/0'),
    nexthop_ip             TEXT        NOT NULL,
    interface_name         TEXT,
    distance               INTEGER,
    metric                 INTEGER,
    priority               INTEGER,
    route_name             TEXT,
    device_comment         TEXT,
    representative_host    TEXT        NOT NULL,
    change_ticket_ref      TEXT,
    reason                 TEXT,
    job_id                 TEXT        UNIQUE REFERENCES jobs(job_id) ON DELETE SET NULL,
    state                  TEXT        NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'DONE', 'STOPPED')),
    verdict                TEXT        NOT NULL DEFAULT 'PENDING' CHECK (verdict IN ('PENDING', 'PASS', 'FAIL', 'UNKNOWN')),
    stop_code              TEXT,
    mutation_possible      BOOLEAN     NOT NULL DEFAULT FALSE CHECK (mutation_possible = FALSE),
    pre_route_table_sha256 TEXT,
    pre_checks_json        JSONB       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(pre_checks_json) = 'array'),
    before_path_json       JSONB       CHECK (before_path_json IS NULL OR jsonb_typeof(before_path_json) = 'object'),
    command_plan_json      JSONB       NOT NULL CHECK (jsonb_typeof(command_plan_json) = 'object'),
    inverse_plan_json      JSONB       NOT NULL CHECK (jsonb_typeof(inverse_plan_json) = 'object'),
    completed_at           TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_static_route_preview_device ON static_route_preview (target_device_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_static_route_preview_requested_at ON static_route_preview (requested_at DESC);

SELECT set_config('app.action_id', 'audit_redaction_policy_seed_by_migration', true);

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('static_route_preview', 'destination_cidr', 2, 'route destination prefix'),
    ('static_route_preview', 'nexthop_ip', 2, 'route next-hop IP address'),
    ('static_route_preview', 'representative_host', 2, 'representative host IP inside destination prefix'),
    ('static_route_preview', 'before_path_json', 2, 'parsed before-state path lookup fields'),
    ('static_route_preview', 'command_plan_json', 2, 'compiled preview command literals'),
    ('static_route_preview', 'inverse_plan_json', 2, 'compiled preview inverse command literals')
ON CONFLICT (table_name, column_name) DO NOTHING;

GRANT SELECT, INSERT, UPDATE ON static_route_preview TO ui2_app;

DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT, UPDATE'),
        ('ui2_diagnostics', 'SELECT, INSERT, UPDATE'),
        ('ui2_worker_general', 'SELECT, INSERT, UPDATE')
    ) AS g(role_name, privs)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.static_route_preview TO %I', v_rec.privs, v_rec.role_name);
        END IF;
    END LOOP;
END
$grants$;
