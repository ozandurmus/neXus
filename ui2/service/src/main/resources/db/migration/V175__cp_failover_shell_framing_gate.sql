-- V175 -- Check Point failover: one shell channel per member per pass (docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md).
-- The PO approved L1-L4 on 2026-10-22 (docs/design/PO_DECISION_RECORD_2026_10_22_CP_ONE_SHELL_SESSION.md, RATIFIED:
-- "Onaylıyorum, önce tek kümede"). The external review (docs/design/ARGON_REVIEW_2026_10_22_CP_ONE_SHELL.md, P1-3/P1-5)
-- amended the framing to L2': each read runs in a subshell with an explicit stderr rule, so its context and stderr behave as
-- on one exec channel. The amended literal is registered DRAFTED until the PO confirms amendment A1 of that record; a later
-- migration (or this one, before it ships) then signs it off. While DRAFTED, a run on an allow-listed cluster stops with
-- COMMAND_GATE_UNAVAILABLE before any connection; every other run never reads this row.
-- The approved read gates are not changed: each read is still resolved by its own SSH_EXEC row and command key; the
-- clusterXL_admin writes stay on SSH_EXEC. Renumbered from V174 (taken by V174__failover_settled_task_lease_release.sql).
-- Seed data only; re-running is a no-op (ON CONFLICT DO NOTHING).
SELECT set_config('app.actor_fingerprint', 'migration:V175_cp_failover_shell_framing_gate', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_failover_shell_framing', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_INTERACTIVE',
     'echo __NX_<NONCE>_BEGIN__; ( <READ> ) <STDERR>; echo __NX_<NONCE>_END_$?__', 'read', 'DRAFTED', 30, 'none',
     'once per approved cp_failover read of a readiness, execution or resume run on an allow-listed cluster, wrapping that read; <NONCE> = 32 lowercase hex characters from SecureRandom, new per command; <READ> = the read''s own gated command without the bash -lc wrapper, or vsenv <VSID> && <command> in a VS run; <STDERR> = 2>/dev/null, or 2>&1 for cphaprob -a if (the read that ran with a PTY on exec); Ctrl-C (0x03) once on a read timeout before the shell is closed',
     'the run''s one trusted SSH session per member; one PTY shell channel (vt100, 4096x10000) per member per run, opened at its first read after the banner and first prompt are drained; commands serial with the configured shell pause; never reopened: after a failure the member''s remaining reads use their SSH_EXEC form; write confirmation polls, the resume pre-UP reads and the clusterXL_admin writes always use SSH_EXEC',
     'no BEGIN or END sentinel within the read''s timeout, output over 262144 characters, preamble over 64 KiB, END before BEGIN or a second BEGIN = that check UNKNOWN (never retried); the shell is closed',
     'none added: the sentinels carry only the random nonce and the exit status',
     '[]'::jsonb,
     'docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md §3, §5, §7; PO approval 2026-10-22 of L1-L4 (docs/design/PO_DECISION_RECORD_2026_10_22_CP_ONE_SHELL_SESSION.md); amendment A1 (L2'') pending PO confirmation')
ON CONFLICT (gate_id) DO NOTHING;
