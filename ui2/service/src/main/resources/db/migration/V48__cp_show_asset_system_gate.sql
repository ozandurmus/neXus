-- V48 -- Check Point serial number read (PLATFORM_IDENTITY_FACTS_CONTRACT §3).
-- The Product Owner ran `clish -c "show asset system"` by hand on a Smart-1
-- appliance on 2026-09-22 and supplied the output (docs/design/
-- CP_PLATFORM_IDENTITY_MEASUREMENTS.md); that measurement is the sign-off.
SELECT set_config('app.actor_fingerprint', 'migration:V48_cp_show_asset_system_gate', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_identity_show_asset_system', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'clish -c ''show asset system''', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'the inventory session', 'an open server may report no serial (N/A) -- a result, not an error',
     'none', '[]'::jsonb, 'docs/design/PLATFORM_IDENTITY_FACTS_CONTRACT.md §3 -- measured 2026-09-22');
