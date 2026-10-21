-- V171 -- PO decision 2026-10-21: neXus stops sending show system info to Palo Alto firewalls entirely,
-- and an inventory run can record reads switched off (scope update, same day)
-- (docs/design/PO_DECISION_RECORD_2026_10_21_PAN_SHOW_SYSTEM_INFO_RETIRED.md, RATIFIED).
--
-- The firewall gate row is withdrawn with the registry's own state for it: BLOCKED resolves UNKNOWN
-- ("gate exists, BLOCKED pending") in GateResolver for every executor, so nothing that resolves its command
-- through the gate can send it. The row is kept, not deleted, as the record of what was sent before (V36's
-- append-only discipline for gate rows). Scoped by gate_id and platform_role_scope: the Panorama row
-- pan_panorama_show_system_info (V83, platform_role_scope panorama, same literal) is out of scope and untouched.
-- Re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V171_pan_firewall_show_system_info_retired', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

UPDATE gate_registry SET
    sign_off_state = 'BLOCKED',
    max_frequency = 'never -- retired by the PO 2026-10-21',
    source_document_pointer = 'docs/design/PO_DECISION_RECORD_2026_10_21_PAN_SHOW_SYSTEM_INFO_RETIRED.md (withdrawn; was PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md item 7)'
WHERE gate_id = 'pan_inventory_show_system_info'
  AND platform_role_scope = 'pan_firewall'
  AND sign_off_state <> 'BLOCKED';

-- PO scope update 2026-10-21 (PAN-OS 12+ defect): three more firewall reads may be switched off by a worker setting
-- (PanFirewallCommandSwitches; default on). An inventory run records which reads it deliberately did not send, so
-- what they provide reads UNKNOWN ("retired by PO") instead of an empty list. Comma-separated read-kind tokens;
-- NULL on every older row and whenever every read was sent.
ALTER TABLE device_inventory_run ADD COLUMN IF NOT EXISTS not_collected TEXT;
