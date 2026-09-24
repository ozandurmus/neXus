-- V65 -- device role "appliance" (V64 follow-up, 2026-09-24): Infoblox Grid Manager and Radware DefensePro are
-- enrolled as appliances, neither a gateway nor a management server. V64 shipped the HTTPS backup path but left the
-- V21 role check at two values, so enrolling either vendor was refused (role_invalid).
SELECT set_config('app.actor_fingerprint', 'migration:V65_device_role_appliance', true);
SELECT set_config('app.action_id', 'device_role_appliance_by_migration', true);

ALTER TABLE devices DROP CONSTRAINT chk_devices_role;
ALTER TABLE devices ADD CONSTRAINT chk_devices_role CHECK (role IN ('gateway', 'management_server', 'appliance'));
