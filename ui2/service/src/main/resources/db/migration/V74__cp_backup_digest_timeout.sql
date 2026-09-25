-- V74 -- the device-side archive digest's time bound (2026-09-25): measured on a gateway with a multi-GB archive,
-- sha256sum ran past the flat 60 s and the run failed as digest UNPARSEABLE. The worker now allows one second per
-- 20 MB of archive, at least 60 s and at most 600 s -- the MDS export's own digest bound (mds_sha256sum).
SELECT set_config('app.actor_fingerprint', 'migration:V74_cp_backup_digest_timeout', true);
SELECT set_config('app.action_id', 'cp_backup_digest_timeout_by_migration', true);
UPDATE gate_registry SET timeout_s = 600 WHERE gate_id = 'cp_backup_archive_digest';
