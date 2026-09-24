-- V69 -- Radware Cyber Controller's own configuration backup (RADWARE_CYBER_CONTROLLER_OWN_BACKUP_RECEIVER.md, approved by
-- the Product Owner 2026-09-24): create, export by SFTP to HOST-A's chrooted receiver, list, delete -- on its restricted CLI.
-- The receiver password is a device secret of the Cyber Controller (purpose backup_receiver), a credential-store reference.
SELECT set_config('app.actor_fingerprint', 'migration:V69_radware_cc_own_backup', true);
SELECT set_config('app.action_id', 'radware_cc_own_backup_by_migration', true);

ALTER TABLE device_secret_reference DROP CONSTRAINT IF EXISTS device_secret_reference_purpose_check;
ALTER TABLE device_secret_reference ADD CONSTRAINT device_secret_reference_purpose_check
  CHECK (purpose IN ('export_passphrase', 'enable_password', 'expert_password', 'backup_receiver'));

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('rdw_cc_backup_create', 'radware', 'radware_cyber_controller', 'cc_cli', 'SSH_EXEC', 'system backup config create %s', 'recovery-write', 'SIGNED_OFF', 600,
  'none', 'once per job', 'one session for the run', 'the backup is not listed afterwards -> refused, nothing to delete', 'none', '[]'::jsonb, 'RADWARE_CYBER_CONTROLLER_OWN_BACKUP_RECEIVER.md #1; measured 2026-09-24'),
 ('rdw_cc_backup_export', 'radware', 'radware_cyber_controller', 'cc_cli', 'SSH_EXEC', 'system backup config export %s %s', 'recovery-write', 'SIGNED_OFF', 600,
  'none', 'once per job', 'one session for the run', 'no "Export completed." -> refused, the backup deleted', 'prompt answer carries the receiver password (never logged)', '[]'::jsonb, '#2; measured: Password: prompt, "Export completed.", .tar appended'),
 ('rdw_cc_backup_list', 'radware', 'radware_cyber_controller', 'cc_cli', 'SSH_EXEC', 'system backup config list', 'read', 'SIGNED_OFF', 60,
  'none', 'once per job', 'one session for the run', 'our name absent -> refused', 'backup names only', '[]'::jsonb, '#3'),
 ('rdw_cc_backup_delete', 'radware', 'radware_cyber_controller', 'cc_cli', 'SSH_EXEC', 'system backup config delete %s', 'recovery-write', 'SIGNED_OFF', 120,
  'none', 'once per job, after the store or after a failure', 'one session for the run', 'no "Remove completed." -> CLEANUP_FAILED', 'none', '[]'::jsonb, '#4; measured: (Y/N)? answered y, "Remove completed."');
