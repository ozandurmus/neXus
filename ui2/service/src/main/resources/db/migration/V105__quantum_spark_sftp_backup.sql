-- Quantum Spark Gaia Embedded settings backup: the two PO-approved clish commands (2026-09-28).
SELECT set_config('app.actor_fingerprint', 'migration:V105_quantum_spark_sftp_backup', true);
SELECT set_config('app.action_id', 'quantum_spark_sftp_backup_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('cp_spark_backup_log', 'check_point', 'gaia_embedded', 'clish', 'SSH_EXEC', 'show backup-settings-log', 'read', 'SIGNED_OFF', 30,
  'up to 2', 'before and after each backup run', 'one clish session', 'unrecognised output -> shape only, no inferred outcome',
  'server or user may appear; shape only', '[]'::jsonb, 'QUANTUM_SPARK_BACKUP_DRAFT.md #3 and #5; PO go 2026-09-28'),
 ('cp_spark_backup_push', 'check_point', 'gaia_embedded', 'clish', 'SSH_EXEC',
  'backup settings to sftp server %s filename %s file-encryption on password %s backup-policy on username nexus-spark password %s',
  'read', 'SIGNED_OFF', 180, 'none', 'once per backup run', 'one clish session',
  'no valid uploaded ZIP within 180 seconds -> refused', 'two inline passwords; transcript and logs redacted',
  '[]'::jsonb, 'QUANTUM_SPARK_BACKUP_DRAFT.md #3 and #5; PO go 2026-09-28');
