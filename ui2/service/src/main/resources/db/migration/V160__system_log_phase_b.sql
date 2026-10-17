-- V160 -- system-log programme, phase B schema (incremental collection and storage; PO decision 2026-10-17,
-- docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md; docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md
-- §15 item 1). This migration holds schema and seed data only: no executor, transport or UI is introduced here.
--   1. The 13 approved collection commands (§10.1 C1-C8, §10.2 P1-P5) as SIGNED_OFF read gate rows.
--   2. ui2_job_module: cp_system_log_collect and pan_system_log_collect belong to the diagnostics module (§3.1).
--   3. backup_artefact.artefact_class admits 'system_log' (chunks and whole-file fetches use the artefact store and
--      the backup retention ledger, §5.1).
--   4. The notification type device_crash_file (§7, PO Q5): widens V100's type check (as V113 did for security_scan)
--      and seeds its route disabled. NOTIFICATION_ROUTING_CONTRACT.md carries the matching amendment.
--   5. The tables of §5.2 and the typed settings row of §5.3 / §2.3. No table holds raw log text.
-- Every statement is repeatable (IF NOT EXISTS, ON CONFLICT DO NOTHING, drop-then-create), so the dry run can run
-- the file twice inside one rolled-back transaction.
SELECT set_config('app.actor_fingerprint', 'migration:V160_system_log_phase_b', true);
SELECT set_config('app.action_id', 'system_log_phase_b_by_migration', true);

-- ---------------------------------------------------------------------
-- 1. Gate rows (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10; PO approval 2026-10-17, all 13 as written).
-- Command text, gate ids, timeouts, retry, frequency, session, unsupported-behavior, secret-risk and telemetry terms
-- are copied from the contract tables. Fields the gate_registry columns cannot name separately are folded into
-- max_frequency ("max bytes", the C8 timeouts, the parameter rules). Where a table cell says "same" or "as Pn" the
-- referenced row's text is written out. Deviations (also in the migration summary of the lane report):
--   * C8 has no gate id in the contract table; it is registered as cp_syslog_collect_fetch.
--   * C2 and C8 are SFTP steps (C4 §2.3: gate NOT_APPLICABLE). They are registered for the record only, with
--     transport_kind SFTP_GET and shell_context not_applicable; their canonical_command_key is the contract's own
--     description of the step, since there is no shell command.
--   * C3, C4, C6, C7 and P5 carry command text identical to the approved measurement rows of V157 and V151. C4 §3.3
--     step 6 makes two rows with the same (vendor, scope, shell, transport, command) key a hard AmbiguousGate error,
--     which would also break the measurement reads. The command text stays byte-identical; the key is narrowed with
--     shell_context expert_collect (CP) and not_applicable_collect (PAN). A capability spec step for these five
--     commands must declare that shell_context.
-- ---------------------------------------------------------------------
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_syslog_collect_stat', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''date +%s; stat -c "%n %i %s %Y" /var/log/messages* $FWDIR/log/fwd.elg* $CPDIR/log/cpd.elg* $CPDIR/log/cpwd.elg* $FWDIR/log/fwk.elg* $FWDIR/log/mq_mng.elg* /var/log/spike_detective/spike_detective.log* /var/log/spike_detective/spike_detective.csv*''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per job window (≤ 4/day); max bytes: 64 KiB output',
     'the run''s one existing trusted SSH session; non-interactive bash login shell; sequential',
     'an unmatched glob or missing file is a stat error line = ABSENT, not a failure',
     'file paths may contain host-specific install paths; no content; paths masked in aiview projections',
     '["deviceEpoch", "name", "inode", "sizeBytes", "mtime"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.1 (C1); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('cp_syslog_collect_read', 'check_point', 'cp_gaia_gateway', 'not_applicable', 'SFTP_GET', 'no shell command. Read-only SFTP open of `<path>`, read `[<pos>, <pos> + <cap>)`, close. `<pos>`, `<cap>` non-negative integers computed by neXus, `<cap> ≤ 8388672`; `<path>` = an absolute path printed by C1 in the same run, basename in the source''s family pattern, directory equal to the family base directory', 'read', 'SIGNED_OFF', 60, 'none',
     'per source per run, until the per-source (32 MiB) or per-device (64 MiB) budget; only inside the job window; max bytes: <cap> (8 MiB + 64 B anchor)',
     'same session (SFTP channel); sequential; never concurrent with another command or transfer',
     'missing file at read time (rotation race) or a short anchor read = UNKNOWN for this source in this run, cursor unchanged, re-planned next run; SFTP unavailable = UNKNOWN',
     'raw log lines: user names, addresses, host names, clish command audit lines that can carry password-hash/secret values; redacted on write (§12), stored encrypted, masked for aiview',
     '["startOffset", "bytesRead", "lines", "anchorMatch", "lineSplit"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.1 (C2); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('cp_syslog_collect_ls_crash', 'check_point', 'cp_gaia_gateway', 'expert_collect', 'SSH_EXEC', 'bash -lc ''ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per job window; max bytes: 256 KiB',
     'same session',
     'a missing directory is a result (ABSENT)',
     'file names may carry process names, addresses (kernel crash directory names) or host names; names stored as HMAC plus the parsed process; masked in aiview',
     '["fileCount", "sizeBytes", "mtime", "process"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.1 (C3); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('cp_syslog_collect_ls_spike', 'check_point', 'cp_gaia_gateway', 'expert_collect', 'SSH_EXEC', 'bash -lc ''ls -lgo --time-style=+%s /var/log/spike_detective/''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per job window; max bytes: 256 KiB',
     'same session',
     'missing directory = ABSENT',
     'names only',
     '["fileCount", "sizeBytes", "mtime", "namePattern"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.1 (C4); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('cp_syslog_collect_ls_spike_dir', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'bash -lc ''ls -lgo --time-style=+%s /var/log/spike_detective/<dir>/''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per new, settled data directory; max bytes: 64 KiB; parameters: <dir> = a name printed by C4 in the same run, matching the safe name form of §8.3 (the data-directory name pattern is not measured)',
     'same session',
     'missing = ABSENT',
     'names only',
     '["fileCount", "sizeBytes", "mtime"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.1 (C5); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('cp_syslog_collect_ls_sa', 'check_point', 'cp_gaia_gateway', 'expert_collect', 'SSH_EXEC', 'bash -lc ''ls -lgo --time-style=+%s /var/log/sa/''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per day (06:00 window); max bytes: 64 KiB',
     'same session',
     'missing = ABSENT',
     'names only',
     '["fileCount", "sizeBytes", "mtime", "namePattern"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.1 (C6); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('cp_syslog_collect_ls_hcp', 'check_point', 'cp_gaia_gateway', 'expert_collect', 'SSH_EXEC', 'bash -lc ''ls -lgo --time-style=+%s /var/log/hcp/last/''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per day (06:00 window); max bytes: 64 KiB',
     'same session',
     'missing = ABSENT',
     'archive names carry the host name; stored as HMAC plus the parsed time; masked',
     '["fileCount", "sizeBytes", "mtime", "namePattern"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.1 (C7); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('cp_syslog_collect_fetch', 'check_point', 'cp_gaia_gateway', 'not_applicable', 'SFTP_GET', 'fetch one absolute path printed by C4/C5 (spike data), C6 (`sar<DD>`) or C7 (HCP archive) in the same run; no other path', 'read', 'SIGNED_OFF', 300, 'none',
     'HCP and sar: once per device per day; spike: once per new directory; max bytes: HCP 16 MiB (RESOLVED, §3.4), sar 32 MiB (PROVISIONAL, §3.4), spike 16 MiB per directory (PENDING, layout not measured); timeouts: 120 (sar, spike), 300 (HCP)',
     'same session (SFTP channel)',
     'SFTP unavailable or path gone = UNKNOWN/ABSENT; over bound = TOO_LARGE, not fetched',
     'HCP archives contain device diagnostics that may include configuration fragments and names: stored encrypted as received, never shown raw to aiview (§12); sar and spike data are counters and process names',
     '["sizeBytes", "sha256", "kind"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.1 (C8); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('pan_syslog_collect_system_seed', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API', 'type=log&log-type=system&nlogs=<n>', 'read', 'SIGNED_OFF', 30, 'none',
     'once per firewall, the first run only (no cursor); max bytes: 8 MiB per result (as V151); parameters: <n> digits, 1–5000',
     'one existing-authentication API key per job; serial per firewall',
     'API error = UNKNOWN',
     'entries carry user names, addresses, host names; stored encrypted, masked for aiview',
     '["entries", "resultBytes", "enqueueToFinMs"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.2 (P1); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('pan_syslog_collect_config_seed', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API', 'type=log&log-type=config&nlogs=<n>', 'read', 'SIGNED_OFF', 30, 'none',
     'first run only; max bytes: 8 MiB',
     'one existing-authentication API key per job; serial per firewall',
     'API error = UNKNOWN',
     'entries carry user names, addresses, host names; stored encrypted, masked for aiview; plus configured values in before-/after-change-detail (redacted on write, §6)',
     '["entries", "resultBytes", "enqueueToFinMs"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.2 (P2); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('pan_syslog_collect_system_enqueue', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API', 'type=log&log-type=system&nlogs=<n>&dir=forward&query=(receive_time geq ''<ts>'')', 'read', 'SIGNED_OFF', 30, 'none',
     '≤ 4 pages per firewall per job window; max bytes: 8 MiB per page; parameters: <ts> matches ^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$ and is the stored cursor',
     'one existing-authentication API key per job; serial per firewall',
     'API error or unfinished job = UNKNOWN; the cursor is unchanged',
     'entries carry user names, addresses, host names; stored encrypted, masked for aiview',
     '["entries", "resultBytes", "enqueueToFinMs", "pages", "seqGapSuspected"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.2 (P3); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('pan_syslog_collect_config_enqueue', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API', 'type=log&log-type=config&nlogs=<n>&dir=forward&query=(receive_time geq ''<ts>'')', 'read', 'SIGNED_OFF', 30, 'none',
     '≤ 4 pages per firewall per job window; max bytes: 8 MiB',
     'one existing-authentication API key per job; serial per firewall',
     'API error or unfinished job = UNKNOWN; the cursor is unchanged',
     'entries carry user names, addresses, host names; stored encrypted, masked for aiview; plus configured values in before-/after-change-detail (redacted on write, §6)',
     '["entries", "resultBytes", "enqueueToFinMs", "pages", "seqGapSuspected"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.2 (P4); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)'),
    ('pan_syslog_collect_job_get', 'palo_alto', 'pan_firewall', 'not_applicable_collect', 'PAN_XML_API', 'type=log&action=get&job-id=<id>', 'read', 'SIGNED_OFF', 30, 'none',
     'every 2 s until FIN, at most 30 per enqueued job; max bytes: 8 MiB; parameters: <id> = the digits-only id the enqueue answered',
     'one existing-authentication API key per job; serial per firewall',
     'not FIN within the bound = UNKNOWN',
     'entries carry user names, addresses, host names; stored encrypted, masked for aiview; plus configured values in before-/after-change-detail (redacted on write, §6)',
     '["jobStatus", "entries", "resultBytes"]'::jsonb, 'docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.2 (P5); PO approval 2026-10-17 (docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md, Q2: all 13 commands APPROVED as written)')
ON CONFLICT (gate_id) DO NOTHING;

-- ---------------------------------------------------------------------
-- 2. Module ownership (contract §3.1). V154's mapping byte-for-byte, plus the two collection capabilities in the
-- diagnostics branch (V151 put the measurement jobs there). Without a module the claim statement never selects a job.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ui2_job_module(capability TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT CASE
 WHEN capability IN ('cp_policy_collect','pan_policy_collect') THEN 'policy'
 WHEN capability IN ('cp_gateway_backup','cp_spark_sftp_backup','cp_gaia_snapshot','pan_device_state_backup','cp_mds_export','https_vendor_backup','rdw_cc_config_backup','asa_config_backup','fgt_config_backup') THEN 'backup'
 WHEN capability IN ('device_confirm_check_point','device_confirm_palo_alto','device_confirm_https','device_confirm_cisco_asa','device_confirm_fortigate','cp_inventory_collect','pan_inventory_collect','https_inventory_collect','asa_inventory_collect','fgt_inventory_collect','cp_discovery_enumerate','pan_discovery_enumerate','rdw_discovery_enumerate','fmg_discovery_enumerate','bcmc_discovery_enumerate') THEN 'inventory'
 WHEN capability IN ('cp_failover_readiness','pan_failover_readiness','cp_cluster_failover','pan_cluster_failover') THEN 'failover'
 WHEN capability IN ('diagnostic_read','fmg_interface_detail','pan_syslog_measure','cp_system_log_collect','pan_system_log_collect') THEN 'diagnostics'
 WHEN capability IN ('cp_configuration_collect','pan_configuration_collect','fgt_configuration_collect','asa_configuration_collect','proxysg_configuration_collect') THEN 'configuration'
 WHEN capability = 'packet_analysis_run' THEN 'packet_analysis'
 ELSE NULL END
$$;

-- ---------------------------------------------------------------------
-- 3. Artefact class (contract §5.1: ArtefactClass.SYSTEM_LOG). The manifest check of V17 is replaced by one that
-- also admits 'system_log'. Every class-filtered query (backups, overview, search) names its class, so system-log
-- artefacts do not appear in the backup screens.
-- ---------------------------------------------------------------------
DO $$
DECLARE c text;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'backup_artefact'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%artefact_class%' LOOP
    EXECUTE format('ALTER TABLE backup_artefact DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE backup_artefact ADD CONSTRAINT backup_artefact_artefact_class_check
    CHECK (artefact_class IN ('configuration', 'backup', 'system_log'));

-- ---------------------------------------------------------------------
-- 4. Notification type device_crash_file (contract §7, PO Q5). Same pattern as V113 (security_scan).
-- ---------------------------------------------------------------------
ALTER TABLE notification_routes DROP CONSTRAINT IF EXISTS notification_routes_type_check;
ALTER TABLE notification_routes ADD CONSTRAINT notification_routes_type_check
    CHECK (type IN ('admin_event', 'login_security', 'backup_failure', 'job_failure',
        'config_change', 'compliance_regression', 'device_health', 'security_scan', 'device_crash_file'));
INSERT INTO notification_routes(type, enabled, watermark_audit, watermark_time, updated_by)
VALUES ('device_crash_file', false, 0, now(), 'system-log-migration')
ON CONFLICT (type) DO NOTHING;

-- ---------------------------------------------------------------------
-- 5. Tables (contract §5.2). No raw log text in any column. Conventions:
--   * source_key values are the contract's source keys (§2.1). Cursor and chunk rows exist only for the ten
--     incremental sources; gap and run rows may also name the whole-file and listing sources.
--   * inode is TEXT (digits): an inode is an opaque identifier (AGENTS.md identity law) and 64-bit inodes exceed a
--     signed BIGINT. It is never cast to a number.
--   * *_hmac columns hold a lowercase hex HMAC-SHA256 under the product privacy key; file names and log bytes are
--     never stored in clear (contract §5.2, §12).
--   * device_id and job_id carry no foreign key (as system_log_measurement, V151): history outlives a deleted
--     device or job row and is pruned by retention.
--   * line times are device-local wall-clock values, never converted (contract §4.7).
--   * every table is audited by fn_audit_capture (like V151); redacted columns are listed in section 6.
-- ---------------------------------------------------------------------

-- 5.1 One cursor per (device, incremental source).
CREATE TABLE IF NOT EXISTS system_log_cursor (
    device_id TEXT NOT NULL,
    source_key TEXT NOT NULL CHECK (source_key IN ('cp_messages', 'cp_fwd_elg', 'cp_cpd_elg', 'cp_cpwd_elg',
        'cp_fwk_elg', 'cp_mq_mng_elg', 'cp_spike_log', 'cp_spike_csv', 'pan_system', 'pan_config')),
    cursor_key TEXT GENERATED ALWAYS AS (device_id || '/' || source_key) STORED,
    inode TEXT CHECK (inode IS NULL OR inode ~ '^[0-9]{1,20}$'),
    offset_bytes BIGINT NOT NULL DEFAULT 0 CHECK (offset_bytes >= 0),
    anchor_hmac TEXT CHECK (anchor_hmac IS NULL OR anchor_hmac ~ '^[0-9a-f]{64}$'),
    family_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(family_snapshot) = 'array'),
    pan_last_receive_time TEXT CHECK (pan_last_receive_time IS NULL
        OR pan_last_receive_time ~ '^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$'),
    pan_boundary_seqnos JSONB CHECK (pan_boundary_seqnos IS NULL OR jsonb_typeof(pan_boundary_seqnos) = 'array'),
    device_epoch_at_read BIGINT CHECK (device_epoch_at_read IS NULL OR device_epoch_at_read >= 0),
    fast_rotating BOOLEAN NOT NULL DEFAULT false,
    retention_horizon_seconds BIGINT CHECK (retention_horizon_seconds IS NULL OR retention_horizon_seconds >= 0),
    updated_job_id TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, source_key)
);
COMMENT ON TABLE system_log_cursor IS
    'Resume point per device and incremental source (contract §4.1). Advanced only in the transaction that records the chunk.';
COMMENT ON COLUMN system_log_cursor.cursor_key IS 'device_id/source_key: the single-column row key fn_audit_capture records.';
COMMENT ON COLUMN system_log_cursor.family_snapshot IS 'Previous run''s stat of the file family: [{name, inode, size, mtime}].';
COMMENT ON COLUMN system_log_cursor.pan_last_receive_time IS 'Palo Alto cursor: the newest receive_time seen, verbatim device-local text.';
COMMENT ON COLUMN system_log_cursor.device_epoch_at_read IS 'Device clock (epoch seconds) at the last C1 stat.';

-- 5.2 Chunk / artefact index: one row per stored chunk. The artefact itself is a backup_artefact row of class system_log.
CREATE TABLE IF NOT EXISTS system_log_chunk (
    chunk_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    source_key TEXT NOT NULL CHECK (source_key IN ('cp_messages', 'cp_fwd_elg', 'cp_cpd_elg', 'cp_cpwd_elg',
        'cp_fwk_elg', 'cp_mq_mng_elg', 'cp_spike_log', 'cp_spike_csv', 'pan_system', 'pan_config')),
    job_id TEXT NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    log_day DATE NOT NULL,
    file_name_hmac TEXT NOT NULL CHECK (file_name_hmac ~ '^[0-9a-f]{64}$'),
    inode TEXT CHECK (inode IS NULL OR inode ~ '^[0-9]{1,20}$'),
    start_offset BIGINT NOT NULL CHECK (start_offset >= 0),
    end_offset BIGINT NOT NULL,
    bytes BIGINT NOT NULL CHECK (bytes >= 0),
    lines BIGINT NOT NULL CHECK (lines >= 0),
    first_line_time TIMESTAMP,
    last_line_time TIMESTAMP,
    line_time_basis TEXT NOT NULL DEFAULT 'DEVICE_LOCAL' CHECK (line_time_basis = 'DEVICE_LOCAL'),
    redaction_count INTEGER NOT NULL DEFAULT 0 CHECK (redaction_count >= 0),
    flags TEXT[] NOT NULL DEFAULT '{}'::text[] CHECK (flags <@ ARRAY['LINE_SPLIT', 'ROTATION_FOLLOWED']::text[]),
    artefact_ref TEXT NOT NULL,
    wrapped_data_key BYTEA NOT NULL,
    plaintext_sha256 TEXT NOT NULL CHECK (plaintext_sha256 ~ '^[0-9a-f]{64}$'),
    ciphertext_bytes BIGINT NOT NULL CHECK (ciphertext_bytes >= 0),
    pruned_at TIMESTAMPTZ,
    prune_reason TEXT CHECK (prune_reason IS NULL OR prune_reason IN ('RETENTION', 'RETENTION_SHORTENED_BY_CAP')),
    CONSTRAINT system_log_chunk_end_offset_check CHECK (end_offset >= start_offset),
    CONSTRAINT system_log_chunk_prune_check CHECK ((pruned_at IS NULL) = (prune_reason IS NULL)),
    CONSTRAINT system_log_chunk_device_source_inode_offset UNIQUE (device_id, source_key, inode, start_offset)
);
-- Palo Alto chunks have no inode (SQL NULLs are distinct in the unique key above): one chunk per log type per run.
CREATE UNIQUE INDEX IF NOT EXISTS system_log_chunk_pan_one_per_job
    ON system_log_chunk(device_id, source_key, job_id) WHERE inode IS NULL;
CREATE INDEX IF NOT EXISTS system_log_chunk_device_source_day ON system_log_chunk(device_id, source_key, log_day);
CREATE INDEX IF NOT EXISTS system_log_chunk_log_day ON system_log_chunk(log_day);
CREATE INDEX IF NOT EXISTS system_log_chunk_artefact_ref ON system_log_chunk(artefact_ref);
COMMENT ON TABLE system_log_chunk IS
    'Index of stored raw-log chunks (contract §5.2). The bytes live in the encrypted artefact store; this row holds identity and counters only.';
COMMENT ON COLUMN system_log_chunk.log_day IS 'Europe/Istanbul calendar date of collected_at.';
COMMENT ON COLUMN system_log_chunk.artefact_ref IS 'backup_artefact.artefact_id of the chunk (artefact_class = system_log).';
COMMENT ON COLUMN system_log_chunk.pruned_at IS 'Set when retention or the per-device cap removed the artefact; the row stays as the ledger.';

-- 5.3 Gap records: byte ranges the pool will never hold, always reported (contract §4.5).
CREATE TABLE IF NOT EXISTS system_log_gap (
    gap_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    source_key TEXT NOT NULL CHECK (source_key IN ('cp_messages', 'cp_fwd_elg', 'cp_cpd_elg', 'cp_cpwd_elg',
        'cp_fwk_elg', 'cp_mq_mng_elg', 'cp_spike_log', 'cp_spike_csv', 'pan_system', 'pan_config',
        'cp_sar_daily', 'cp_hcp_report', 'cp_spike_data', 'cp_crash_listing')),
    job_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('ROTATED_AWAY', 'TRUNCATED', 'ANCHOR_MISMATCH', 'INITIAL_BASELINE', 'BACKLOG',
        'COLLECTION_FAILED', 'PAGE_STALL', 'SEQ_GAP_SUSPECTED', 'RETENTION_SHORTENED_BY_CAP')),
    known_lost_bytes BIGINT NOT NULL DEFAULT 0 CHECK (known_lost_bytes >= 0),
    pending_bytes BIGINT NOT NULL DEFAULT 0 CHECK (pending_bytes >= 0),
    extent_unknown BOOLEAN NOT NULL DEFAULT false,
    window_start_epoch BIGINT CHECK (window_start_epoch IS NULL OR window_start_epoch >= 0),
    window_end_epoch BIGINT CHECK (window_end_epoch IS NULL OR window_end_epoch >= 0),
    detail JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(detail) = 'object'
        AND NOT detail ?| ARRAY['name', 'names', 'file_name', 'file_names', 'path', 'paths', 'line', 'lines', 'text', 'raw']),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT system_log_gap_window_check CHECK (window_start_epoch IS NULL OR window_end_epoch IS NULL
        OR window_end_epoch >= window_start_epoch)
);
CREATE INDEX IF NOT EXISTS system_log_gap_device_time ON system_log_gap(device_id, created_at DESC);
CREATE INDEX IF NOT EXISTS system_log_gap_device_source ON system_log_gap(device_id, source_key, kind);
COMMENT ON TABLE system_log_gap IS 'Reported loss or delay of log data, with exact byte counts (contract §4.5).';
COMMENT ON COLUMN system_log_gap.detail IS
    'Counts, suffix classes, inode numbers and name HMACs only. File names are never stored in clear (keys such as name or path are refused).';
COMMENT ON COLUMN system_log_gap.window_start_epoch IS 'Device epoch seconds of the previous cursor read.';

-- 5.4 Whole-file fetches: cp_sar_daily, cp_hcp_report, cp_spike_data (contract §8).
CREATE TABLE IF NOT EXISTS system_log_file (
    file_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('cp_sar_daily', 'cp_hcp_report', 'cp_spike_data')),
    job_id TEXT NOT NULL,
    remote_name_hmac TEXT NOT NULL CHECK (remote_name_hmac ~ '^[0-9a-f]{64}$'),
    name_time TIMESTAMP,
    size BIGINT NOT NULL CHECK (size >= 0),
    mtime BIGINT NOT NULL CHECK (mtime >= 0),
    status TEXT NOT NULL DEFAULT 'STORED' CHECK (status IN ('STORED', 'CORRUPT')),
    flags TEXT[] NOT NULL DEFAULT '{}'::text[] CHECK (flags <@ ARRAY['NAME_SHAPE_UNEXPECTED']::text[]),
    artefact_ref TEXT NOT NULL,
    wrapped_data_key BYTEA NOT NULL,
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    ciphertext_bytes BIGINT NOT NULL CHECK (ciphertext_bytes >= 0),
    collected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    pruned_at TIMESTAMPTZ,
    CONSTRAINT system_log_file_device_kind_name_mtime UNIQUE (device_id, kind, remote_name_hmac, mtime)
);
CREATE INDEX IF NOT EXISTS system_log_file_device_kind ON system_log_file(device_id, kind, collected_at DESC);
CREATE INDEX IF NOT EXISTS system_log_file_expires ON system_log_file(expires_at);
COMMENT ON TABLE system_log_file IS 'Index of whole-file fetches (sar, HCP report, Spike Detective data); bytes live in the artefact store.';
COMMENT ON COLUMN system_log_file.size IS 'Remote file size in bytes, from the gated listing.';
COMMENT ON COLUMN system_log_file.mtime IS 'Remote last-write time, device epoch seconds, from the gated listing.';
COMMENT ON COLUMN system_log_file.name_time IS 'Time parsed from the file name (device-local wall clock), when the name matches the measured form.';

-- 5.5 Crash and dump file listing state (contract §7). Listing only; content is never read.
CREATE TABLE IF NOT EXISTS device_crash_entry (
    entry_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    directory TEXT NOT NULL CHECK (directory IN ('crash', 'dump_usermode')),
    name_hmac TEXT NOT NULL CHECK (name_hmac ~ '^[0-9a-f]{64}$'),
    process TEXT NOT NULL CHECK (process ~ '^[A-Za-z0-9_.+-]{1,64}$'),
    size BIGINT NOT NULL CHECK (size >= 0),
    mtime BIGINT NOT NULL CHECK (mtime >= 0),
    baseline BOOLEAN NOT NULL DEFAULT false,
    first_seen_job TEXT NOT NULL,
    last_seen_job TEXT NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ,
    CONSTRAINT device_crash_entry_device_dir_name_mtime UNIQUE (device_id, directory, name_hmac, mtime)
);
CREATE INDEX IF NOT EXISTS device_crash_entry_device ON device_crash_entry(device_id, directory);
COMMENT ON TABLE device_crash_entry IS 'Listing state of /var/log/crash and /var/log/dump/usermode per device: names as HMAC plus the parsed process.';
COMMENT ON COLUMN device_crash_entry.process IS 'kernel for /var/log/crash; UNKNOWN when the name does not match the measured pattern.';
COMMENT ON COLUMN device_crash_entry.baseline IS 'True for files present in the first listing of a device ("present before monitoring"); they raise no alert.';
COMMENT ON COLUMN device_crash_entry.mtime IS 'Device epoch seconds from the listing.';

CREATE TABLE IF NOT EXISTS device_crash_alert (
    alert_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    process TEXT NOT NULL CHECK (process ~ '^[A-Za-z0-9_.+-]{1,64}$'),
    file_count INTEGER NOT NULL CHECK (file_count > 0),
    newest_mtime BIGINT NOT NULL CHECK (newest_mtime >= 0),
    job_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    notified_at TIMESTAMPTZ,
    CONSTRAINT device_crash_alert_device_job_process UNIQUE (device_id, job_id, process)
);
CREATE INDEX IF NOT EXISTS device_crash_alert_created ON device_crash_alert(created_at);
COMMENT ON TABLE device_crash_alert IS 'One alert per (device, run, process); the device_crash_file notification route digests rows newer than its watermark.';
COMMENT ON COLUMN device_crash_alert.file_count IS 'Number of new files (the contract''s count).';
COMMENT ON COLUMN device_crash_alert.newest_mtime IS 'Device epoch seconds of the newest new file.';

-- 5.6 Run outcomes: exactly one row per job and source (contract §9).
CREATE TABLE IF NOT EXISTS system_log_run (
    run_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    job_id TEXT NOT NULL,
    source_key TEXT NOT NULL CHECK (source_key IN ('cp_messages', 'cp_fwd_elg', 'cp_cpd_elg', 'cp_cpwd_elg',
        'cp_fwk_elg', 'cp_mq_mng_elg', 'cp_spike_log', 'cp_spike_csv', 'pan_system', 'pan_config',
        'cp_sar_daily', 'cp_hcp_report', 'cp_spike_data', 'cp_crash_listing')),
    outcome TEXT NOT NULL CHECK (outcome IN ('COLLECTED', 'NO_NEW_DATA', 'BACKLOG', 'GAP', 'ABSENT', 'UNSUPPORTED',
        'TOO_LARGE', 'STORAGE_REFUSED', 'UNKNOWN')),
    bytes_read BIGINT NOT NULL DEFAULT 0 CHECK (bytes_read >= 0),
    bytes_stored BIGINT NOT NULL DEFAULT 0 CHECK (bytes_stored >= 0),
    reads_issued INTEGER NOT NULL DEFAULT 0 CHECK (reads_issued >= 0),
    duration_ms BIGINT NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    detail JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(detail) = 'object'
        AND NOT detail ?| ARRAY['name', 'names', 'file_name', 'file_names', 'path', 'paths', 'line', 'lines', 'text', 'raw']),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT system_log_run_job_source UNIQUE (job_id, source_key)
);
CREATE INDEX IF NOT EXISTS system_log_run_device_time ON system_log_run(device_id, created_at DESC);
CREATE INDEX IF NOT EXISTS system_log_run_device_source ON system_log_run(device_id, source_key, created_at DESC);
COMMENT ON TABLE system_log_run IS 'Per job and source outcome of a collection run; a source failure is never recorded as NO_NEW_DATA.';

-- 5.7 Typed settings (contract §5.3, §2.3, §8.3): one row, PO-settable by security_admin through the audited PUT.
CREATE TABLE IF NOT EXISTS system_log_settings (
    settings_id TEXT PRIMARY KEY CHECK (settings_id = 'default'),
    raw_retention_days INTEGER NOT NULL DEFAULT 30 CHECK (raw_retention_days BETWEEN 1 AND 3650),
    device_cap_bytes BIGINT NOT NULL DEFAULT 2147483648 CHECK (device_cap_bytes BETWEEN 67108864 AND 1099511627776),
    hcp_retention_days INTEGER NOT NULL DEFAULT 7 CHECK (hcp_retention_days BETWEEN 1 AND 3650),
    spike_data_retention_days INTEGER NOT NULL DEFAULT 30 CHECK (spike_data_retention_days BETWEEN 1 AND 3650),
    spike_data_cap_bytes BIGINT NOT NULL DEFAULT 536870912 CHECK (spike_data_cap_bytes BETWEEN 1048576 AND 1099511627776),
    ledger_retention_days INTEGER NOT NULL DEFAULT 180 CHECK (ledger_retention_days BETWEEN 1 AND 3650),
    include_management_servers BOOLEAN NOT NULL DEFAULT false,
    spike_data_enabled BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);
COMMENT ON TABLE system_log_settings IS
    'Collection settings. API/CLI keys system_log.include_management_servers and system_log.spike_data_enabled map to the columns of the same name.';
COMMENT ON COLUMN system_log_settings.device_cap_bytes IS 'Per-device stored (compressed) bytes; at the cap the oldest chunks are pruned early and ledgered (PO Q4).';
COMMENT ON COLUMN system_log_settings.ledger_retention_days IS 'Retention of gap, run, crash-entry and crash-alert rows (180 days, contract §5.3).';

-- ---------------------------------------------------------------------
-- 6. Audit classification (UI2_0_B1_02A section 4: every column of an audited table is redacted here or listed in
-- AuditPresentationAllowlist). Keyed digests, name HMACs, wrapped keys, artefact locations and device-derived detail
-- are redacted; identifiers, enums, counters, timestamps and content digests are audited in full.
-- ---------------------------------------------------------------------
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('system_log_cursor', 'anchor_hmac', 2, 'keyed digest of device log bytes; low-entropy input'),
    ('system_log_cursor', 'family_snapshot', 2, 'device-derived log file names, inodes and sizes'),
    ('system_log_chunk', 'file_name_hmac', 2, 'keyed digest of a device file name; low-entropy input'),
    ('system_log_chunk', 'artefact_ref', 2, 'server-only artefact location'),
    ('system_log_chunk', 'wrapped_data_key', 1, 'wrapped per-artefact data key -- cryptographic material outside its key lifecycle'),
    ('system_log_gap', 'detail', 2, 'device-derived counts, suffixes, inode numbers and name digests'),
    ('system_log_file', 'remote_name_hmac', 2, 'keyed digest of a device file name; low-entropy input'),
    ('system_log_file', 'artefact_ref', 2, 'server-only artefact location'),
    ('system_log_file', 'wrapped_data_key', 1, 'wrapped per-artefact data key -- cryptographic material outside its key lifecycle'),
    ('device_crash_entry', 'name_hmac', 2, 'keyed digest of a device file name; low-entropy input'),
    ('device_crash_entry', 'process', 2, 'device-derived process name'),
    ('device_crash_alert', 'process', 2, 'device-derived process name'),
    ('system_log_run', 'detail', 2, 'device-derived counts and classes')
ON CONFLICT (table_name, column_name) DO NOTHING;

-- ---------------------------------------------------------------------
-- 7. Audit triggers and grants (as V151).
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON system_log_cursor TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON system_log_chunk TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON system_log_gap TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON system_log_file TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON device_crash_entry TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON device_crash_alert TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON system_log_run TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON system_log_settings TO ui2_app;

DROP TRIGGER IF EXISTS trg_audit_system_log_cursor ON system_log_cursor;
CREATE TRIGGER trg_audit_system_log_cursor
    AFTER INSERT OR UPDATE OR DELETE ON system_log_cursor
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('cursor_key');
DROP TRIGGER IF EXISTS trg_audit_system_log_chunk ON system_log_chunk;
CREATE TRIGGER trg_audit_system_log_chunk
    AFTER INSERT OR UPDATE OR DELETE ON system_log_chunk
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('chunk_id');
DROP TRIGGER IF EXISTS trg_audit_system_log_gap ON system_log_gap;
CREATE TRIGGER trg_audit_system_log_gap
    AFTER INSERT OR UPDATE OR DELETE ON system_log_gap
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('gap_id');
DROP TRIGGER IF EXISTS trg_audit_system_log_file ON system_log_file;
CREATE TRIGGER trg_audit_system_log_file
    AFTER INSERT OR UPDATE OR DELETE ON system_log_file
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('file_id');
DROP TRIGGER IF EXISTS trg_audit_device_crash_entry ON device_crash_entry;
CREATE TRIGGER trg_audit_device_crash_entry
    AFTER INSERT OR UPDATE OR DELETE ON device_crash_entry
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('entry_id');
DROP TRIGGER IF EXISTS trg_audit_device_crash_alert ON device_crash_alert;
CREATE TRIGGER trg_audit_device_crash_alert
    AFTER INSERT OR UPDATE OR DELETE ON device_crash_alert
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('alert_id');
DROP TRIGGER IF EXISTS trg_audit_system_log_run ON system_log_run;
CREATE TRIGGER trg_audit_system_log_run
    AFTER INSERT OR UPDATE OR DELETE ON system_log_run
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('run_id');
DROP TRIGGER IF EXISTS trg_audit_system_log_settings ON system_log_settings;
CREATE TRIGGER trg_audit_system_log_settings
    AFTER INSERT OR UPDATE OR DELETE ON system_log_settings
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('settings_id');

-- The single settings row is seeded after its trigger exists, so the audit log records where the defaults came from.
INSERT INTO system_log_settings(settings_id, updated_by) VALUES ('default', 'system-log-migration')
ON CONFLICT (settings_id) DO NOTHING;
