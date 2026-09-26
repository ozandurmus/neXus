-- V86 -- the configuration plane's vendor check (PO 2026-09-26): the FortiGate configuration read was refused by the
-- V16 CHECK (check_point, palo_alto only). The list becomes V64's vendor vocabulary so the next vendors (Cisco ASA,
-- Pulse Secure) do not fail the same way.
SELECT set_config('app.actor_fingerprint', 'migration:V86_configuration_vendor_check', true);
SELECT set_config('app.action_id', 'configuration_vendor_check_by_migration', true);
ALTER TABLE device_configuration_run DROP CONSTRAINT IF EXISTS device_configuration_run_vendor_check;
ALTER TABLE device_configuration_run ADD CONSTRAINT device_configuration_run_vendor_check
    CHECK (vendor IN ('check_point', 'palo_alto', 'infoblox', 'radware', 'fortinet', 'cisco_asa', 'pulse_secure', 'bluecoat'));
ALTER TABLE configuration_artefact DROP CONSTRAINT IF EXISTS configuration_artefact_vendor_check;
ALTER TABLE configuration_artefact ADD CONSTRAINT configuration_artefact_vendor_check
    CHECK (vendor IN ('check_point', 'palo_alto', 'infoblox', 'radware', 'fortinet', 'cisco_asa', 'pulse_secure', 'bluecoat'));
