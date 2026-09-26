-- V85 -- FortiGate configuration plane and FortiManager interface states (PO 2026-09-26: "Fortilerde config yok";
-- "ssh imkanın var, interface niye unknown?"). The FortiGate configuration read reuses the gated top-level "show"
-- (fgt_show_configuration, V80): a parse-scope extension, no new device command. FortiManager's link states come from
-- its SSH CLI, one read, in the inventory's own SSH login.
SELECT set_config('app.actor_fingerprint', 'migration:V85_fortinet_configuration_and_fmg_ssh', true);
SELECT set_config('app.action_id', 'fortinet_configuration_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('fmg_ssh_get_system_interface', 'fortinet', 'fortimanager', 'cli', 'SSH_EXEC', 'get system interface', 'read', 'SIGNED_OFF', 60,
  'none', 'once per inventory', 'one SSH session per inventory', 'refused or unreadable -> states stay unknown, logged',
  'interface addresses (masked to aiview)', '[]'::jsonb, 'FortiManager CLI reference (get system interface); PO 2026-09-26')
ON CONFLICT (gate_id) DO NOTHING;
