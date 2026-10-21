-- V174 -- Check Point failover: one shell channel per member per pass (docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md;
-- PO approval 2026-10-22, docs/design/PO_DECISION_RECORD_2026_10_22_CP_ONE_SHELL_SESSION.md, RATIFIED: "Onaylıyorum, önce
-- tek kümede"). Registers the framing literal (L1-L4) as a SIGNED_OFF read gate. The worker uses it only for runs whose
-- cluster is allow-listed (NEXUS_CP_FAILOVER_ONE_SHELL=enabled and NEXUS_CP_FAILOVER_ONE_SHELL_CLUSTERS); every other run
-- never reads this row. The approved read gates are not changed: each read is still resolved by its own SSH_EXEC row and
-- command key; the clusterXL_admin writes stay on SSH_EXEC.
-- Seed data only; re-running is a no-op (ON CONFLICT DO NOTHING).
SELECT set_config('app.actor_fingerprint', 'migration:V174_cp_failover_shell_framing_gate', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_failover_shell_framing', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_INTERACTIVE',
     'echo __NX_<NONCE>_BEGIN__; <READ>; echo __NX_<NONCE>_END_$?__', 'read', 'SIGNED_OFF', 30, 'none',
     'once per approved cp_failover read of a readiness, execution or resume run, wrapping that read; <NONCE> = 32 lowercase hex characters from SecureRandom, new per command; <READ> = the read''s own gated command without the bash -lc wrapper, or vsenv <VSID> && <command> in a VS run',
     'the run''s one trusted SSH session per member; one PTY shell channel (vt100, 4096x10000) per member per run, opened at its first read; commands serial with the configured pause; never reopened after a failure; clusterXL_admin writes stay on SSH_EXEC',
     'no BEGIN or END sentinel within the read''s timeout, output over 262144 characters, preamble over 64 KiB, END before BEGIN or a second BEGIN = that check UNKNOWN; the shell is closed and not reused in the run',
     'none added: the sentinels carry only the random nonce and the exit status',
     '[]'::jsonb,
     'docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md §3, §5; PO approval 2026-10-22 (docs/design/PO_DECISION_RECORD_2026_10_22_CP_ONE_SHELL_SESSION.md, L1-L4 APPROVED as written; one cluster first)')
ON CONFLICT (gate_id) DO NOTHING;
