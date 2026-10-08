-- PO approved 2026-10-08: selected-bond membership reads only.
-- BOND is quoted and validated against the same member's same-pass summary.
-- Interface names and raw replies remain in memory; persisted traffic evidence contains counts only.
SELECT set_config('app.actor_fingerprint', 'migration:V142_cp_failover_bond_members', true);
SELECT set_config('app.action_id', 'gate_registry_insert_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields, source_document_pointer)
VALUES
    ('cp_failover_bond_members', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'cphaprob show_bond <BOND>', 'read', 'SIGNED_OFF', 30, 'none', 'once per selected bond per phase per member', 'one SSH session per member per run; commands serial', 'UNKNOWN', 'none', '[]'::jsonb, 'PO chat approval 2026-10-08; CP R82 CLI reference, Viewing Bond Interfaces'),
    ('cp_failover_bond_members_vsid', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''vsenv <VSID> && cphaprob show_bond <BOND>''', 'read', 'SIGNED_OFF', 30, 'none', 'once per selected bond per phase per member', 'one SSH session per member per run; commands serial', 'UNKNOWN', 'none', '[]'::jsonb, 'PO chat approval 2026-10-08; CP R82 CLI reference, Viewing Bond Interfaces');
