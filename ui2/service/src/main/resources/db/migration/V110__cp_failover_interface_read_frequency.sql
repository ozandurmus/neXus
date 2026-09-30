-- V101 set the failover frequency on the V15 rows 'cphaprob -a -m if' (superseded by V36's documented form); the
-- failover pre/post checks send 'cphaprob -a if' (V36 rows). Record the frequency on the rows actually used.
SELECT set_config('app.actor_fingerprint', 'migration:V110_cp_failover_interface_read_frequency', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);
UPDATE gate_registry SET max_frequency = 'once pre and once post per member per failover run; once per readiness run'
WHERE gate_id IN ('cp_inventory_cphaprob_a_if', 'cp_inventory_vsid_cphaprob_a_if');
