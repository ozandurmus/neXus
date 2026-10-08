-- PO-approved one-off measurement; all existing command bounds remain unchanged.
SELECT set_config('app.actor_fingerprint', 'migration:V142_cpview_measurement_sign_off', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

UPDATE gate_registry SET
    sign_off_state = 'SIGNED_OFF',
    source_document_pointer = source_document_pointer || '; PO chat approval 2026-10-07 for one measurement on one plain gateway member'
WHERE gate_id = 'cp_diagnostic_cpview_measure';
