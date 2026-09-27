-- PO-approved FortiGate full configuration sections (2026-09-27).
SELECT set_config('app.actor_fingerprint', 'migration:V98_fortigate_full_config_sections', true);
SELECT set_config('app.action_id', 'fortigate_full_config_sections_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('fgt_show_full_configuration_system_global', 'fortinet', 'fortigate', 'top_or_global', 'SSH_EXEC',
  'show full-configuration system global', 'read', 'SIGNED_OFF', 600,
  'once inside config global when refused at the top level', 'once per configuration/backup collection',
  'same SSH session as show', 'refusal or empty answer -> original show section retained, warning logged',
  'full configuration contains secrets; same withholding as FortiGate show', '[]'::jsonb, 'FortiOS CLI reference; PO 2026-09-27'),
 ('fgt_show_full_configuration_system_interface', 'fortinet', 'fortigate', 'top_or_global', 'SSH_EXEC',
  'show full-configuration system interface', 'read', 'SIGNED_OFF', 600,
  'once inside config global when refused at the top level', 'once per configuration/backup collection',
  'same SSH session as show', 'refusal or empty answer -> original show section retained, warning logged',
  'full configuration contains secrets; same withholding as FortiGate show', '[]'::jsonb, 'FortiOS CLI reference; PO 2026-09-27'),
 ('fgt_show_full_configuration_system_ha', 'fortinet', 'fortigate', 'top_or_global', 'SSH_EXEC',
  'show full-configuration system ha', 'read', 'SIGNED_OFF', 600,
  'once inside config global when refused at the top level', 'once per configuration/backup collection',
  'same SSH session as show', 'refusal or empty answer -> original show section retained, warning logged',
  'full configuration contains secrets; same withholding as FortiGate show', '[]'::jsonb, 'FortiOS CLI reference; PO 2026-09-27')
ON CONFLICT (gate_id) DO NOTHING;
