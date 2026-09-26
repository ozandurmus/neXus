-- PO-approved FortiGate HA role read (2026-09-26).
SELECT set_config('app.actor_fingerprint', 'migration:V94_fortigate_ha_status', true);
SELECT set_config('app.action_id', 'fortigate_ha_status_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('fgt_get_system_ha_status', 'fortinet', 'fortigate', 'cli', 'SSH_EXEC', 'get system ha status', 'read', 'SIGNED_OFF', 60,
  'none', 'once per inventory/confirm', 'same SSH session', 'refusal -> role unknown (logged)',
  'none', '[]'::jsonb, 'FortiOS CLI reference; PO 2026-09-26')
ON CONFLICT (gate_id) DO NOTHING;
