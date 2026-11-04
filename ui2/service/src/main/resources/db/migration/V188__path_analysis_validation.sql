-- V188 -- path analysis stage A6: real-environment validation against the vendors' own read-only matchers
-- (docs/design/PATH_ANALYSIS_DESIGN.md §7 A6, FROZEN; docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md rows 2-5,
-- RATIFIED, PO chat approval 2026-11-04: "1-5 onaylı devam").
--
-- Number: V188. V187 is left to the G6 lane (row 1 of the same proposal, per-VS 'table all'). Flyway runs without
-- outOfOrder: V187 must be applied before V188 on any database that already has V186, or the G6 lane renumbers.
--
--   1. Five CLASS_0 read gates, SIGNED_OFF, exactly the approved literals (V181-V183 shape). <DST>, <VSID>, VR, Z1, Z2,
--      SRC and PORT are filled by the worker only from a plan the service computed from a path result, after strict
--      validation (IPv4 literal, port 1-65535, VSID digits as stored, zone/VR names present in the device's stored
--      inventory). The Palo Alto policy-match literals carry <protocol>6</protocol> as approved: TCP only.
--      They are excluded from the diagnostic_read catalog (DiagnosticRead.DIAGNOSTIC_EXCLUDED): only path_validation
--      issues them.
--   2. ui2_job_module: V178's mapping byte-for-byte plus path_validation in the diagnostics branch (without a module the
--      claim statement never selects the job and ui2_job_owner_valid never admits its writes).
--   3. path_validation (one row per request) and path_validation_hop (one row per validated hop, at most four).
--      Raw-evidence law: no vendor output is stored anywhere. A hop keeps MATCH / MISMATCH / NOT_EVALUABLE, per-check
--      enums and reason codes, and opaque references (branch id, hop index, topology node id, device id, job id).
--      The plan (addresses, zone/VR/rule names the matcher needs) exists only while the hop is PENDING; a CHECK makes
--      any non-PENDING row carry plan = NULL, and the outcome checks reject an IPv4 literal.
--
-- Device deletion: path_validation_hop rows are REMOVED with the device (ON DELETE CASCADE on device_id); the device
-- delete removes the device's jobs first, which sets job_id NULL (ON DELETE SET NULL) on the way.
--
-- Audit (V186 convention): the request is recorded in authz_decisions (masked query, validation id, hop counts) and every
-- job row through the existing jobs audit trigger. The two tables here are derived rows written by the service and the
-- worker: no audit trigger (the transient plan would otherwise be re-captured into the audit log). The plan column is
-- still registered tier 2. Seed data only for gate_registry and audit_redaction_policy; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V188_path_analysis_validation', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('path_validation_cp_route_get', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''ip -4 route get <DST>''', 'read', 'SIGNED_OFF', 30, 'none',
     'on a PO-chosen sample only: once per hop per path_validation job, at most four hops per validation request, never polled',
     'one existing trusted SSH session per job; non-interactive bash login shell; no retry',
     'an error answer, a missing route line or an unknown route type is NOT_EVALUABLE, never a MISMATCH',
     'output carries addresses and interface names; parsed in memory to a MATCH / MISMATCH / NOT_EVALUABLE comparison only; never stored, transcribed or logged (raw-evidence law)',
     '["result", "reason"]'::jsonb,
     'docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md row 2 (RATIFIED; PO chat approval 2026-11-04: "1-5 onaylı devam")'),
    ('path_validation_cp_route_get_vsx', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv <VSID> && ip -4 route get <DST>''', 'read', 'SIGNED_OFF', 30, 'none',
     'on a PO-chosen sample only: once per hop per path_validation job, at most four hops per validation request, never polled',
     'one existing trusted SSH session per job; non-interactive bash login shell (so vsenv resolves); no retry',
     'an error answer, a missing route line or an unknown route type is NOT_EVALUABLE, never a MISMATCH',
     'output carries addresses and interface names; parsed in memory to a MATCH / MISMATCH / NOT_EVALUABLE comparison only; never stored, transcribed or logged (raw-evidence law)',
     '["result", "reason"]'::jsonb,
     'docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md row 2 (RATIFIED; PO chat approval 2026-11-04: "1-5 onaylı devam")'),
    ('path_validation_pan_fib_lookup', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<test><routing><fib-lookup><virtual-router>VR</virtual-router><ip>DST</ip></fib-lookup></routing></test>', 'read', 'SIGNED_OFF', 30, 'none',
     'on a PO-chosen sample only: once per hop per path_validation job, at most four hops per validation request, never polled',
     'one existing-authentication API key per job; serial per firewall; no retry',
     'an API error, an unknown answer shape or a missing field is NOT_EVALUABLE, never a MISMATCH',
     'output carries addresses and interface names; parsed in memory to a MATCH / MISMATCH / NOT_EVALUABLE comparison only; never stored, transcribed or logged (raw-evidence law)',
     '["result", "reason"]'::jsonb,
     'docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md row 3 (RATIFIED; PO chat approval 2026-11-04: "1-5 onaylı devam")'),
    ('path_validation_pan_security_policy_match', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<test><security-policy-match><from>Z1</from><to>Z2</to><source>SRC</source><destination>DST</destination><protocol>6</protocol><destination-port>PORT</destination-port></security-policy-match></test>', 'read', 'SIGNED_OFF', 30, 'none',
     'on a PO-chosen sample only: once per hop per path_validation job, at most four hops per validation request, never polled',
     'one existing-authentication API key per job; serial per firewall; no retry',
     'an API error, an unknown answer shape or a missing field is NOT_EVALUABLE, never a MISMATCH',
     'output carries addresses, zone and rule names; parsed in memory to a MATCH / MISMATCH / NOT_EVALUABLE comparison only; never stored, transcribed or logged (raw-evidence law)',
     '["result", "reason"]'::jsonb,
     'docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md row 4 (RATIFIED; PO chat approval 2026-11-04: "1-5 onaylı devam")'),
    ('path_validation_pan_nat_policy_match', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<test><nat-policy-match><from>Z1</from><to>Z2</to><source>SRC</source><destination>DST</destination><protocol>6</protocol><destination-port>PORT</destination-port></nat-policy-match></test>', 'read', 'SIGNED_OFF', 30, 'none',
     'on a PO-chosen sample only: once per hop per path_validation job, at most four hops per validation request, never polled',
     'one existing-authentication API key per job; serial per firewall; no retry',
     'an API error, an unknown answer shape or a missing field is NOT_EVALUABLE, never a MISMATCH',
     'output carries addresses, zone and rule names; parsed in memory to a MATCH / MISMATCH / NOT_EVALUABLE comparison only; never stored, transcribed or logged (raw-evidence law)',
     '["result", "reason"]'::jsonb,
     'docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md row 5 (RATIFIED; PO chat approval 2026-11-04: "1-5 onaylı devam")')
ON CONFLICT (gate_id) DO NOTHING;

CREATE OR REPLACE FUNCTION ui2_job_module(capability TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT CASE
 WHEN capability IN ('cp_policy_collect','pan_policy_collect') THEN 'policy'
 WHEN capability IN ('cp_gateway_backup','cp_spark_sftp_backup','cp_gaia_snapshot','pan_device_state_backup','cp_mds_export','https_vendor_backup','rdw_cc_config_backup','asa_config_backup','fgt_config_backup') THEN 'backup'
 WHEN capability IN ('device_confirm_check_point','device_confirm_palo_alto','device_confirm_https','device_confirm_cisco_asa','device_confirm_fortigate','cp_inventory_collect','pan_inventory_collect','https_inventory_collect','asa_inventory_collect','fgt_inventory_collect','cp_discovery_enumerate','pan_discovery_enumerate','rdw_discovery_enumerate','fmg_discovery_enumerate','bcmc_discovery_enumerate') THEN 'inventory'
 WHEN capability IN ('cp_failover_readiness','pan_failover_readiness','cp_cluster_failover','pan_cluster_failover','pan_failover_config_sync') THEN 'failover'
 WHEN capability IN ('diagnostic_read','fmg_interface_detail','pan_syslog_measure','cp_system_log_collect','pan_system_log_collect','cp_spike_layout_review','path_validation') THEN 'diagnostics'
 WHEN capability IN ('cp_configuration_collect','pan_configuration_collect','fgt_configuration_collect','asa_configuration_collect','proxysg_configuration_collect') THEN 'configuration'
 WHEN capability = 'packet_analysis_run' THEN 'packet_analysis'
 ELSE NULL END
$$;

CREATE TABLE IF NOT EXISTS path_validation (
    validation_id         TEXT        PRIMARY KEY CHECK (validation_id ~ '^pv-[0-9a-f-]{36}$'),
    submitted_by          TEXT        NOT NULL,
    submitted_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    topology_snapshot_id  TEXT        NOT NULL CHECK (topology_snapshot_id ~ '^tps-[A-Za-z0-9-]{1,64}$'),
    path_verdict          TEXT        NOT NULL CHECK (path_verdict IN ('ALLOWED', 'DENIED', 'PARTIAL', 'UNKNOWN', 'ALLOWED_TO_EDGE')),
    hop_count             INTEGER     NOT NULL CHECK (hop_count BETWEEN 0 AND 4),
    hops_not_validated    INTEGER     NOT NULL CHECK (hops_not_validated >= 0)
);

CREATE INDEX IF NOT EXISTS idx_path_validation_submitted_at ON path_validation (submitted_at DESC);

CREATE TABLE IF NOT EXISTS path_validation_hop (
    validation_id  TEXT        NOT NULL REFERENCES path_validation(validation_id) ON DELETE CASCADE,
    ordinal        INTEGER     NOT NULL CHECK (ordinal BETWEEN 1 AND 4),
    branch_id      TEXT        NOT NULL CHECK (branch_id ~ '^b[0-9]{1,3}$'),
    hop_index      INTEGER     NOT NULL CHECK (hop_index BETWEEN 1 AND 64),
    node_id        TEXT        NOT NULL CHECK (node_id ~ '^tn-[0-9a-f]{20}$'),
    vendor         TEXT        NOT NULL CHECK (vendor IN ('check_point', 'palo_alto', 'other')),
    device_id      TEXT        REFERENCES devices(device_id) ON DELETE CASCADE,
    job_id         TEXT        UNIQUE REFERENCES jobs(job_id) ON DELETE SET NULL,
    plan           JSONB       CHECK (plan IS NULL OR jsonb_typeof(plan) = 'object'),
    result         TEXT        NOT NULL CHECK (result IN ('PENDING', 'MATCH', 'MISMATCH', 'NOT_EVALUABLE')),
    checks         JSONB       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(checks) = 'array'
                                   AND checks::text !~ '[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}'),
    reasons        TEXT[]      NOT NULL DEFAULT '{}' CHECK (array_to_string(reasons, ',') ~ '^[A-Z0-9_,]*$'),
    evaluated_at   TIMESTAMPTZ,
    PRIMARY KEY (validation_id, ordinal),
    CONSTRAINT path_validation_hop_plan_only_while_pending CHECK (result = 'PENDING' OR plan IS NULL),
    CONSTRAINT path_validation_hop_pending_has_device CHECK (result <> 'PENDING' OR device_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_path_validation_hop_device ON path_validation_hop (device_id) WHERE device_id IS NOT NULL;

SELECT set_config('app.action_id', 'audit_redaction_policy_seed_by_migration', true);

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('path_validation_hop', 'plan', 2, 'transient matcher parameters: addresses, zone, virtual-router and rule names')
ON CONFLICT (table_name, column_name) DO NOTHING;

-- The service inserts requests and hops and abandons a hop it could not admit; the worker records outcomes. Nothing is
-- deleted by the application (hops go only with their device, above; no retention pruning yet).
GRANT SELECT, INSERT ON path_validation TO ui2_app;
GRANT SELECT, INSERT, UPDATE ON path_validation_hop TO ui2_app;
