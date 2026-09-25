-- V82 -- ProxySGs as devices under their Symantec Management Center (PO 2026-09-25: "Cihazlar gelsin, oradaki IP'ler
-- rotalar"). Discovery reads the MC's device list (gate bluecoat_mc_devices, V75); an imported ProxySG is confirmed by
-- the MC's entry and read through the MC's command API with two more show commands. MEASURE FIRST: the first run logs
-- the output line shapes only (letters a, digits 9).
SELECT set_config('app.actor_fingerprint', 'migration:V82_proxysg_via_management_center', true);
SELECT set_config('app.action_id', 'proxysg_via_mc_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('bluecoat_mc_command_show_interface_all', 'bluecoat', 'bluecoat_management_center', 'config_mode_via_mc', 'HTTPS',
  'PUT /api/devices/{uuid}/command "show interface all"', 'read', 'SIGNED_OFF', 120,
  'none', 'once per ProxySG per inventory', 'one client per run', 'status != SUCCESS -> inventory failed with the MC status',
  'interface addresses (masked to aiview)', '[]'::jsonb, 'SGOS CLI reference (show interface); PO 2026-09-25'),
 ('bluecoat_mc_command_show_ip_route_table', 'bluecoat', 'bluecoat_management_center', 'config_mode_via_mc', 'HTTPS',
  'PUT /api/devices/{uuid}/command "show ip-route-table"', 'read', 'SIGNED_OFF', 120,
  'none', 'once per ProxySG per inventory', 'one client per run', 'status != SUCCESS -> inventory failed with the MC status',
  'route destinations and gateways (masked to aiview)', '[]'::jsonb, 'SGOS CLI reference (show ip-route-table); PO 2026-09-25')
ON CONFLICT (gate_id) DO NOTHING;
