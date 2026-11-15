-- V222 -- sign off the four Cisco ASA failover readiness reads (PO approval 2026-11-14, relayed by the PO session:
-- "Onaylıyorum" to show failover, show failover state, show failover history, show conn count; run only when a
-- readiness check runs, on both members of an ASA pair, read-only, masked output). Record:
-- docs/design/PO_DECISION_RECORD_2026_11_14_ASA_FAILOVER_READINESS_READS.md.
-- The rows were inserted DRAFTED by V212; only those four rows change, and only from DRAFTED. No state-changing
-- literal is added: the ASA switch (no failover active) waits for the decision council. The one-time measurement rows
-- (asa_measure_*, V210) are untouched. The readiness switch UI2_ASA_FAILOVER_READINESS is unchanged (off by default).
-- Dry-run in BEGIN/ROLLBACK on the live database before rollout.
SELECT set_config('app.actor_fingerprint', 'migration:V222_asa_failover_readiness_signoff', true);
SELECT set_config('app.action_id', 'gate_registry_signoff_by_migration', true);

UPDATE gate_registry
SET sign_off_state = 'SIGNED_OFF',
    source_document_pointer = 'Cisco ASA command reference (' || canonical_command_key || '); PO approval 2026-11-14 '
        || '(''Onaylıyorum'': readiness checks only, both members, read-only, masked output); '
        || 'PO_DECISION_RECORD_2026_11_14_ASA_FAILOVER_READINESS_READS.md'
WHERE gate_id IN ('asa_failover_show_failover', 'asa_failover_show_failover_state',
                  'asa_failover_show_failover_history', 'asa_failover_show_conn_count')
  AND vendor = 'cisco_asa' AND action_class = 'read' AND sign_off_state = 'DRAFTED';
