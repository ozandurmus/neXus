-- V254 -- Check Point per-VS failover: the missing cpstat policy read in the VS context
-- (docs/design/PO_DECISION_RECORD_2026_11_19_CP_VS_CPSTAT_POLICY_GATE.md; the PO answered "Onaylıyorum" on 2026-11-19
-- to the exact literal bash -lc 'vsenv <VSID> && cpstat -f policy fw'). A VS failover run gates every command before any
-- contact and stopped with COMMAND_GATE_UNAVAILABLE (nothing sent) because only the default and vsenv 0 forms of this read
-- existed; every other per-VS failover row (_vsid, including down/up) was already SIGNED_OFF. One new read row, same
-- timeout, retry and projection as cp_policy_install_cpstat_vsenv0. Seed data only; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V254_cp_vs_cpstat_policy_gate', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_policy_install_cpstat_vsid', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv <VSID> && cpstat -f policy fw''', 'read', 'SIGNED_OFF', 30, 'none',
     'once pre, once post-switch and once post-return per member per failover run of a virtual system',
     'one SSH session per member per run; commands serial',
     'missing policy or install time -> UNKNOWN; blocks failover', 'policy identities stay in memory; only equality, presence and install-time skew are projected', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_11_19_CP_VS_CPSTAT_POLICY_GATE.md (RATIFIED)')
ON CONFLICT (gate_id) DO NOTHING;
