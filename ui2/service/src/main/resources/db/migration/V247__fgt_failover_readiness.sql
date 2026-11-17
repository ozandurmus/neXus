-- V247 -- FortiGate (FGCP) failover readiness, Phase 1 (docs/design/PO_DECISION_RECORD_2026_11_17_FGT_FAILOVER_READINESS_READS.md;
-- PO chat approval 2026-11-17, "Evet, onaylıyorum", exact literals).
--   1. Three permanent gate rows, inserted SIGNED_OFF (the Product Owner approved the exact literals): diagnose sys ha
--      checksum cluster, diagnose sys ha dump-by group, execute ha failover status. All three are reads, run only when a
--      readiness run runs, on the enrolled primary address, global context when VDOMs are on, 30 s, no retry, once per run.
--      The already-approved FortiGate reads (get system status, get system ha status, show full-configuration system ha,
--      the context moves) are reused as they are. diagnose sys session stat is NOT added.
--   2. failover_run.vendor accepts 'fortinet' (readiness runs only). The approval table keeps its own vendor check:
--      an approval for a FortiGate cluster stays impossible.
--   3. ui2_job_module() maps the readiness capability fgt_failover_readiness to the failover module (V212's mapping
--      byte-for-byte plus this one capability).
-- The switch UI2_FGT_FAILOVER_READINESS is off by default on the service and on the worker.
-- Dry-run in BEGIN/ROLLBACK on the live database before rollout.
SELECT set_config('app.actor_fingerprint', 'migration:V247_fgt_failover_readiness', true);
SELECT set_config('app.action_id', 'fgt_failover_readiness_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('fgt_failover_checksum_cluster', 'fortinet', 'fortigate', 'top_or_global', 'SSH_EXEC', 'diagnose sys ha checksum cluster', 'read',
  'SIGNED_OFF', 30, 'none', 'once per run, only when a readiness run runs',
  'one SSH session on the enrolled cluster address (global context when VDOMs are on), commands one at a time with a configurable pause',
  'refused, unparsable or timed out -> the check is UNKNOWN (fail-closed); nothing is retried',
  'output names serials and hostnames; reduced in memory to derived values, never stored or recorded in the transcript',
  '[]'::jsonb, 'FortiOS CLI reference (diagnose sys ha checksum cluster); checksum equality of the two members (check 4); PO decision record docs/design/PO_DECISION_RECORD_2026_11_17_FGT_FAILOVER_READINESS_READS.md (PO chat approval 2026-11-17)'),
 ('fgt_failover_dump_by_group', 'fortinet', 'fortigate', 'top_or_global', 'SSH_EXEC', 'diagnose sys ha dump-by group', 'read',
  'SIGNED_OFF', 30, 'none', 'once per run, only when a readiness run runs',
  'one SSH session on the enrolled cluster address (global context when VDOMs are on), commands one at a time with a configurable pause',
  'refused, unparsable or timed out -> the check is UNKNOWN (fail-closed); nothing is retried',
  'output names serials and hostnames; reduced in memory to derived values, never stored or recorded in the transcript',
  '[]'::jsonb, 'FortiOS CLI reference (diagnose sys ha dump-by group); per-member HA uptime (check 8); PO decision record docs/design/PO_DECISION_RECORD_2026_11_17_FGT_FAILOVER_READINESS_READS.md (PO chat approval 2026-11-17)'),
 ('fgt_failover_ha_failover_status', 'fortinet', 'fortigate', 'top_or_global', 'SSH_EXEC', 'execute ha failover status', 'read',
  'SIGNED_OFF', 30, 'none', 'once per run, only when a readiness run runs',
  'one SSH session on the enrolled cluster address (global context when VDOMs are on), commands one at a time with a configurable pause',
  'refused, unparsable or timed out -> the check is UNKNOWN (fail-closed); nothing is retried',
  'output names serials and hostnames; reduced in memory to derived values, never stored or recorded in the transcript',
  '[]'::jsonb, 'FortiOS CLI reference (execute ha failover status); is a failover flag latched (check 8); PO decision record docs/design/PO_DECISION_RECORD_2026_11_17_FGT_FAILOVER_READINESS_READS.md (PO chat approval 2026-11-17)')
ON CONFLICT (gate_id) DO NOTHING;

ALTER TABLE failover_run DROP CONSTRAINT failover_run_vendor_check;
ALTER TABLE failover_run ADD CONSTRAINT failover_run_vendor_check
    CHECK (vendor IN ('check_point', 'palo_alto', 'cisco_asa', 'fortinet'));

CREATE OR REPLACE FUNCTION ui2_job_module(capability TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT CASE
 WHEN capability IN ('cp_policy_collect','pan_policy_collect') THEN 'policy'
 WHEN capability IN ('cp_gateway_backup','cp_spark_sftp_backup','cp_gaia_snapshot','pan_device_state_backup','cp_mds_export','https_vendor_backup','rdw_cc_config_backup','asa_config_backup','fgt_config_backup','credential_canary') THEN 'backup'
 WHEN capability IN ('device_confirm_check_point','device_confirm_palo_alto','device_confirm_https','device_confirm_cisco_asa','device_confirm_fortigate','cp_inventory_collect','pan_inventory_collect','https_inventory_collect','asa_inventory_collect','fgt_inventory_collect','cp_discovery_enumerate','pan_discovery_enumerate','rdw_discovery_enumerate','fmg_discovery_enumerate','bcmc_discovery_enumerate') THEN 'inventory'
 WHEN capability IN ('cp_failover_readiness','pan_failover_readiness','cp_cluster_failover','pan_cluster_failover','pan_failover_config_sync','asa_cluster_failover','fgt_failover_readiness') THEN 'failover'
 WHEN capability IN ('diagnostic_read','fmg_interface_detail','pan_syslog_measure','cp_system_log_collect','pan_system_log_collect','cp_spike_layout_review','path_validation') THEN 'diagnostics'
 WHEN capability IN ('cp_configuration_collect','pan_configuration_collect','fgt_configuration_collect','asa_configuration_collect','proxysg_configuration_collect') THEN 'configuration'
 WHEN capability = 'packet_analysis_run' THEN 'packet_analysis'
 ELSE NULL END
$$;
