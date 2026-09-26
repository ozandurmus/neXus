-- V93 -- V92 created infoblox_grid_summary without the application role's grant; the first Infoblox inventory after
-- deploy failed with "permission denied for table infoblox_grid_summary" (2026-09-26). Same grant as grid_member (V72).
SELECT set_config('app.actor_fingerprint', 'migration:V93_infoblox_grid_summary_grant', true);
SELECT set_config('app.action_id', 'infoblox_grid_summary_grant_by_migration', true);
GRANT SELECT, INSERT, UPDATE, DELETE ON infoblox_grid_summary TO ui2_app;
