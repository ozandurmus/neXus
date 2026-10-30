-- V181 -- Check Point local-log diagnostics (docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md).
-- PO chat approval 2026-10-30 ("Bunları sen çalıştırabilirsin") of five exact read-only Gaia commands to investigate a
-- gateway keeping about 288 GB of local firewall logs. Five CLASS_0 read rows for cp_gaia_gateway, Expert context,
-- SSH_EXEC, written as bash -lc '<command>' like the system-log measurement reads (V157), so $FWDIR and cphaprob resolve.
-- They appear in the diagnostic_read catalog (not in DIAGNOSTIC_EXCLUDED) and use its masking and output cap.
-- The ls listing is bounded with | tail -20 inside the bash -lc literal (precedent: cp_syslog_measure_hcp_listing).
-- Numbered after V179 on main and V180 on lane/backup-size-anomaly. Seed data only; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V181_cp_local_log_diagnostics', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_localdiag_du_fwdir_log', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''du -sh $FWDIR/log''', 'read', 'SIGNED_OFF', 30, 'none',
     'on PO request for one gateway keeping large local firewall logs: at most once per device per job, never polled',
     'one existing trusted SSH session; non-interactive bash login shell (so $FWDIR and cphaprob resolve); no retry',
     'a missing file or directory is a result (command error text), not an error', 'output may carry host names, addresses and file names; in-memory masked projection only (hostnames and IPs masked by the diagnostic output mask); no raw retention; output size cap as for every diagnostic_read', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md (PO chat approval 2026-10-30: "Bunları sen çalıştırabilirsin")'),
    ('cp_localdiag_ls_fwdir_log', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''ls -la $FWDIR/log | tail -20''', 'read', 'SIGNED_OFF', 30, 'none',
     'on PO request for one gateway keeping large local firewall logs: at most once per device per job, never polled',
     'one existing trusted SSH session; non-interactive bash login shell (so $FWDIR and cphaprob resolve); no retry',
     'a missing file or directory is a result (command error text), not an error', 'output may carry host names, addresses and file names; in-memory masked projection only (hostnames and IPs masked by the diagnostic output mask); no raw retention; output size cap as for every diagnostic_read', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md (PO chat approval 2026-10-30: "Bunları sen çalıştırabilirsin")'),
    ('cp_localdiag_cpstat_fw_log_connection', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''cpstat fw -f log_connection''', 'read', 'SIGNED_OFF', 30, 'none',
     'on PO request for one gateway keeping large local firewall logs: at most once per device per job, never polled',
     'one existing trusted SSH session; non-interactive bash login shell (so $FWDIR and cphaprob resolve); no retry',
     'a missing file or directory is a result (command error text), not an error', 'output may carry host names, addresses and file names; in-memory masked projection only (hostnames and IPs masked by the diagnostic output mask); no raw retention; output size cap as for every diagnostic_read', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md (PO chat approval 2026-10-30: "Bunları sen çalıştırabilirsin")'),
    ('cp_localdiag_cat_fw1logs_cpbak', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''cat /var/CPbackup/schemes/fw1logs.cpbak''', 'read', 'SIGNED_OFF', 30, 'none',
     'on PO request for one gateway keeping large local firewall logs: at most once per device per job, never polled',
     'one existing trusted SSH session; non-interactive bash login shell (so $FWDIR and cphaprob resolve); no retry',
     'a missing file or directory is a result (command error text), not an error', 'output may carry host names, addresses and file names; in-memory masked projection only (hostnames and IPs masked by the diagnostic output mask); no raw retention; output size cap as for every diagnostic_read', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md (PO chat approval 2026-10-30: "Bunları sen çalıştırabilirsin")'),
    ('cp_localdiag_cphaprob_state', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''cphaprob state''', 'read', 'SIGNED_OFF', 30, 'none',
     'on PO request for one gateway keeping large local firewall logs: at most once per device per job, never polled',
     'one existing trusted SSH session; non-interactive bash login shell (so $FWDIR and cphaprob resolve); no retry',
     'a missing file or directory is a result (command error text), not an error', 'output may carry host names, addresses and file names; in-memory masked projection only (hostnames and IPs masked by the diagnostic output mask); no raw retention; output size cap as for every diagnostic_read', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md (PO chat approval 2026-10-30: "Bunları sen çalıştırabilirsin")')
ON CONFLICT (gate_id) DO NOTHING;
