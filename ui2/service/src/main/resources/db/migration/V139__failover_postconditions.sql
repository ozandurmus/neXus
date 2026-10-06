-- PO-approved PR9: retain existing commands; extend their bounded verification phases.
ALTER TABLE failover_check_result DROP CONSTRAINT failover_check_result_phase_check;
ALTER TABLE failover_check_result ADD CONSTRAINT failover_check_result_phase_check
    CHECK (phase IN ('pre', 'post', 'post_return'));
ALTER TABLE failover_check_result DROP CONSTRAINT failover_check_result_status_check;
ALTER TABLE failover_check_result ADD CONSTRAINT failover_check_result_status_check
    CHECK (status IN ('PASS', 'FAIL', 'UNKNOWN', 'WARN', 'NOT_EVALUATED'));

SELECT set_config('app.actor_fingerprint', 'migration:V139_failover_postconditions', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

UPDATE gate_registry SET
    max_frequency = 'once pre and twice in each post-switch and post-return phase per member; shared budget of 21 polls every 3 s across DOWN and UP per run',
    source_document_pointer = 'docs/design/PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md item 7; approved PR9 phases'
WHERE gate_id IN ('cp_inventory_cphaprob_stat', 'cp_inventory_vsid_cphaprob_stat');

UPDATE gate_registry SET
    max_frequency = 'once pre, once post-switch and once post-return per member per failover run; once per readiness run',
    source_document_pointer = 'docs/design/PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md item 7; approved PR9 phases'
WHERE gate_id IN ('cp_inventory_cphaprob_a_if', 'cp_inventory_vsid_cphaprob_a_if', 'pan_inventory_show_system_info', 'cp_failover_tablestat', 'pan_failover_session_sync', 'pan_failover_session_info', 'cp_failover_arp', 'cp_failover_syncstat', 'cp_failover_fw_stat', 'cp_failover_tablestat_vsid', 'cp_failover_arp_vsid', 'cp_failover_syncstat_vsid', 'cp_failover_fw_stat_vsid', 'cp_failover_pnotes', 'cp_failover_pnotes_vsid', 'cp_failover_bonds', 'cp_failover_bonds_vsid', 'cp_failover_last_event', 'cp_failover_last_event_vsid', 'cp_failover_routing', 'cp_failover_routing_vsid');

UPDATE gate_registry SET
    max_frequency = 'once pre, once post-switch and twice post-return per peer; additionally every 3 s for at most 60 s per transition',
    source_document_pointer = 'docs/design/PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md item 7; approved PR9 phases'
WHERE gate_id IN ('pan_inventory_show_ha_state');

UPDATE gate_registry SET
    max_frequency = 'once per device per inventory run; once pre, once post-switch and once post-return per member per failover run; once per readiness run',
    session_reuse_rule = 'existing inventory or failover SSH session per member; serial; gateway Expert login shell',
    unsupported_behavior_ref = 'missing policy or install time -> UNKNOWN; blocks failover, inventory continues',
    secret_output_risk = 'policy identities stay in memory; only equality, presence and install-time skew are projected',
    source_document_pointer = 'docs/design/PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md item 7; approved PR9 phases'
WHERE gate_id IN ('cp_policy_install_cpstat');

UPDATE gate_registry SET
    max_frequency = 'two samples 5 s apart per member in baseline immediately before DOWN, post-switch and post-return; once per readiness run',
    source_document_pointer = 'docs/design/PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md item 7; approved PR9 phases'
WHERE gate_id IN ('cp_failover_traffic', 'cp_failover_traffic_vsid');
