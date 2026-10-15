-- V151 -- system-log programme, phase A measurement for Palo Alto (PO chat approval 2026-10-15, M3/M4).
-- Three read-only PAN-OS XML API requests on every enrolled Palo Alto firewall (never Panorama): enqueue a system-log
-- and a config-log query of 100 entries, and fetch each log job's results until FIN with a bounded wait. The job
-- (pan_syslog_measure) keeps only a per-source summary -- counts, field names, formats and enum-like
-- subtype/severity/eventid/cmd values -- in system_log_measurement; log entries are never persisted.
-- docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4.
SELECT set_config('app.actor_fingerprint', 'migration:V151_pan_syslog_measurement', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('pan_syslog_measure_system_enqueue', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     'type=log&log-type=system&nlogs=100', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: manual admin request, at most once per firewall per job window',
     'one existing-authentication API key per job; serial per firewall',
     'an API error or an unfinished log job is UNKNOWN, never a number',
     'log entries carry user names, addresses and host names; in-memory summary only (counts, field names, formats, enum values); no raw retention',
     '["entries", "spanSeconds", "resultBytes", "enqueueToFinMs", "fieldNames", "receiveTimeFormat", "subtypeCounts", "severityCounts", "eventidTopCounts"]'::jsonb,
     'DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15 for M3/M4 on every enrolled Palo Alto firewall'),
    ('pan_syslog_measure_config_enqueue', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     'type=log&log-type=config&nlogs=100', 'read', 'SIGNED_OFF', 30, 'none',
     'phase A measurement only: manual admin request, at most once per firewall per job window',
     'one existing-authentication API key per job; serial per firewall',
     'an API error or an unfinished log job is UNKNOWN, never a number',
     'log entries carry user names, addresses and host names; in-memory summary only (counts, field names, formats, enum values); no raw retention',
     '["entries", "spanSeconds", "resultBytes", "enqueueToFinMs", "fieldNames", "receiveTimeFormat", "cmdCounts"]'::jsonb,
     'DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15 for M3/M4 on every enrolled Palo Alto firewall'),
    ('pan_syslog_measure_job_get', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     'type=log&action=get&job-id=<id>', 'read', 'SIGNED_OFF', 30, 'none',
     'polled every 2 s until FIN, at most 30 polls per enqueued log job; <id> is the digits-only job id the enqueue answered',
     'one existing-authentication API key per job; serial per firewall',
     'a job not FIN within the bound, or an API error, is UNKNOWN, never a number',
     'log entries carry user names, addresses and host names; in-memory summary only (counts, field names, formats, enum values); no raw retention',
     '["jobStatus", "entries", "resultBytes"]'::jsonb,
     'DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15 for M3/M4 on every enrolled Palo Alto firewall')
ON CONFLICT (gate_id) DO NOTHING;

-- Module ownership: the measurement job is a diagnostics job (V128's mapping plus pan_syslog_measure). Without a
-- module the claim statement never selects the job.
CREATE OR REPLACE FUNCTION ui2_job_module(capability TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT CASE
 WHEN capability IN ('cp_policy_collect','pan_policy_collect') THEN 'policy'
 WHEN capability IN ('cp_gateway_backup','cp_spark_sftp_backup','cp_gaia_snapshot','pan_device_state_backup','cp_mds_export','https_vendor_backup','rdw_cc_config_backup','asa_config_backup','fgt_config_backup') THEN 'backup'
 WHEN capability IN ('device_confirm_check_point','device_confirm_palo_alto','device_confirm_https','device_confirm_cisco_asa','device_confirm_fortigate','cp_inventory_collect','pan_inventory_collect','https_inventory_collect','asa_inventory_collect','fgt_inventory_collect','cp_discovery_enumerate','pan_discovery_enumerate','rdw_discovery_enumerate','fmg_discovery_enumerate','bcmc_discovery_enumerate') THEN 'inventory'
 WHEN capability IN ('cp_failover_readiness','pan_failover_readiness','cp_cluster_failover','pan_cluster_failover') THEN 'failover'
 WHEN capability IN ('diagnostic_read','fmg_interface_detail','pan_syslog_measure') THEN 'diagnostics'
 WHEN capability IN ('cp_configuration_collect','pan_configuration_collect','fgt_configuration_collect','asa_configuration_collect','proxysg_configuration_collect') THEN 'configuration'
 ELSE NULL END
$$;

-- One row per measured source per job. The summary holds counts, field names, formats and enum-like values only.
CREATE TABLE IF NOT EXISTS system_log_measurement (
    measurement_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    vendor TEXT NOT NULL CHECK (vendor IN ('palo_alto')),
    source TEXT NOT NULL CHECK (source IN ('pan_system', 'pan_config')),
    measured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    summary JSONB NOT NULL CHECK (jsonb_typeof(summary) = 'object'
        AND coalesce(summary->>'status', '') IN ('MEASURED', 'UNKNOWN')
        AND NOT summary ?| ARRAY['entry', 'logs', 'lines', 'messages', 'opaque', 'raw']),
    job_id TEXT NOT NULL,
    UNIQUE (job_id, source)
);
CREATE INDEX IF NOT EXISTS system_log_measurement_device_time ON system_log_measurement(device_id, measured_at DESC);
GRANT SELECT, INSERT, UPDATE, DELETE ON system_log_measurement TO ui2_app;
DROP TRIGGER IF EXISTS trg_audit_system_log_measurement ON system_log_measurement;
CREATE TRIGGER trg_audit_system_log_measurement
    AFTER INSERT OR UPDATE OR DELETE ON system_log_measurement
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('measurement_id');
