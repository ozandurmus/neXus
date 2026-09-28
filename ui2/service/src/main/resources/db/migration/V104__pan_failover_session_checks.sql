ALTER TABLE failover_check_result DROP CONSTRAINT failover_check_result_check_no_check;
ALTER TABLE failover_check_result ADD CONSTRAINT failover_check_result_check_no_check
    CHECK (check_no IN (1, 2, 3, 4, 5, 6, 7, 8, 9, 10));

SELECT set_config('app.actor_fingerprint', 'migration:V104_pan_failover_session_checks', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);
UPDATE gate_registry SET max_frequency = 'once pre and once post per peer per run'
WHERE gate_id = 'pan_inventory_show_system_info';
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('pan_failover_session_sync', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<show><high-availability><state-synchronization/></high-availability></show>',
     'read', 'SIGNED_OFF', 30, 'none', 'once pre and once post per peer per run',
     'one key per peer per run', 'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/FAILOVER_EXECUTION_PAN_CONTRACT.md §6'),
    ('pan_failover_session_info', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<show><session><info/></session></show>',
     'read', 'SIGNED_OFF', 30, 'none', 'once pre and once post per peer per run',
     'one key per peer per run', 'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/FAILOVER_EXECUTION_PAN_CONTRACT.md §6');
