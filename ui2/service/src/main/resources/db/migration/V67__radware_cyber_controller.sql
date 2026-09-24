-- V67 -- Radware DefensePro backup through Cyber Controller (RADWARE_CYBER_CONTROLLER_BACKUP_API_GATE_ENTRIES.md,
-- approved by the Product Owner 2026-09-24). Four REST calls on the Cyber Controller, all reads; the direct DefensePro
-- path (V64) stays as the fallback. A Radware Cyber Controller is enrolled as vendor radware, role management_server.
SELECT set_config('app.actor_fingerprint', 'migration:V67_radware_cyber_controller', true);
SELECT set_config('app.action_id', 'radware_cyber_controller_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('radware_cc_login', 'radware', 'radware_cyber_controller', 'not_applicable', 'HTTPS', 'POST /mgmt/system/user/login', 'read', 'SIGNED_OFF', 30,
  'none', 'once per job', 'the jobs own session only', 'non-2xx or no JSESSIONID -> authentication_failed, nothing further', 'request carries the password (credential store, memory only)', '[]'::jsonb, 'Cyber Controller REST 10.3.0 Server Login; 10.13 MEASURE FIRST'),
 ('radware_cc_alldevices', 'radware', 'radware_cyber_controller', 'not_applicable', 'HTTPS', 'GET /mgmt/system/config/itemlist/alldevices', 'read', 'SIGNED_OFF', 60,
  'none', 'once per confirm, once per backup job', 'same session', 'non-2xx -> confirm failed; target not listed -> direct DefensePro path', 'device names and addresses (parsed to a match yes/no and counts; never persisted)', '[]'::jsonb, 'Cyber Controller REST 10.3.0 alldevices; shape MEASURE FIRST'),
 ('radware_cc_getcfg', 'radware', 'radware_cyber_controller', 'not_applicable', 'HTTPS', 'GET /mgmt/device/byip/<deviceIp>/config/getcfg', 'read', 'SIGNED_OFF', 600,
  'none', 'once per device per backup job', 'same session', 'non-2xx, empty or HTML body -> backup failed, no retry', 'DefensePro configuration, private keys encrypted with the passphrase (encrypted at rest); saveToDb=false, includePrivateKeys=true', '[]'::jsonb, 'Cyber Controller REST 10.3.0 getcfg (also Vision 5.1.0)'),
 ('radware_cc_logout', 'radware', 'radware_cyber_controller', 'not_applicable', 'HTTPS', 'POST /mgmt/system/config/itemlist/systemuser/logout', 'read', 'SIGNED_OFF', 30,
  'none', 'once per job, always', 'same session', 'failure ignored (session expires)', 'none', '[]'::jsonb, 'Cyber Controller REST 10.3.0 logout');
