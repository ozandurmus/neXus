-- V195 -- Check Point management server: cpinfo issued directly in the interactive Expert shell.
-- PO approval 2026-11-07 (chat, "Onaylıyorum") of this exact CLASS_0 literal, the original V47 form the PO ran by hand
-- on 2026-09-22:
--   cpinfo -y all
-- Measured 2026-11-06/07 on the R82 management server: the login-shell form `bash -lc 'cpinfo -y all'` (V49, needed only
-- on the bare exec channel) answered 2 bytes (CR LF) after the echo and then nothing for 60 s, three times; the PO
-- confirms the command itself works on that version. The interactive Expert shell already carries the Check Point
-- environment, as the policy collector's mgmt_cli reads in the same kind of shell show.
-- Scope: management servers only, once per inventory run, the inventory's interactive Expert session, 60 s, no retry.
-- Gateways keep cp_identity_cpinfo_hotfixes (V47/V49). Seed data only; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V195_cp_mds_cpinfo_direct_gate', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_identity_cpinfo_hotfixes_mds', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC',
     'cpinfo -y all',
     'read', 'SIGNED_OFF', 60, 'none', 'once per management server per inventory run',
     'the inventory session (interactive Expert shell)',
     'no jumbo line or no answer within 60 s leaves the hotfix level UNKNOWN; the inventory never fails on it',
     'none (hotfix names and takes only)',
     '[]'::jsonb,
     'docs/design/CP_MEASUREMENTS_2026_11_06_AFTERNOON.md (PO chat approval 2026-11-07)')
ON CONFLICT (gate_id) DO NOTHING;
