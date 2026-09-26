-- FortiManager physical NIC observation. Output semantics remain UNKNOWN until measured on the managed appliance.
SELECT set_config('app.actor_fingerprint', 'migration:V87_fortimanager_nic_read', true);
SELECT set_config('app.action_id', 'fortimanager_nic_read_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('fmg_ssh_diagnose_hardware_info_nic', 'fortinet', 'fortimanager', 'cli', 'SSH_EXEC',
  'diagnose hardware info nic <port>', 'read', 'SIGNED_OFF', 60, 'none',
  'once per named physical port per inventory', 'reuse inventory SSH session',
  'refused, missing, or unrecognized output -> physical link UNKNOWN; preserve configured state',
  'hardware identifiers and addresses; log only masked shape', '[]'::jsonb,
  'FORTINET_CONTRACT.md; FortiManager CLI reference: diagnose hardware info; PO 2026-09-26')
ON CONFLICT (gate_id) DO NOTHING;
