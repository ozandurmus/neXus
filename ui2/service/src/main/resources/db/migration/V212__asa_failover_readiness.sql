-- V212 -- Cisco ASA failover readiness, Phase 1 (docs/design/PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md,
-- DRAFT awaiting the Product Owner's exact-literal approval).
--   1. Four permanent gate rows, inserted DRAFTED (transport SSH_INTERACTIVE: the reads go through the interactive shell, and
--      the registry keeps one row per canonical key -- the V210 measurement rows hold the same literals under SSH_EXEC): a DRAFTED row never resolves KNOWN, so no command is sent until the
--      Product Owner approves the literals and a later migration signs them off. All four are reads.
--   2. failover_run.vendor accepts 'cisco_asa' (readiness runs only). the approval table keeps its own vendor check:
--      an approval for a Cisco ASA pair stays impossible.
--   3. ui2_job_module() maps the readiness capability asa_cluster_failover to the failover module (V207's mapping
--      byte-for-byte plus this one capability).
-- The switch UI2_ASA_FAILOVER_READINESS is off by default on the service and on the worker.
-- Dry-run in BEGIN/ROLLBACK on the live database before rollout.
SELECT set_config('app.actor_fingerprint', 'migration:V212_asa_failover_readiness', true);
SELECT set_config('app.action_id', 'asa_failover_readiness_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('asa_failover_show_failover', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_INTERACTIVE', 'show failover', 'read',
  'DRAFTED', 30, 'none', 'twice per run per member, at least 5 s apart (the second sample is the counter comparison of check 6)',
  'one SSH session per member (pager off, privilege 15, single context), commands one at a time with a configurable pause',
  'refused, unparsable or timed out -> the check is UNKNOWN (fail-closed); nothing is retried',
  'output names interfaces, addresses and serials; reduced in memory to derived values, never stored or recorded in the transcript',
  '[]'::jsonb, 'Cisco ASA command reference (show failover); PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md (DRAFT, awaiting PO approval)'),
 ('asa_failover_show_failover_state', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_INTERACTIVE', 'show failover state', 'read',
  'DRAFTED', 30, 'none', 'once per run per member',
  'one SSH session per member (pager off, privilege 15, single context), commands one at a time with a configurable pause',
  'refused, unparsable or timed out -> the check is UNKNOWN (fail-closed); nothing is retried',
  'output names interfaces, addresses and serials; reduced in memory to derived values, never stored or recorded in the transcript',
  '[]'::jsonb, 'Cisco ASA command reference (show failover state); PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md (DRAFT, awaiting PO approval)'),
 ('asa_failover_show_failover_history', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_INTERACTIVE', 'show failover history', 'read',
  'DRAFTED', 60, 'none', 'once per run per member',
  'one SSH session per member (pager off, privilege 15, single context), commands one at a time with a configurable pause',
  'refused, unparsable or timed out -> the check is UNKNOWN (fail-closed); nothing is retried',
  'output names interfaces, addresses and serials; reduced in memory to derived values, never stored or recorded in the transcript',
  '[]'::jsonb, 'Cisco ASA command reference (show failover history); PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md (DRAFT, awaiting PO approval)'),
 ('asa_failover_show_conn_count', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_INTERACTIVE', 'show conn count', 'read',
  'DRAFTED', 30, 'none', 'once per run per member',
  'one SSH session per member (pager off, privilege 15, single context), commands one at a time with a configurable pause',
  'refused, unparsable or timed out -> the check is UNKNOWN (fail-closed); nothing is retried',
  'output names interfaces, addresses and serials; reduced in memory to derived values, never stored or recorded in the transcript',
  '[]'::jsonb, 'Cisco ASA command reference (show conn count); PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md (DRAFT, awaiting PO approval)')
ON CONFLICT (gate_id) DO NOTHING;

ALTER TABLE failover_run DROP CONSTRAINT failover_run_vendor_check;
ALTER TABLE failover_run ADD CONSTRAINT failover_run_vendor_check
    CHECK (vendor IN ('check_point', 'palo_alto', 'cisco_asa'));

CREATE OR REPLACE FUNCTION ui2_job_module(capability TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT CASE
 WHEN capability IN ('cp_policy_collect','pan_policy_collect') THEN 'policy'
 WHEN capability IN ('cp_gateway_backup','cp_spark_sftp_backup','cp_gaia_snapshot','pan_device_state_backup','cp_mds_export','https_vendor_backup','rdw_cc_config_backup','asa_config_backup','fgt_config_backup','credential_canary') THEN 'backup'
 WHEN capability IN ('device_confirm_check_point','device_confirm_palo_alto','device_confirm_https','device_confirm_cisco_asa','device_confirm_fortigate','cp_inventory_collect','pan_inventory_collect','https_inventory_collect','asa_inventory_collect','fgt_inventory_collect','cp_discovery_enumerate','pan_discovery_enumerate','rdw_discovery_enumerate','fmg_discovery_enumerate','bcmc_discovery_enumerate') THEN 'inventory'
 WHEN capability IN ('cp_failover_readiness','pan_failover_readiness','cp_cluster_failover','pan_cluster_failover','pan_failover_config_sync','asa_cluster_failover') THEN 'failover'
 WHEN capability IN ('diagnostic_read','fmg_interface_detail','pan_syslog_measure','cp_system_log_collect','pan_system_log_collect','cp_spike_layout_review','path_validation') THEN 'diagnostics'
 WHEN capability IN ('cp_configuration_collect','pan_configuration_collect','fgt_configuration_collect','asa_configuration_collect','proxysg_configuration_collect') THEN 'configuration'
 WHEN capability = 'packet_analysis_run' THEN 'packet_analysis'
 ELSE NULL END
$$;
