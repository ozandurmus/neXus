-- V29 -- Persist discovered virtual systems in device_inventory_run

ALTER TABLE device_inventory_run ADD COLUMN IF NOT EXISTS virtual_systems TEXT;
