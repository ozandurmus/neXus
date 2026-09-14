-- V21 -- add device role (14I MS-1)
SELECT set_config('app.actor_fingerprint', 'migration:V21_device_role', true);
SELECT set_config('app.action_id', 'device_role_addition_by_migration', true);

ALTER TABLE devices ADD COLUMN role TEXT;
UPDATE devices SET role = 'gateway';
ALTER TABLE devices ALTER COLUMN role SET NOT NULL;
ALTER TABLE devices ADD CONSTRAINT chk_devices_role CHECK (role IN ('gateway', 'management_server'));
