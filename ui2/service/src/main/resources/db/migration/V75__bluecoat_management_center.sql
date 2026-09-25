-- V75 -- Symantec (Blue Coat) Management Center (PO 2026-09-25, "ProxySG through the management server"): the MC REST
-- API on 8082 with basic auth; one read, its managed device list, for confirm and inventory. MEASURE FIRST: the first
-- run logs the list's field names and counts only. Backup through the MC is decided after that measurement.
SELECT set_config('app.actor_fingerprint', 'migration:V75_bluecoat_management_center', true);
SELECT set_config('app.action_id', 'bluecoat_management_center_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('bluecoat_mc_devices', 'bluecoat', 'bluecoat_management_center', 'not_applicable', 'HTTPS', 'GET /api/devices', 'read', 'SIGNED_OFF', 30,
  'none', 'once per confirm, once per inventory read', 'one client per run', '401/403 -> authentication_failed; other non-2xx -> failed',
  'device names and addresses (names shown masked to aiview; addresses never persisted)', '[]'::jsonb,
  'Broadcom TechDocs: Management Center REST API (https://<mc>:8082/api), Best Practices (GET /devices); PO 2026-09-25')
ON CONFLICT (gate_id) DO NOTHING;
