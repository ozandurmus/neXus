-- V81 -- FortiManager (PO 2026-09-25: "Fortimanager da hani IP hani route?"; "discovery yaparak diğer cihazları da
-- çekeceğim"). Its own interfaces and static routes for inventory; its ADOMs and each ADOM's FortiGates (with HA members)
-- for discovery. All JSON-RPC reads in the same login session; ADOM names checked before use in a URL.
SELECT set_config('app.actor_fingerprint', 'migration:V81_fortimanager_interfaces_routes_discovery', true);
SELECT set_config('app.action_id', 'fortimanager_reads_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('fmg_jsonrpc_system_interface', 'fortinet', 'fortimanager', 'not_applicable', 'HTTPS', 'POST /jsonrpc get /cli/global/system/interface', 'read', 'SIGNED_OFF', 60,
  'none', 'once per inventory', 'the inventory session', 'refused -> inventory stored without interfaces, logged',
  'interface addresses (masked to aiview)', '[]'::jsonb, 'FortiManager JSON API reference (cli/global/system/interface)'),
 ('fmg_jsonrpc_system_route', 'fortinet', 'fortimanager', 'not_applicable', 'HTTPS', 'POST /jsonrpc get /cli/global/system/route', 'read', 'SIGNED_OFF', 60,
  'none', 'once per inventory', 'the inventory session', 'refused -> inventory stored without routes, logged',
  'route destinations and gateways (masked to aiview)', '[]'::jsonb, 'FortiManager JSON API reference (cli/global/system/route)'),
 ('fmg_jsonrpc_dvmdb_adom', 'fortinet', 'fortimanager', 'not_applicable', 'HTTPS', 'POST /jsonrpc get /dvmdb/adom', 'read', 'SIGNED_OFF', 60,
  'none', 'once per discovery run', 'the discovery session', 'refused -> the plain device list, domain "root"',
  'ADOM names (masked to aiview)', '[]'::jsonb, 'FortiManager JSON API reference (dvmdb/adom)'),
 ('fmg_jsonrpc_dvmdb_adom_device', 'fortinet', 'fortimanager', 'not_applicable', 'HTTPS', 'POST /jsonrpc get /dvmdb/adom/<adom>/device (loadsub)', 'read', 'SIGNED_OFF', 60,
  'none', 'once per ADOM per discovery run', 'the discovery session', 'refused for an ADOM -> that ADOM skipped',
  'device names, serials, addresses (masked to aiview)', '[]'::jsonb, 'FortiManager JSON API reference (dvmdb/adom/{adom}/device)')
ON CONFLICT (gate_id) DO NOTHING;
