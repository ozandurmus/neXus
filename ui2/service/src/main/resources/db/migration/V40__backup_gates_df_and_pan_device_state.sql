-- Backup gate rows measured on the first real runs (Product Owner, 2026-09-22).
--
-- 1. Check Point: gate row cp_backup_show_diskspace already records (BK-7) that "if the Clish form
--    fails, the Expert fallback df -P /var/log becomes primary". The first live run measured exactly
--    that failure (a one-line CLI error with exit 0, misread as 329 KB free), so the fallback gets
--    its own row under the same entry 1.
-- 2. Palo Alto: pan_device_state_backup existed as an executor and a capability id but had no gate
--    rows and was never registered, so every PAN backup request was refused CAPABILITY_UNKNOWN. Its
--    two reads are the XML API calls the executor already issues -- the same shape Backbox's own
--    measured PAN backup uses (keygen, then type=export&category=device-state).
SELECT set_config('app.actor_fingerprint', 'migration:V40_backup_gates_df_and_pan_device_state', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_backup_df_var_log', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'df -P /var/log', 'read', 'SIGNED_OFF', 30, 'none', 'once per run, before the submit, only when show diskspace did not answer',
     'one session held for the whole run',
     'the Expert fallback named by cp_backup_show_diskspace (BK-7); POSIX df, Available column in 1K blocks',
     'none', '[]'::jsonb, 'docs/design/CP_BACKUP_COMMAND_GATE_ENTRIES.md entry 1'),

    ('pan_backup_config_show', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     'type=config&action=show', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'the one key-generation session per run',
     'companion running-config read for the semantic deviation diff; secret-bearing leaves withheld in any view, encrypted at rest',
     'none', '[]'::jsonb, 'docs/design/BACKUP_RECOVERY_CONTRACTS.md (PAN device-state) -- Product Owner directive 2026-09-22, Backbox trail 34411050 as measurement'),

    ('pan_backup_export_device_state', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     'type=export&category=device-state', 'read', 'SIGNED_OFF', 600, 'none', 'once per device per run',
     'the one key-generation session per run',
     'streamed straight into the envelope-encrypted artefact store; never held whole in memory',
     'none', '[]'::jsonb, 'docs/design/BACKUP_RECOVERY_CONTRACTS.md (PAN device-state) -- Product Owner directive 2026-09-22, Backbox trail 34411050 as measurement');
