-- PO 2026-09-28: Spark shares the approved Cyber Controller receiver account.
SELECT set_config('app.actor_fingerprint', 'migration:V106_spark_shared_sftp_receiver', true);
SELECT set_config('app.action_id', 'spark_shared_sftp_receiver_by_migration', true);

UPDATE gate_registry
SET canonical_command_key = 'backup settings to sftp server %s filename %s file-encryption on password %s backup-policy on username nexus-cc password %s'
WHERE gate_id = 'cp_spark_backup_push';
