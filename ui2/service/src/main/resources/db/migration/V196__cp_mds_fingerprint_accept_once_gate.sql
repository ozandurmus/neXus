-- V196 -- One-off: the management-server HA read once with the API certificate fingerprint accepted automatically.
-- PO approval 2026-11-09 (chat: "fingerprint ok kabul edebilirsin", then "Yes, send it once") of this exact literal:
--   mgmt_cli -r true --unsafe-auto-accept true -f json show-ha-status
-- Context: after 2026-11-06 16:45 the MDS API certificate fingerprint changed; every MDS-level mgmt_cli read (the HA
-- read, V194, and the policy collection's show-domains) stops at the fingerprint prompt "(y/n) [n] ?" and times out.
-- The agent cannot open a manual device session (AGENTS.md), so the acceptance goes through neXus, once.
-- Scope: management servers only; sent only while ui2-worker runs with UI2_CP_MDS_FINGERPRINT_ACCEPT_ONCE=1, which is set
-- for one inventory run and removed right after. All other reads keep normal certificate verification, so a later
-- certificate change prompts again. The --unsafe-auto-accept flag is documented in Check Point's mgmt_cli option list
-- (CheckMates "mgmt_cli options reference"), not in the Management API reference.
SELECT set_config('app.actor_fingerprint', 'migration:V196_cp_mds_fingerprint_accept_once_gate', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_identity_mds_ha_status_accept_once', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC',
     'mgmt_cli -r true --unsafe-auto-accept true -f json show-ha-status',
     'read', 'SIGNED_OFF', 60, 'none', 'once, one PO-approved inventory run (2026-11-09)',
     'the inventory session (interactive Expert shell)',
     'as cp_identity_mds_ha_status; a vendor error or no answer leaves the HA role UNKNOWN',
     'as cp_identity_mds_ha_status: only the local ha-state and the server count are kept',
     '["outcome", "servers", "role"]'::jsonb,
     'docs/design/CP_MEASUREMENTS_2026_11_06_AFTERNOON.md §5 (PO chat approval 2026-11-09)')
ON CONFLICT (gate_id) DO NOTHING;
