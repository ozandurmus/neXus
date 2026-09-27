ALTER TABLE failover_approval ADD COLUMN vendor TEXT NOT NULL DEFAULT 'check_point'
    CHECK (vendor IN ('check_point', 'palo_alto'));
ALTER TABLE failover_run ADD COLUMN vendor TEXT NOT NULL DEFAULT 'check_point'
    CHECK (vendor IN ('check_point', 'palo_alto'));
DROP INDEX uq_failover_run_active_unit;
CREATE UNIQUE INDEX uq_failover_run_active_unit ON failover_run(vendor, cluster_ref, coalesce(vs_id, ''))
    WHERE state NOT IN ('DONE', 'STOPPED');
ALTER TABLE failover_check_result DROP CONSTRAINT failover_check_result_check_no_check;
ALTER TABLE failover_check_result ADD CONSTRAINT failover_check_result_check_no_check
    CHECK (check_no IN (1, 2, 3, 4, 5, 6, 8, 9, 10));

SELECT set_config('app.actor_fingerprint', 'migration:V103_pan_failover_execution', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);
UPDATE gate_registry SET max_frequency = 'pre/post once per peer; additionally every 3 s for at most 60 s per transition'
WHERE gate_id = 'pan_inventory_show_ha_state';

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('pan_failover_suspend', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<request><high-availability><state><suspend/></state></high-availability></request>',
     'operational-state-change', 'SIGNED_OFF', 30, 'none', 'once on former active per run',
     'one key per peer per run', 'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/FAILOVER_EXECUTION_PAN_CONTRACT.md §4'),
    ('pan_failover_functional', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<request><high-availability><state><functional/></state></high-availability></request>',
     'operational-state-change', 'SIGNED_OFF', 30, 'none', 'once on former active per run',
     'one key per peer per run', 'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/FAILOVER_EXECUTION_PAN_CONTRACT.md §4');
