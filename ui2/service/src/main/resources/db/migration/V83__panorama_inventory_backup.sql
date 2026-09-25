-- V83 -- Panorama (PO 2026-09-25: "Panoramayı halledelim"; VENDOR_BACKUP_CONTRACTS §3). The firewall reads, scoped to
-- Panorama: show system info (its management interface, mask, default gateway, identity) and HA state for inventory;
-- device-state export for backup, else (measure first) the running configuration XML already read by config show;
-- the set-format CLI read as the second member (a Panorama backup without it is stored as partial).
SELECT set_config('app.actor_fingerprint', 'migration:V83_panorama_inventory_backup', true);
SELECT set_config('app.action_id', 'panorama_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('pan_panorama_show_system_info', 'palo_alto', 'panorama', 'not_applicable', 'PAN_XML_API', '<show><system><info/></system></show>', 'read', 'SIGNED_OFF', 60,
  'none', 'once per inventory', 'one key per run', 'no serial -> inventory failed', 'serial, hostname, management address (masked to aiview)',
  '[]'::jsonb, 'PAN-OS XML API (op show system info); the firewall read, Panorama scope'),
 ('pan_panorama_show_ha_state', 'palo_alto', 'panorama', 'not_applicable', 'PAN_XML_API', '<show><high-availability><state/></high-availability></show>', 'read', 'SIGNED_OFF', 60,
  'none', 'once per inventory', 'one key per run', 'no HA -> standalone', 'none', '[]'::jsonb, 'PAN-OS XML API; Panorama scope'),
 ('pan_panorama_export_device_state', 'palo_alto', 'panorama', 'not_applicable', 'PAN_XML_API', 'type=export&category=device-state', 'read', 'SIGNED_OFF', 300,
  'none', 'once per backup', 'one key per run', 'refused -> the running configuration XML is the member (logged)',
  'configuration with keys: inside the encrypted artefact only', '[]'::jsonb, 'VENDOR_BACKUP_CONTRACTS §3 (measure first)'),
 ('pan_panorama_config_show', 'palo_alto', 'panorama', 'not_applicable', 'PAN_XML_API', 'type=config&action=show', 'read', 'SIGNED_OFF', 120,
  'none', 'once per backup', 'one key per run', 'refused and device-state refused -> backup failed',
  'configuration: inside the encrypted artefact only', '[]'::jsonb, 'PAN-OS XML API (config show); Panorama scope')
ON CONFLICT (gate_id) DO NOTHING;
