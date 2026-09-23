-- V61 -- Check Point Multi-Domain Server export (PO, 2026-09-23: "two backup types: normal backup and MDS export").
-- One mds_backup of the whole server and every domain (R81.20 CLI reference), run in the background from /var/log
-- with its exit code written to a file the run polls; the Gaia configuration, licences, routes and uname go in the
-- same work directory, which is bundled, digested, fetched by SFTP, compared and removed by exact name.
-- Contract: docs/design/VENDOR_BACKUP_CONTRACTS_2026_09_22.md §7, amendment 2026-09-23. mds_backup takes the MDS
-- database lock for its duration (vendor: no SmartConsole changes until it completes) -- scheduled at night.
SELECT set_config('app.actor_fingerprint', 'migration:V61_cp_mds_export_gates', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('mds_df_var_log', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'df -P /var/log', 'read', 'SIGNED_OFF', 30,
  'none', 'once per run', 'one session for the run', 'unparseable -> refused (fail closed)', 'none', '[]'::jsonb,
  'VENDOR_BACKUP_CONTRACTS_2026_09_22.md §7 (2026-09-23)'),
 ('mds_mkdir_workdir', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'mkdir -p %s', 'recovery-write', 'SIGNED_OFF', 30,
  'none', 'once per run', 'one session for the run', 'failure -> run refused before mds_backup', 'none', '[]'::jsonb,
  'VENDOR_BACKUP_CONTRACTS_2026_09_22.md §7 (2026-09-23) -- the run''s own /var/log/nexus-mds-<job> directory'),
 ('mds_mdsstat', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'bash -lc ''mdsstat'' > %s/mdsstat.txt 2>&1', 'read', 'SIGNED_OFF', 60,
  'none', 'once per run', 'one session for the run', 'empty -> recorded as such', 'none', '[]'::jsonb,
  'VENDOR_BACKUP_CONTRACTS_2026_09_22.md §7 confirm read'),
 ('mds_gaia_configuration', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'clish -c ''show configuration'' > %s/gaia_config.txt', 'read', 'SIGNED_OFF', 120,
  'none', 'once per run', 'one session for the run', 'failure -> bundle without it', 'secret-bearing (bundle is encrypted at rest)', '[]'::jsonb,
  'amendment 2026-09-23: show configuration (already SIGNED_OFF for configuration collection) instead of lock database + save configuration'),
 ('mds_cplic_print', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'bash -lc ''cplic print -x'' > %s/cplic.txt 2>&1', 'read', 'SIGNED_OFF', 60,
  'none', 'once per run', 'one session for the run', 'failure -> bundle without it', 'licence strings (bundle encrypted at rest)', '[]'::jsonb,
  'Backbox trail 34410129'),
 ('mds_netstat_rn', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'netstat -rn > %s/netstat.txt', 'read', 'SIGNED_OFF', 30,
  'none', 'once per run', 'one session for the run', 'failure -> bundle without it', 'none', '[]'::jsonb, 'Backbox trail 34410129'),
 ('mds_uname', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'uname -a > %s/uname.txt', 'read', 'SIGNED_OFF', 30,
  'none', 'once per run', 'one session for the run', 'failure -> bundle without it', 'none', '[]'::jsonb, 'Backbox trail 34410129'),
 ('mds_backup_start', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC',
  'cd /var/log && nohup bash -lc ''$CPMDIR/scripts/mds_backup -b -l -d %1$s > %1$s/mds_backup.log 2>&1; echo $? > %1$s/mds_backup.rc'' >/dev/null 2>&1 &',
  'recovery-write', 'SIGNED_OFF', 60, 'never retried', 'once per run', 'one session for the run',
  'takes the MDS database lock until done; exit code in mds_backup.rc', 'secret-bearing archive (encrypted at rest)', '[]'::jsonb,
  'VENDOR_BACKUP_CONTRACTS_2026_09_22.md §7; R81.20 CLI reference mds_backup (-b batch, -l no logs, -d directory)'),
 ('mds_backup_poll', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'cat %s/mds_backup.rc', 'read', 'SIGNED_OFF', 30,
  'polled until the run deadline', 'every poll interval', 'one session for the run', 'no file yet -> still running', 'none', '[]'::jsonb,
  '14H asynchronous truth'),
 ('mds_list_workdir', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'ls %s', 'read', 'SIGNED_OFF', 30,
  'none', 'once per run', 'one session for the run', 'no *mdsbk* file -> failed run', 'none', '[]'::jsonb, 'Backbox trail 34410129'),
 ('mds_bundle', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'cd %1$s && tar -czf %1$s.tgz .', 'recovery-write', 'SIGNED_OFF', 3600,
  'none', 'once per run', 'one session for the run', 'failure -> run failed, work directory removed', 'secret-bearing archive', '[]'::jsonb,
  'Backbox trail 34410129 (tar -pczf)'),
 ('mds_sha256sum', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'sha256sum %s.tgz', 'read', 'SIGNED_OFF', 600,
  'none', 'once per run', 'one session for the run', 'unparseable -> digest mismatch', 'none', '[]'::jsonb, 'BK-3 digest compare'),
 ('mds_remove_workdir', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'rm -rf %1$s %1$s.tgz', 'recovery-write', 'SIGNED_OFF', 120,
  'retried once', 'once per run', 'one session for the run', 'failure -> cleanup failed outcome', 'none', '[]'::jsonb,
  'the run''s own work directory and bundle, by exact name');
