-- V228 -- Zone matrix, delivery step 3: audited witness view in zone_matrix_access_log
-- (docs/design/ZONE_MATRIX_CONTRACT.md sections 3.4, 5.9, 8.2 and 10.1, ZM-33, FROZEN 2026-11-13).
--
-- Step 3 evaluates stored firewall policies against a published zone-matrix version in memory without durable run/finding
-- tables (those belong to delivery step 4). Showing witness address ranges on an evaluation result requires
-- zone_matrix_zones_read and writes an access-log row captured into audit_log under action_id 'zone_matrix_zones_read',
-- never persisting witness addresses themselves.

ALTER TABLE zone_matrix_access_log DROP CONSTRAINT zone_matrix_access_log_kind_check;
ALTER TABLE zone_matrix_access_log ADD CONSTRAINT zone_matrix_access_log_kind_check
    CHECK (kind IN ('BLOCKS_VIEW', 'ADDRESS_LOOKUP', 'WITNESS_VIEW'));
