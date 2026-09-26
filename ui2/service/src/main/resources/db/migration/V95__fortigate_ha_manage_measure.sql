-- V95 -- one-shot measurement (PO 2026-09-27: "Forti tarafını deneyelim"): from the HA primary's own SSH session, list
-- the cluster units and open a CLI session on the secondary over the HA link (execute ha manage), read its identity and
-- HA status with the already gated reads, exit. The password prompt is answered from the credential store, never on the
-- command line and never logged. No configuration change.
SELECT set_config('app.actor_fingerprint', 'migration:V95_fortigate_ha_manage_measure', true);
SELECT set_config('app.action_id', 'fortigate_ha_manage_measure_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('fgt_execute_ha_manage_list', 'fortinet', 'fortigate', 'cli', 'SSH_EXEC', 'execute ha manage ?', 'read', 'SIGNED_OFF', 30,
  'none', 'once per measurement', 'same SSH session (HA primary)', 'refused -> measurement ends, logged',
  'none', '[]'::jsonb, 'Fortinet Technical Tip: Managing individual cluster units with execute ha manage; PO 2026-09-27'),
 ('fgt_execute_ha_manage_login', 'fortinet', 'fortigate', 'cli', 'SSH_EXEC', 'execute ha manage <index> <username>', 'read', 'SIGNED_OFF', 30,
  'none', 'once per measurement', 'same SSH session; password prompt answered from the credential store; exit returns to the primary',
  'refused or no prompt back -> exit, measurement ends, logged', 'password prompt (answered, never logged)', '[]'::jsonb,
  'Fortinet Technical Tip: How to access the secondary unit from the primary with execute ha manage; PO 2026-09-27')
ON CONFLICT (gate_id) DO NOTHING;
