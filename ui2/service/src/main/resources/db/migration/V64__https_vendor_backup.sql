-- V64 -- vendors backed up over HTTPS (VENDOR_BACKUP_CONTRACTS_2026_09_22.md §0-§1, §4; measurements addendum
-- 2026-09-24): Infoblox Grid Manager (WAPI) and Radware DefensePro first. The manifest vendor list is widened to the
-- eight contracted vendors at once (§0.1); a device's second secret (Radware's export passphrase, later the ASA enable
-- or MDS expert password) is a credential-store reference per purpose, never a value.
SELECT set_config('app.actor_fingerprint', 'migration:V64_https_vendor_backup', true);
SELECT set_config('app.action_id', 'https_vendor_backup_by_migration', true);

DO $$
DECLARE c text;
BEGIN
  SELECT conname INTO c FROM pg_constraint
   WHERE conrelid = 'backup_artefact_manifest'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%vendor%';
  IF c IS NOT NULL THEN
    EXECUTE format('ALTER TABLE backup_artefact_manifest DROP CONSTRAINT %I', c);
  END IF;
END $$;
ALTER TABLE backup_artefact_manifest ADD CONSTRAINT backup_artefact_manifest_vendor_check
  CHECK (vendor IN ('check_point', 'palo_alto', 'infoblox', 'radware', 'fortinet', 'cisco_asa', 'pulse_secure', 'bluecoat'));

CREATE TABLE device_secret_reference (
    device_id                TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    purpose                  TEXT NOT NULL CHECK (purpose IN ('export_passphrase', 'enable_password', 'expert_password')),
    credential_reference_id  TEXT NOT NULL,
    set_by_actor_fingerprint TEXT NOT NULL,
    set_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, purpose)
);
CREATE TRIGGER trg_audit_device_secret_reference
    AFTER INSERT OR UPDATE OR DELETE ON device_secret_reference
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('device_id');
GRANT SELECT, INSERT, UPDATE, DELETE ON device_secret_reference TO ui2_app;

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('infoblox_wapidoc_version', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS', 'GET /wapidoc/', 'read', 'SIGNED_OFF', 30,
  'none', 'once per run', 'one client per run', 'no VERSION line -> refused', 'none', '[]'::jsonb, 'measurements addendum 2026-09-24, trail 34411065 step 2-3'),
 ('infoblox_grid_identity', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS', 'GET /wapi/v<ver>/grid', 'read', 'SIGNED_OFF', 30,
  'none', 'once per confirm', 'one client per run', 'no name -> identity UNKNOWN', 'none', '[]'::jsonb, 'VENDOR_BACKUP_CONTRACTS_2026_09_22.md §4 confirm'),
 ('infoblox_fileop_getgriddata', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS', 'POST /wapi/v<ver>/fileop?_function=getgriddata', 'recovery-write', 'SIGNED_OFF', 600,
  'never retried', 'once per run', 'one client per run', 'no token/url -> refused', 'token (never persisted)', '[]'::jsonb, 'trail 34411065 step 5'),
 ('infoblox_download', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS', 'GET <getgriddata url, same host>', 'read', 'SIGNED_OFF', 600,
  'none', 'once per run', 'one client per run', 'off-host url -> refused', 'secret-bearing grid backup (encrypted at rest)', '[]'::jsonb, 'trail 34411065 step 9'),
 ('infoblox_fileop_downloadcomplete', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS', 'POST /wapi/v<ver>/fileop?_function=downloadcomplete', 'recovery-write', 'SIGNED_OFF', 30,
  'none', 'always, after every getgriddata', 'one client per run', 'failure logged', 'none', '[]'::jsonb, 'trail 34411065 step 12 -- sent with the read version (Backbox sends v/ and fails)'),
 ('radware_root_reachability', 'radware', 'radware_defensepro', 'not_applicable', 'HTTPS', 'GET /', 'read', 'SIGNED_OFF', 30,
  'none', 'once per confirm', 'one client per run', 'identity UNKNOWN (MEASURE FIRST)', 'none', '[]'::jsonb, 'VENDOR_BACKUP_CONTRACTS_2026_09_22.md §1 confirm'),
 ('radware_backup_receive_configuration', 'radware', 'radware_defensepro', 'not_applicable', 'HTTPS', 'POST /dynamic/File/Configuration/ReceivefromDevice', 'read', 'SIGNED_OFF', 120,
  'none', 'once per run', 'one client per run', 'under 1 KB -> not a backup', 'private keys encrypted with the passphrase (encrypted at rest)', '[]'::jsonb,
  'trail 34095224: form DownloadFormat=cli&IncludePKeys=on&passphrase=<credential store>');
