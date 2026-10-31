-- V182 -- Check Point local-log directory diagnostics (docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md, addendum 2026-10-31).
-- On the gateway $FWDIR/log is a symlink, so V181's du/ls measured the link (0 bytes, one line) instead of the directory.
-- PO chat approval 2026-10-31 ("Komutlar onayli") of the same two commands with a trailing slash so they follow the link.
-- Same shape as V181: CLASS_0 read rows for cp_gaia_gateway, Expert context, SSH_EXEC, bash -lc '<command>'.
-- Seed data only; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V182_cp_local_log_dir_diagnostics', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_localdiag_du_fwdir_log_dir', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''du -sh $FWDIR/log/''', 'read', 'SIGNED_OFF', 60, 'none',
     'on PO request for one gateway keeping large local firewall logs: at most once per device per job, never polled',
     'one existing trusted SSH session; non-interactive bash login shell (so $FWDIR resolves); no retry',
     'a missing file or directory is a result (command error text), not an error', 'output may carry host names, addresses and file names; in-memory masked projection only (hostnames and IPs masked by the diagnostic output mask); no raw retention; output size cap as for every diagnostic_read', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md addendum (PO chat approval 2026-10-31: "Komutlar onayli")'),
    ('cp_localdiag_ls_fwdir_log_dir', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''ls -la $FWDIR/log/ | tail -20''', 'read', 'SIGNED_OFF', 30, 'none',
     'on PO request for one gateway keeping large local firewall logs: at most once per device per job, never polled',
     'one existing trusted SSH session; non-interactive bash login shell (so $FWDIR resolves); no retry',
     'a missing file or directory is a result (command error text), not an error', 'output may carry host names, addresses and file names; in-memory masked projection only (hostnames and IPs masked by the diagnostic output mask); no raw retention; output size cap as for every diagnostic_read', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md addendum (PO chat approval 2026-10-31: "Komutlar onayli")')
ON CONFLICT (gate_id) DO NOTHING;
