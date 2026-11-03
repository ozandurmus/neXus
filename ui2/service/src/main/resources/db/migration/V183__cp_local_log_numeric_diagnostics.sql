-- V183 -- Check Point local-log diagnostics with unmasked numeric output (docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md, addendum 2026-11-03).
-- V182's output was unreadable: the diagnostic mask treats "289G" and month abbreviations as identifiers, and ls -la sorts
-- by name, so the tail was not the newest files. Two replacements print plain numbers: du with a 1 GiB block size (a bare
-- integer) and ls -lt with ISO dates (newest first, head -20). PO chat approval 2026-11-03 ("devam" to the proposed commands).
-- Same shape as V181/V182. Seed data only; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V183_cp_local_log_numeric_diagnostics', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_localdiag_du_fwdir_log_gib', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''du -s --block-size=1073741824 $FWDIR/log/''', 'read', 'SIGNED_OFF', 60, 'none',
     'on PO request for one gateway keeping large local firewall logs: at most once per device per job, never polled',
     'one existing trusted SSH session; non-interactive bash login shell (so $FWDIR resolves); no retry',
     'a missing file or directory is a result (command error text), not an error', 'output may carry host names, addresses and file names; in-memory masked projection only (hostnames and IPs masked by the diagnostic output mask); no raw retention; output size cap as for every diagnostic_read', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md addendum 2026-11-03 (PO chat approval 2026-11-03: "devam")'),
    ('cp_localdiag_ls_fwdir_log_newest', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''ls -lt --time-style=+%Y-%m-%d $FWDIR/log/ | head -20''', 'read', 'SIGNED_OFF', 30, 'none',
     'on PO request for one gateway keeping large local firewall logs: at most once per device per job, never polled',
     'one existing trusted SSH session; non-interactive bash login shell (so $FWDIR resolves); no retry',
     'a missing file or directory is a result (command error text), not an error', 'output may carry host names, addresses and file names; in-memory masked projection only (hostnames and IPs masked by the diagnostic output mask); no raw retention; output size cap as for every diagnostic_read', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md addendum 2026-11-03 (PO chat approval 2026-11-03: "devam")')
ON CONFLICT (gate_id) DO NOTHING;
