-- V73 -- Quantum Spark / Gaia Embedded hostname read (PO 2026-09-25: observed facts follow every read). A Spark
-- appliance's shell is clish itself, so the gateway path's "clish -c 'show hostname'" is absent there (measured
-- 2026-09-25: four 1570/1590 appliances completed their Collect with no hostname). The bare clish command, the same
-- read. Its version comes from the already-gated bare "show version all" (cp_gaia_show_version_all).
SELECT set_config('app.actor_fingerprint', 'migration:V73_cp_spark_show_hostname', true);
SELECT set_config('app.action_id', 'cp_spark_show_hostname_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('cp_spark_show_hostname', 'check_point', 'cp_gaia_gateway', 'clish', 'SSH_EXEC', 'show hostname', 'read', 'SIGNED_OFF', 30,
  'none', 'once per device per inventory run', 'one session per device per run', 'an empty hostname is a result, not an error',
  'none', '[]'::jsonb, 'PO 2026-09-25: observed facts follow every read; Quantum Spark answers clish natively')
ON CONFLICT (gate_id) DO NOTHING;
