-- V84 -- Pulse Secure / Ivanti Connect Secure (PO 2026-09-25: "hızlıca pulse secure"; VENDOR_BACKUP_CONTRACTS §2, the
-- request sequence of Backbox trail 34411066). Admin web session: form login (password only in the form), "continue
-- the session", xsauth from the system configuration page, sysinfo (identity), the XML export's network sections
-- (inventory, element names measured first), the four exports (backup, .cfg exports protected with the device's export
-- passphrase from the credential store), logout.
SELECT set_config('app.actor_fingerprint', 'migration:V84_pulse_secure', true);
SELECT set_config('app.action_id', 'pulse_secure_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('pulse_login', 'pulse_secure', 'pulse_appliance', 'not_applicable', 'HTTPS', 'POST /dana-na/auth/url_admin/login.cgi', 'read', 'SIGNED_OFF', 60,
  'none', 'once per run', 'one session per run', 'no DSID cookie -> authentication_failed', 'the password is in the form only, never logged',
  '[]'::jsonb, 'Backbox trail 34411066; VENDOR_BACKUP_CONTRACTS §2'),
 ('pulse_continue_session', 'pulse_secure', 'pulse_appliance', 'not_applicable', 'HTTPS', 'POST /dana-na/auth/url_admin/login.cgi (btnContinue, FormDataStr)', 'read', 'SIGNED_OFF', 60,
  'none', 'when another admin session exists', 'the run session', 'refused -> authentication_failed', 'none', '[]'::jsonb, 'Backbox trail 34411066'),
 ('pulse_xsauth', 'pulse_secure', 'pulse_appliance', 'not_applicable', 'HTTPS', 'GET /dana-admin/cached/config/config.cgi?type=system', 'read', 'SIGNED_OFF', 60,
  'none', 'once per run', 'the run session', 'no xsauth -> failed', 'none', '[]'::jsonb, 'Backbox trail 34411066'),
 ('pulse_sysinfo', 'pulse_secure', 'pulse_appliance', 'not_applicable', 'HTTPS', 'GET /dana-admin/sysinfo/sysinfo.cgi', 'read', 'SIGNED_OFF', 60,
  'none', 'once per confirm and inventory', 'the run session', 'no version -> not a Pulse/Ivanti, failed', 'hostname, serial (masked to aiview)', '[]'::jsonb, 'Backbox trail 34411066'),
 ('pulse_export_network_xml', 'pulse_secure', 'pulse_appliance', 'not_applicable', 'HTTPS', 'POST /dana-admin/cached/config/config.cgi?type=exportxml (network sections)', 'read', 'SIGNED_OFF', 600,
  'none', 'once per inventory', 'the run session', 'unparseable -> inventory without interfaces, element names logged', 'addresses (masked to aiview)', '[]'::jsonb, 'Backbox trail 34411066 (subset of its flags)'),
 ('pulse_export_system_user_ivs_xml', 'pulse_secure', 'pulse_appliance', 'not_applicable', 'HTTPS', 'POST /dana-admin/download/{system,user,ivs}.cfg; POST config.cgi?type=exportxml', 'read', 'SIGNED_OFF', 600,
  'none', 'once per backup', 'the run session', 'an empty or HTML answer -> that export missing, backup partial', 'configuration and users: inside the encrypted artefact only', '[]'::jsonb, 'Backbox trail 34411066; VENDOR_BACKUP_CONTRACTS §2'),
 ('pulse_logout', 'pulse_secure', 'pulse_appliance', 'not_applicable', 'HTTPS', 'GET /dana-na/auth/logout.cgi?xsauth=', 'read', 'SIGNED_OFF', 60,
  'none', 'once per run, always', 'the run session', 'failure ignored (the session times out)', 'none', '[]'::jsonb, 'Backbox trail 34411066')
ON CONFLICT (gate_id) DO NOTHING;
