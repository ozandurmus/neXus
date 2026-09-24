-- V63 -- the MDS export reads the server's Gaia version itself (C7 §3.3's third source), like the Gaia backup now
-- does under cp_configuration_show_version_all. 2026-09-24: an MDS had no version from confirm or inventory and its
-- verified backup was refused at the store.
SELECT set_config('app.actor_fingerprint', 'migration:V63_mds_show_version_gate', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('mds_show_version_all', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'clish -c ''show version all''', 'read',
  'SIGNED_OFF', 30, 'none', 'once per run', 'one session for the run', 'no version -> the store refuses (C7 §3.3)', 'none', '[]'::jsonb,
  'CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md entry 2 (same literal, MDS scope)');
