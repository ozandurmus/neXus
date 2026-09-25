-- V76 -- ProxySG backup through the Symantec Management Center (PO 2026-09-25, "devam" after the read-only measurement in
-- VENDOR_BACKUP_CONTRACTS §8a): PUT /api/devices/{uuid}/command with exactly "show version" or "show configuration".
-- The MC's session on the ProxySG is at #(config), so these two literals are the whole allowlist -- nothing else is
-- ever sent. Measured on the test proxy: show configuration 4.48 MB, 71,941 lines, 1.8 s, BEGIN/END section markers.
SELECT set_config('app.actor_fingerprint', 'migration:V76_bluecoat_mc_device_commands', true);
SELECT set_config('app.action_id', 'bluecoat_mc_device_commands_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('bluecoat_mc_command_show_version', 'bluecoat', 'bluecoat_management_center', 'config_mode_via_mc', 'HTTPS',
  'PUT /api/devices/{uuid}/command "show version"', 'read', 'SIGNED_OFF', 120, 'none', 'once per ProxySG per backup job',
  'one client per run', 'status != SUCCESS -> that ProxySG failed, named in the manifest', 'serial number (encrypted at rest)',
  '[]'::jsonb, 'VENDOR_BACKUP_CONTRACTS_2026_09_22.md §8a; MC on-box guide PUT /devices/{uuid}/command'),
 ('bluecoat_mc_command_show_configuration', 'bluecoat', 'bluecoat_management_center', 'config_mode_via_mc', 'HTTPS',
  'PUT /api/devices/{uuid}/command "show configuration"', 'read', 'SIGNED_OFF', 120, 'none', 'once per ProxySG per backup job',
  'one client per run', 'status != SUCCESS or no BEGIN/END markers -> not stored, named in the manifest',
  'the configuration, including hashed secrets (encrypted at rest, never logged)', '[]'::jsonb,
  'VENDOR_BACKUP_CONTRACTS_2026_09_22.md §8a; measured on the test proxy 2026-09-25')
ON CONFLICT (gate_id) DO NOTHING;
