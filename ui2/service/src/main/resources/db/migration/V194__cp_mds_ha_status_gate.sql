-- V194 -- Check Point management-server HA role (Management API show-ha-status).
-- PO approval 2026-11-07 (chat, exact literal "Onaylıyorum") of this exact CLASS_0 literal:
--   mgmt_cli -r true -f json show-ha-status
-- Evidence: Check Point Management API reference, command show-ha-status ("Retrieve domain high availability
-- status"), in API v1.9.1, v2 and v2.1; no request fields. Without -d the read runs at the Multi-Domain Server
-- level, as the approved show-domains read does. docs/design/CP_MEASUREMENTS_2026_11_06_AFTERNOON.md §4.
--
-- Scope: management servers only, once per inventory run, in the inventory's interactive Expert session (mgmt_cli
-- needs the Check Point environment of that shell). Only the ha-state of the server whose ip-address equals the
-- device's own connection address is kept, plus the server count; names and addresses are parsed in memory and
-- discarded. A vendor error, no match, several matches or an unexpected shape leaves the HA role UNKNOWN.
-- Seed data only; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V194_cp_mds_ha_status_gate', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_identity_mds_ha_status', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC',
     'mgmt_cli -r true -f json show-ha-status',
     'read', 'SIGNED_OFF', 60, 'none', 'once per management server per inventory run',
     'the inventory session (interactive Expert shell)',
     'vendor error, no server matching the device address, several matches or unexpected shape -> HA role stays UNKNOWN; the inventory never fails on it',
     'server names and addresses: parsed in memory, only the local ha-state and the server count are kept',
     '["outcome", "servers", "role"]'::jsonb,
     'docs/design/CP_MEASUREMENTS_2026_11_06_AFTERNOON.md §4 (PO chat approval 2026-11-07)')
ON CONFLICT (gate_id) DO NOTHING;
