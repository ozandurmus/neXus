-- V174 -- Check Point failover: one shell channel per member per pass (docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md,
-- DRAFT). Registers the one new framing literal (section 5, L1-L4) as a DRAFTED read gate: GateResolver returns UNKNOWN
-- for any row that is not SIGNED_OFF, so a worker with NEXUS_CP_FAILOVER_ONE_SHELL=enabled still stops every Check Point
-- readiness, execution and resume run with COMMAND_GATE_UNAVAILABLE before any connection until the Product Owner approves
-- the literals and a later migration signs this row off. With the switch off (the default) the row is never read.
-- The approved read gates are not changed: each read is still resolved by its own SSH_EXEC row and command key.
-- Seed data only; re-running is a no-op (ON CONFLICT DO NOTHING).
SELECT set_config('app.actor_fingerprint', 'migration:V174_cp_failover_shell_framing_gate_drafted', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_failover_shell_framing', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_INTERACTIVE',
     'echo __NX_<NONCE>_BEGIN__; <READ>; echo __NX_<NONCE>_END_$?__', 'read', 'DRAFTED', 30, 'none',
     'once per approved cp_failover read of a readiness, execution or resume run, wrapping that read; <NONCE> = 32 lowercase hex characters from SecureRandom, new per command; <READ> = the read''s own gated command without the bash -lc wrapper, or vsenv <VSID> && <command> in a VS run',
     'the run''s one trusted SSH session per member; one PTY shell channel (vt100, 4096x10000) per member per run, opened at its first read; commands serial with the configured pause; never reopened after a failure; clusterXL_admin writes stay on SSH_EXEC',
     'no BEGIN or END sentinel within the read''s timeout, output over 262144 characters, preamble over 64 KiB, END before BEGIN or a second BEGIN = that check UNKNOWN; the shell is closed and not reused in the run',
     'none added: the sentinels carry only the random nonce and the exit status',
     '[]'::jsonb,
     'docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md §3, §5 (DRAFT; pending PO approval of L1-L4)')
ON CONFLICT (gate_id) DO NOTHING;
