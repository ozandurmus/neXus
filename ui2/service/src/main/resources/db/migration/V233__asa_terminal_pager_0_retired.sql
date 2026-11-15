-- V233 -- PO decision 2026-11-15: the Cisco ASA SSH session no longer sends "terminal pager 0" (Cisco warns that it can raise
-- the device CPU). The shell answers the pager prompt "<--- More --->" with a space instead and strips the prompt and its
-- erase sequence, the way FortiOS "--More--" is handled; no console setting is changed on the device. Removed per PO 2026-11-15.
--
-- The gate row is withdrawn with the registry's own state for it: BLOCKED resolves UNKNOWN in GateResolver, so nothing that
-- resolves its command through the gate can send it. The row is kept, not deleted, as the record of what was sent before
-- (V36's append-only discipline for gate rows; same pattern as V171). Re-running is a no-op.
-- Dry-run in BEGIN/ROLLBACK on the live database before rollout.
SELECT set_config('app.actor_fingerprint', 'migration:V233_asa_terminal_pager_0_retired', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

UPDATE gate_registry SET
    sign_off_state = 'BLOCKED',
    max_frequency = 'never -- removed per PO 2026-11-15',
    source_document_pointer = 'docs/design/CISCO_ASA_CONTRACT.md (withdrawn 2026-11-15: Cisco CPU warning; the shell answers "<--- More --->"); was Cisco ASA command reference (terminal pager); Backbox ASA 9.4+ trail 2026-09-22'
WHERE gate_id = 'asa_terminal_pager_0'
  AND vendor = 'cisco_asa'
  AND sign_off_state <> 'BLOCKED';
