-- V79 -- Cisco ASA archive after the configuration text (PO 2026-09-25: "ssh scopy enabled olmalı. İkisi de olsun,
-- sırayla alır, hata veren kısım eksik gözükür"). The ASA writes its own archive to flash, neXus pulls it over SCP
-- (the ASA administrators enable "ssh scopy enable"; neXus never changes it) and deletes that one file by its own
-- name. A failed archive stores the text and fails the job as "partial". docs/design/CISCO_ASA_CONTRACT.md.
SELECT set_config('app.actor_fingerprint', 'migration:V79_cisco_asa_archive', true);
SELECT set_config('app.action_id', 'cisco_asa_archive_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('asa_backup_archive', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC',
  'backup /noconfirm location disk0:/nexus-<job hex>.tar.gz', 'recovery-write', 'SIGNED_OFF', 600,
  'none', 'once per backup job', 'the text-read session', 'no "Backup finished" -> archive missing, text stored, job partial',
  'the archive holds certificates and keys: stored only inside the encrypted backup artefact', '[]'::jsonb,
  'Cisco ASA 9.22 general configuration guide (Back Up and Restore); Backbox ASA 9.4+ trail 2026-09-22; PO 2026-09-25'),
 ('asa_scp_fetch_archive', 'cisco_asa', 'cisco_asa_firewall', 'scp_source', 'SSH_EXEC',
  'scp -f disk0:/nexus-<job hex>.tar.gz', 'read', 'SIGNED_OFF', 600,
  'none', 'once per backup job', 'a second SSH session', 'refused (ssh scopy not enabled) -> archive missing, job partial',
  'as above', '[]'::jsonb, 'Cisco ASA command reference (ssh scopy enable); Backbox ASA trail (scp pull)'),
 ('asa_delete_archive', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC',
  'delete /noconfirm disk0:/nexus-<job hex>.tar.gz', 'recovery-write', 'SIGNED_OFF', 30,
  'none', 'once per backup job, only when the archive command ran', 'a third SSH session',
  'refused or timed out -> cleanup_failed: the device is excluded from backups until cleared',
  'none', '[]'::jsonb, 'Cisco ASA command reference (delete); name restricted to nexus-<hex>.tar.gz')
ON CONFLICT (gate_id) DO NOTHING;
