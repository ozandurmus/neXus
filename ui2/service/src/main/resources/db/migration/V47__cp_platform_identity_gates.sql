-- V47 -- Check Point platform identity reads (backlog
-- platform_identity_facts_on_configuration, Product Owner P1 2026-09-22:
-- "serial number and version information on the Configuration screen").
-- The Product Owner ran both commands by hand on an R81.20 management server
-- on 2026-09-22 and supplied the output (docs/design/
-- CP_PLATFORM_IDENTITY_MEASUREMENTS.md); that measurement is the sign-off.
-- Both are Expert-shell reads issued once per inventory run over the
-- session the inventory read already holds; nothing is written.
-- `show asset system` (serial number) is NOT seeded here: not yet measured.
SELECT set_config('app.actor_fingerprint', 'migration:V47_cp_platform_identity_gates', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_identity_cpinfo_hotfixes', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'cpinfo -y all', 'read', 'SIGNED_OFF', 60, 'none', 'once per device per run',
     'the inventory session', 'a device with no jumbo line reports no hotfix level, which is a result, not an error',
     'none', '[]'::jsonb, 'docs/design/PLATFORM_IDENTITY_FACTS_CONTRACT.md §3 -- measured 2026-09-22'),
    ('cp_identity_uptime', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'uptime', 'read', 'SIGNED_OFF', 15, 'none', 'once per device per run',
     'the inventory session', 'none', 'none', '[]'::jsonb,
     'docs/design/PLATFORM_IDENTITY_FACTS_CONTRACT.md §3 -- measured 2026-09-22');
