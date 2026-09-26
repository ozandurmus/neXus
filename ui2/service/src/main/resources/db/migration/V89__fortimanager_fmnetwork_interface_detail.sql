-- FortiManager interface status diagnostic; first use measures output before assigning semantics.
SELECT set_config('app.actor_fingerprint', 'migration:V89_fortimanager_fmnetwork_interface_detail', true);
SELECT set_config('app.action_id', 'fortimanager_fmnetwork_detail_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('fmg_ssh_fmnetwork_interface_detail', 'fortinet', 'fortimanager', 'cli', 'SSH_EXEC',
  'diagnose fmnetwork interface detail <interface>', 'read', 'SIGNED_OFF', 60, 'none',
  'once per named physical interface per inventory', 'reuse inventory SSH session',
  'refused, missing, or unrecognized output -> physical link UNKNOWN; preserve configured state',
  'interface identity and hardware data; log only masked shape and safe status enum', '[]'::jsonb,
  'FortiManager 7.4 CLI Reference: fmnetwork interface detail; FORTINET_CONTRACT.md')
ON CONFLICT (gate_id) DO NOTHING;
