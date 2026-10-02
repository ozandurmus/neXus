-- PO 2026-10-02: amend only the existing access-rulebase read budget.
SELECT set_config('app.actor_fingerprint', 'migration:V118_cp_policy_page_budget', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);
UPDATE gate_registry SET canonical_command_key = 'mgmt_cli -r true -d ''<DOMAIN>'' -f json show-access-rulebase name ''<LAYER>'' limit 100 offset ''<N>'' details-level full use-object-dictionary true',
    timeout_s = 300, retry_rule = 'once on timeout with limit 50'
WHERE gate_id = 'cp_policy_access_rulebase';
