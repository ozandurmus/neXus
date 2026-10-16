-- V157 -- system-log programme, phase A measurement M5 (PO approval 2026-10-16, exact commands approved in chat).
-- Ten read-only Check Point Gaia gateway diagnostic reads (Expert shell, SSH_EXEC, CLASS_0 read, SIGNED_OFF):
-- the log files and directories that V149 (M1/M2) did not cover. Run through the existing CLASS_0 diagnostic path
-- with its masked projection (file names in outputs may contain host names and are masked there). No collection
-- job uses them; docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4 (M5).
-- Scope: cp_gaia_gateway only, never management servers. Each gate runs at most twice per device, 24 h apart,
-- inside a job window; no polling.
-- Purpose per gate:
--   cp_syslog_measure_stat_ext: name, size and last write time of the fwk, mq_mng, HCP and spike_detective log files, read twice 24 h apart to measure growth.
--   cp_syslog_measure_ls_sa: sar archive directory listing: retention depth, file naming and per-day size.
--   cp_syslog_measure_ls_spike: spike_detective directory listing: rotation count, naming and sizes.
--   cp_syslog_measure_ls_hcp: HCP last-run directory listing: archive naming, count and sizes.
--   cp_syslog_measure_ls_crash: crash and user-mode dump directory listings: whether dumps exist, their naming, sizes and ages.
--   cp_syslog_measure_tail_fwk: line format of fwk.elg (kernel worker log).
--   cp_syslog_measure_tail_spike: line format of spike_detective.log.
--   cp_syslog_measure_tail_spike_csv: row format of spike_detective.csv (column count, timestamp form).
--   cp_syslog_measure_head_sar: header and column layout of yesterday's sar file.
--   cp_syslog_measure_hcp_listing: member names and sizes inside the newest HCP archive.
SELECT set_config('app.actor_fingerprint', 'migration:V157_syslog_measure_m5_gates', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_syslog_measure_stat_ext', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''stat -c "%n %s %Y" $FWDIR/log/fwk.elg $FWDIR/log/mq_mng.elg /var/log/hcp.elg /var/log/spike_detective/spike_detective.log /var/log/spike_detective/spike_detective.csv''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/ls/tail/head error text), not an error',
     'log lines and file names may carry host names, addresses and user names; in-memory masked projection only; no raw retention',
     '["name", "sizeBytes", "mtime"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4 (M5); PO chat approval 2026-10-16 for the exact M5 commands on Check Point Gaia gateways'),
    ('cp_syslog_measure_ls_sa', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''ls -lgo --time-style=+%s /var/log/sa/''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/ls/tail/head error text), not an error',
     'log lines and file names may carry host names, addresses and user names; in-memory masked projection only; no raw retention',
     '["fileCount", "sizeBytes", "mtime", "namePattern"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4 (M5); PO chat approval 2026-10-16 for the exact M5 commands on Check Point Gaia gateways'),
    ('cp_syslog_measure_ls_spike', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''ls -lgo --time-style=+%s /var/log/spike_detective/''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/ls/tail/head error text), not an error',
     'log lines and file names may carry host names, addresses and user names; in-memory masked projection only; no raw retention',
     '["fileCount", "sizeBytes", "mtime", "namePattern"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4 (M5); PO chat approval 2026-10-16 for the exact M5 commands on Check Point Gaia gateways'),
    ('cp_syslog_measure_ls_hcp', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''ls -lgo --time-style=+%s /var/log/hcp/last/''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/ls/tail/head error text), not an error',
     'log lines and file names may carry host names, addresses and user names; in-memory masked projection only; no raw retention',
     '["fileCount", "sizeBytes", "mtime", "namePattern"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4 (M5); PO chat approval 2026-10-16 for the exact M5 commands on Check Point Gaia gateways'),
    ('cp_syslog_measure_ls_crash', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/ls/tail/head error text), not an error',
     'log lines and file names may carry host names, addresses and user names; in-memory masked projection only; no raw retention',
     '["fileCount", "sizeBytes", "mtime", "namePattern"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4 (M5); PO chat approval 2026-10-16 for the exact M5 commands on Check Point Gaia gateways'),
    ('cp_syslog_measure_tail_fwk', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''tail -n 20 $FWDIR/log/fwk.elg''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/ls/tail/head error text), not an error',
     'log lines and file names may carry host names, addresses and user names; in-memory masked projection only; no raw retention',
     '["timestampForm", "lineShape"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4 (M5); PO chat approval 2026-10-16 for the exact M5 commands on Check Point Gaia gateways'),
    ('cp_syslog_measure_tail_spike', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''tail -n 20 /var/log/spike_detective/spike_detective.log''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/ls/tail/head error text), not an error',
     'log lines and file names may carry host names, addresses and user names; in-memory masked projection only; no raw retention',
     '["timestampForm", "lineShape"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4 (M5); PO chat approval 2026-10-16 for the exact M5 commands on Check Point Gaia gateways'),
    ('cp_syslog_measure_tail_spike_csv', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''tail -n 5 /var/log/spike_detective/spike_detective.csv''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/ls/tail/head error text), not an error',
     'log lines and file names may carry host names, addresses and user names; in-memory masked projection only; no raw retention',
     '["columnCount", "timestampForm", "lineShape"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4 (M5); PO chat approval 2026-10-16 for the exact M5 commands on Check Point Gaia gateways'),
    ('cp_syslog_measure_head_sar', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''head -n 20 /var/log/sa/sar$(date -d yesterday +%d)''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/ls/tail/head error text), not an error',
     'log lines and file names may carry host names, addresses and user names; in-memory masked projection only; no raw retention',
     '["headerShape", "lineShape"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4 (M5); PO chat approval 2026-10-16 for the exact M5 commands on Check Point Gaia gateways'),
    ('cp_syslog_measure_hcp_listing', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''f=$(ls -t /var/log/hcp/last/*.tar.gz | head -1); tar -tzvf "$f" | head -n 50''', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: at most twice per device, 24 h apart, inside a job window; no polling',
     'one existing trusted SSH session; non-interactive bash login shell; no retry',
     'a missing file is a result (stat/ls/tail/head error text), not an error',
     'log lines and file names may carry host names, addresses and user names; in-memory masked projection only; no raw retention',
     '["memberNames", "sizeBytes"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4 (M5); PO chat approval 2026-10-16 for the exact M5 commands on Check Point Gaia gateways')
ON CONFLICT (gate_id) DO NOTHING;
