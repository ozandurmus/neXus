-- A member's HA report is a claim until another enrolled member corroborates it.
SELECT set_config('app.actor_fingerprint', 'migration:V96_ha_pair_claim', true);
SELECT set_config('app.action_id', 'ha_pair_claim_by_migration', true);
ALTER TABLE devices ADD COLUMN ha_pair_claim text;
UPDATE devices SET ha_pair_claim = cluster_member_ref
WHERE vendor_hint = 'cisco_asa' AND cluster_member_ref LIKE 'asa-failover|%';
-- V1 grants table-wide SELECT/INSERT/UPDATE/DELETE to ui2_app and trg_audit_devices audits the full row.
