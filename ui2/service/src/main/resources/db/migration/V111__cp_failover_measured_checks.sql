-- PO approved 2026-09-30: cphaprob -ia list, cphaprob show_bond,
-- cphaprob show_failover, cpstat os -f routing (plain and vsenv forms).
-- Extends the existing failover check pipeline; no new device writes.
ALTER TABLE failover_check_result DROP CONSTRAINT failover_check_result_check_no_check;
ALTER TABLE failover_check_result ADD CONSTRAINT failover_check_result_check_no_check
    CHECK (check_no IN (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14));
ALTER TABLE failover_check_result DROP CONSTRAINT failover_check_result_status_check;
ALTER TABLE failover_check_result ADD CONSTRAINT failover_check_result_status_check
    CHECK (status IN ('PASS', 'FAIL', 'UNKNOWN', 'WARN'));

SELECT set_config('app.actor_fingerprint', 'migration:V111_cp_failover_measured_checks', true);
SELECT set_config('app.action_id', 'gate_registry_insert_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
SELECT 'cp_failover_' || cmd.id || CASE WHEN ctx.vsx THEN '_vsid' ELSE '' END,
    'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
    CASE WHEN ctx.vsx THEN 'bash -lc ''vsenv <VSID> && ' || cmd.command || '''' ELSE cmd.command END,
    'read', 'SIGNED_OFF', 30, 'none', 'once pre and once post per member per run',
    'one SSH session per member per run; commands serial', 'UNKNOWN', 'none', cmd.telemetry::jsonb,
    'ui2/service/src/main/resources/db/migration/V111__cp_failover_measured_checks.sql (PO approved 2026-09-30)'
FROM (VALUES
    ('pnotes', 'cphaprob -ia list', '["pnotes"]'),
    ('bonds', 'cphaprob show_bond', '[]'),
    ('last_event', 'cphaprob show_failover', '["lastFailoverAt", "assumedTimeZone"]'),
    ('routing', 'cpstat os -f routing', '["routeCount", "defaultRoute"]')
) AS cmd(id, command, telemetry)
CROSS JOIN (VALUES (false), (true)) AS ctx(vsx);
