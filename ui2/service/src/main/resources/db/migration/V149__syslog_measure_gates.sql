-- V149 -- system-log programme, phase A measurement reads (PO chat approval 2026-10-15, M1/M2).
-- Five read-only Check Point diagnostic reads, runnable through the existing CLASS_0 diagnostic path with its
-- masked projection: file identity/size (M1) and the line format of four system logs (M2). No collection job
-- uses them; docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4.
SELECT set_config('app.actor_fingerprint', 'migration:V149_syslog_measure_gates', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_syslog_measure_stat', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''stat -c "%i %s %Y" /var/log/messages /var/log/messages.1 $FWDIR/log/fwd.elg $CPDIR/log/cpd.elg $CPDIR/log/cpwd.elg''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/tail error text), not an error',
     'log lines may carry names, addresses and user names; in-memory masked projection only; no raw retention',
     '["inode", "sizeBytes", "mtime"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15 for M1/M2 on every enrolled Check Point firewall'),
    ('cp_syslog_measure_tail_messages', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''tail -n 20 /var/log/messages''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/tail error text), not an error',
     'log lines may carry names, addresses and user names; in-memory masked projection only; no raw retention',
     '["timestampForm", "lineShape"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15 for M1/M2 on every enrolled Check Point firewall'),
    ('cp_syslog_measure_tail_fwd', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''tail -n 20 $FWDIR/log/fwd.elg''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/tail error text), not an error',
     'log lines may carry names, addresses and user names; in-memory masked projection only; no raw retention',
     '["timestampForm", "lineShape"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15 for M1/M2 on every enrolled Check Point firewall'),
    ('cp_syslog_measure_tail_cpd', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''tail -n 20 $CPDIR/log/cpd.elg''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/tail error text), not an error',
     'log lines may carry names, addresses and user names; in-memory masked projection only; no raw retention',
     '["timestampForm", "lineShape"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15 for M1/M2 on every enrolled Check Point firewall'),
    ('cp_syslog_measure_tail_cpwd', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''tail -n 20 $CPDIR/log/cpwd.elg''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/tail error text), not an error',
     'log lines may carry names, addresses and user names; in-memory masked projection only; no raw retention',
     '["timestampForm", "lineShape"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15 for M1/M2 on every enrolled Check Point firewall')
ON CONFLICT (gate_id) DO NOTHING;
