-- V68 -- Radware Cyber Controller logout path corrected (first live run, 2026-09-24): V67 carried
-- /mgmt/system/config/itemlist/systemuser/logout, which answered HTTP 405 on Cyber Controller 10.13; the REST reference's
-- "Server Logout" is POST /mgmt/system/user/logout. A session left open expires on its own; this closes it.
SELECT set_config('app.actor_fingerprint', 'migration:V68_radware_cc_logout_path', true);
SELECT set_config('app.action_id', 'radware_cc_logout_path_by_migration', true);

UPDATE gate_registry
   SET canonical_command_key = 'POST /mgmt/system/user/logout',
       source_document_pointer = 'Cyber Controller REST 10.3.0 Server Logout (V68: the V67 path answered 405 on 10.13)'
 WHERE gate_id = 'radware_cc_logout';
