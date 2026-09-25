-- V80 -- Fortinet (PO 2026-09-25: "Fortimanager ve GW'ler"; docs/design/FORTINET_CONTRACT.md). FortiGate over SSH, one
-- interactive shell per run: reads only; "config global" / "config vdom" / "edit <vdom>" / "end" only move between
-- contexts; paging ("--More--") is answered in the session, the device's console setting is never changed.
-- FortiManager over its JSON-RPC API (POST /jsonrpc): login, system status, managed devices, logout.
SELECT set_config('app.actor_fingerprint', 'migration:V80_fortinet_reads', true);
SELECT set_config('app.action_id', 'fortinet_reads_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('fgt_get_system_status', 'fortinet', 'fortigate', 'top_or_global', 'SSH_EXEC', 'get system status', 'read', 'SIGNED_OFF', 30,
  'once inside config global when refused at the top level', 'once per confirm, inventory and backup', 'one session per device per run',
  'no "Version: Forti..." -> not a FortiGate, failed', 'serial number and hostname (masked to aiview)', '[]'::jsonb,
  'FortiOS CLI reference (get system status); Backbox FortiGate SSH (VDOM) trail 23488971'),
 ('fgt_context_moves', 'fortinet', 'fortigate', 'context', 'SSH_EXEC', 'config global | config vdom | edit <vdom> | end', 'read', 'SIGNED_OFF', 30,
  'none', 'as the reads need', 'one session per device per run', 'a refused move leaves the next read to fail on its own',
  'none', '[]'::jsonb, 'FortiOS CLI reference (VDOM contexts); Backbox trail 23488971; VDOM names checked against [A-Za-z0-9_.-]{1,31}'),
 ('fgt_show_system_interface', 'fortinet', 'fortigate', 'global', 'SSH_EXEC', 'show system interface', 'read', 'SIGNED_OFF', 180,
  'none', 'once per inventory', 'one session per device per run', 'no edit blocks -> no interfaces recorded',
  'interface addresses (masked to aiview)', '[]'::jsonb, 'FortiOS CLI reference (system interface)'),
 ('fgt_get_routing_table', 'fortinet', 'fortigate', 'vdom', 'SSH_EXEC', 'get router info routing-table all', 'read', 'SIGNED_OFF', 180,
  'none', 'once per VDOM per inventory', 'one session per device per run', 'no rows -> no routes for that VDOM',
  'route destinations and next hops (masked to aiview)', '[]'::jsonb, 'FortiOS CLI reference (router info routing-table)'),
 ('fgt_show_configuration', 'fortinet', 'fortigate', 'top', 'SSH_EXEC', 'show', 'read', 'SIGNED_OFF', 600,
  'none', 'once per backup', 'one session per device per run', 'no "#config-version=" header -> backup refused, nothing stored',
  'full configuration: stored only inside the encrypted backup artefact, never logged', '[]'::jsonb,
  'Backbox trail 23488971 (the same top-level show); FortiOS CLI reference'),
 ('fmg_jsonrpc_login_logout', 'fortinet', 'fortimanager', 'not_applicable', 'HTTPS', 'POST /jsonrpc exec /sys/login/user, /sys/logout', 'read', 'SIGNED_OFF', 60,
  'none', 'once per confirm and inventory', 'one session per run', 'status code != 0 -> authentication_failed',
  'the password is in the login body, never logged; the session token lives for the run only', '[]'::jsonb,
  'FortiManager JSON API reference (sys/login/user)'),
 ('fmg_jsonrpc_sys_status', 'fortinet', 'fortimanager', 'not_applicable', 'HTTPS', 'POST /jsonrpc get /sys/status', 'read', 'SIGNED_OFF', 60,
  'none', 'once per confirm and inventory', 'one session per run', 'no Version -> not a FortiManager, failed',
  'serial number and hostname (masked to aiview)', '[]'::jsonb, 'FortiManager JSON API reference (sys/status)'),
 ('fmg_jsonrpc_dvmdb_device', 'fortinet', 'fortimanager', 'not_applicable', 'HTTPS', 'POST /jsonrpc get /dvmdb/device', 'read', 'SIGNED_OFF', 60,
  'none', 'once per inventory', 'one session per run', 'status code != 0 -> inventory failed',
  'managed device names and addresses (masked to aiview)', '[]'::jsonb, 'FortiManager JSON API reference (dvmdb/device)')
ON CONFLICT (gate_id) DO NOTHING;
