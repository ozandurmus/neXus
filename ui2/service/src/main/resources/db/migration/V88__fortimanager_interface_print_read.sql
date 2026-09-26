-- FortiManager documented interface diagnostic; output semantics are measured before parsing.
SELECT set_config('app.actor_fingerprint', 'migration:V88_fortimanager_interface_print_read', true);
SELECT set_config('app.action_id', 'fortimanager_interface_print_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('fmg_ssh_diagnose_system_print_interface', 'fortinet', 'fortimanager', 'cli', 'SSH_EXEC',
  'diagnose system print interface <interface>', 'read', 'SIGNED_OFF', 60, 'none',
  'once per named interface per inventory', 'reuse inventory SSH session',
  'refused, missing, or unrecognized output -> physical link UNKNOWN; preserve configured state',
  'hardware identifiers and addresses; log only masked shape', '[]'::jsonb,
  'FortiManager 7.4 CLI Reference: system print interface; FORTINET_CONTRACT.md')
ON CONFLICT (gate_id) DO NOTHING;
