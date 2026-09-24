-- V62 -- the MDS export start is detached from the SSH channel (setsid, stdin from /dev/null). Measured 2026-09-24:
-- without it the exec channel stayed open on the background process's stdin and the start timed out while
-- mds_backup ran. The gate row's key follows the command.
SELECT set_config('app.actor_fingerprint', 'migration:V62_cp_mds_export_start_detached', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

UPDATE gate_registry SET canonical_command_key =
  'cd /var/log && setsid nohup bash -lc ''$CPMDIR/scripts/mds_backup -b -l -d %1$s > %1$s/mds_backup.log 2>&1; echo $? > %1$s/mds_backup.rc'' </dev/null >/dev/null 2>&1 &'
WHERE gate_id = 'mds_backup_start';
