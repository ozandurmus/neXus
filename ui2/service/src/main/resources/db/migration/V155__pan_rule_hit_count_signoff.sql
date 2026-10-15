-- V155 -- sign off the Palo Alto rule hit-count read (PO chat approval 2026-10-16: "komutlar ok").
-- The read and its parser already exist (V121, PolicyHitGates, PanoramaPolicyCollector); the row was DRAFTED, so no
-- transport used it. Signing it off lets policy collection attach per-rule hit counts on direct firewall reads.
SELECT set_config('app.actor_fingerprint', 'migration:V155_pan_rule_hit_count_signoff', true);
SELECT set_config('app.action_id', 'gate_registry_signoff_by_migration', true);

UPDATE gate_registry
SET sign_off_state = 'SIGNED_OFF',
    source_document_pointer = 'PO chat approval 2026-10-16 (''komutlar ok''); PO brief rule-hit-counts'
WHERE gate_id = 'pan_policy_rule_hit_count' AND sign_off_state = 'DRAFTED';
