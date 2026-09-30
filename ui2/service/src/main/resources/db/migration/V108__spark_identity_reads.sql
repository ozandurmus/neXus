SELECT set_config('app.actor_fingerprint', 'migration:V108_spark_identity_reads', true);
SELECT set_config('app.action_id', 'spark_identity_reads_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('cp_spark_show_diag', 'check_point', 'gaia_embedded', 'clish', 'SSH_EXEC', 'show diag', 'read', 'SIGNED_OFF', 30,
  'up to 1', 'confirm, nightly inventory, diagnostics', 'one clish session', 'unrecognised output -> fields UNKNOWN',
  'serial number (identity, masked on screen)', '[]'::jsonb, 'QUANTUM_SPARK_BACKUP_DRAFT.md #7; PO 2026-09-28 ''gönder ekle'''),
 ('cp_spark_show_software_version', 'check_point', 'gaia_embedded', 'clish', 'SSH_EXEC', 'show software-version', 'read', 'SIGNED_OFF', 30,
  'up to 1', 'confirm, nightly inventory, diagnostics', 'one clish session', 'unrecognised output -> version UNKNOWN',
  'serial number (identity, masked on screen)', '[]'::jsonb, 'QUANTUM_SPARK_BACKUP_DRAFT.md #7; PO 2026-09-28 ''gönder ekle''');
