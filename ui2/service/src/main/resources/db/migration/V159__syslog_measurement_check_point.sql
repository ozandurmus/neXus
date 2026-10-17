-- V159 -- system-log programme, phase A measurement R1 (PO approval 2026-10-17): the Check Point cp_syslog_measure_*
-- diagnostic reads (V149, V157) also record a NUMERIC summary in system_log_measurement. The aiview masked
-- projection hides every integer of 6 or more digits, all mtimes, HH:MM:SS times and file names, so file sizes of
-- 100,000 B and above, mtimes, rotation timing and growth could not be measured from the masked text. The worker
-- derives the summary in memory from the raw output (sizes, mtimes, counts, medians, name CLASSES, keyed inode
-- tokens, timestamp-shape counts); no raw line, path, file name or address is stored. The summary reuses the
-- existing summary jsonb column; only the vendor/source whitelist grows. No command, session or frequency changes.
-- docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4.
SELECT set_config('app.actor_fingerprint', 'migration:V159_syslog_measurement_check_point', true);

ALTER TABLE system_log_measurement DROP CONSTRAINT IF EXISTS system_log_measurement_vendor_check;
ALTER TABLE system_log_measurement DROP CONSTRAINT IF EXISTS system_log_measurement_source_check;
ALTER TABLE system_log_measurement DROP CONSTRAINT IF EXISTS system_log_measurement_vendor_source_check;
ALTER TABLE system_log_measurement ADD CONSTRAINT system_log_measurement_vendor_source_check CHECK (
    (vendor = 'palo_alto' AND source IN ('pan_system', 'pan_config'))
    OR (vendor = 'check_point' AND source IN (
        'cp_syslog_measure_stat', 'cp_syslog_measure_stat_ext',
        'cp_syslog_measure_ls_sa', 'cp_syslog_measure_ls_spike', 'cp_syslog_measure_ls_hcp', 'cp_syslog_measure_ls_crash',
        'cp_syslog_measure_tail_messages', 'cp_syslog_measure_tail_fwd', 'cp_syslog_measure_tail_cpd',
        'cp_syslog_measure_tail_cpwd', 'cp_syslog_measure_tail_fwk', 'cp_syslog_measure_tail_spike',
        'cp_syslog_measure_tail_spike_csv', 'cp_syslog_measure_head_sar', 'cp_syslog_measure_hcp_listing')));
