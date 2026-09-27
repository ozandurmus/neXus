ALTER TABLE failover_check_result DROP CONSTRAINT failover_check_result_check_no_check;
ALTER TABLE failover_check_result ADD CONSTRAINT failover_check_result_check_no_check
    CHECK (check_no IN (1, 2, 3, 5, 6, 8, 9, 10));

SELECT set_config('app.actor_fingerprint', 'migration:V102_cp_failover_sync_policy_checks', true);
SELECT set_config('app.action_id', 'gate_registry_insert_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
SELECT 'cp_failover_' || cmd.id || CASE WHEN ctx.vsx THEN '_vsid' ELSE '' END,
    'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
    CASE WHEN ctx.vsx THEN 'bash -lc ''vsenv <VSID> && ' || cmd.command || '''' ELSE cmd.command END,
    'read', 'SIGNED_OFF', 30, 'none', 'once pre and once post per member per run',
    'one SSH session per member per run; commands serial', 'UNKNOWN', 'none', '[]'::jsonb,
    'docs/design/FAILOVER_EXECUTION_CP_CONTRACT.md §12'
FROM (VALUES
    ('syncstat', 'cphaprob syncstat'),
    ('fw_stat', 'fw stat')
) AS cmd(id, command)
CROSS JOIN (VALUES (false), (true)) AS ctx(vsx);
