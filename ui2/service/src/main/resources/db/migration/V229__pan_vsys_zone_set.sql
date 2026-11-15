-- V229 -- pan_vsys_zone_set: the zone names of each virtual system of a Palo Alto firewall
-- (docs/design/PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md, DECIDED by the Product Owner on 2026-11-15, "build it as recommended").
--
-- One row per (enrolled firewall, virtual system): the names of every zone entry that virtual system holds in the
-- firewall's effective-running configuration (template-pushed ones included, a zone with no interface member included),
-- and the time of that configuration. The inventory run already derives a PAN-OS 12 firewall's interfaces from the stored
-- effective-running document (PO record 2026-10-24); the parse scope now also keeps the zone names. No device command,
-- no API call, no new read of the document.
--
-- The policy analysis joins the rows to a Palo Alto policy's targets to decide a rule whose zone is a lone "any"
-- against an earlier list of zone names (PolicyNormalization.zoneHolds): TRUE only when every target's zone set lies in
-- the list, FALSE only when some target has a zone missing from it, UNKNOWN when a target has no set here or its read is
-- older than 24 hours. A firewall neXus does not inventory has no row, so it stays UNKNOWN.
--
--   device_id    enrolled device (devices.device_id); rows are removed with the device (ON DELETE CASCADE, audited).
--   vsys         the vsys entry name as the configuration and Panorama give it ('vsys1'); not the interface context.
--   zone_names   sorted, distinct zone names; may be empty (a read virtual system with no zone is a fact, not a gap).
--                Never NULL, never an empty or NULL element.
--   read_at      when the stored effective-running document was collected -- the age of the evidence, not of the
--                inventory run that parsed it.
--
-- Written by the inventory worker as a difference (unchanged rows are not touched, so a repeated run with the same
-- configuration adds no audit row; a newer document of the same names only moves read_at).
--
-- Audit classification (UI2_0_B1_02A §4, V5/V153/V185 convention): vsys and zone_names describe the network's segmentation
-- and are tier-2 redacted; set_id, device_id and read_at are audited in full (AuditPresentationAllowlist).
SELECT set_config('app.actor_fingerprint', 'migration:V229_pan_vsys_zone_set', true);
SELECT set_config('app.action_id', 'audit_redaction_policy_seed_by_migration', true);

CREATE TABLE IF NOT EXISTS pan_vsys_zone_set (
    set_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id   TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    vsys        TEXT        NOT NULL CHECK (length(vsys) BETWEEN 1 AND 128),
    zone_names  TEXT[]      NOT NULL CHECK (
                    cardinality(zone_names) <= 1024
                    AND array_position(zone_names, NULL) IS NULL
                    AND array_position(zone_names, '') IS NULL
                    AND octet_length(array_to_string(zone_names, ',')) <= 131072),
    read_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT pan_vsys_zone_set_key UNIQUE (device_id, vsys)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON pan_vsys_zone_set TO ui2_app;

DROP TRIGGER IF EXISTS trg_audit_pan_vsys_zone_set ON pan_vsys_zone_set;
CREATE TRIGGER trg_audit_pan_vsys_zone_set
    AFTER INSERT OR UPDATE OR DELETE ON pan_vsys_zone_set
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('set_id');

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('pan_vsys_zone_set', 'vsys', 2, 'device virtual-system reference'),
    ('pan_vsys_zone_set', 'zone_names', 2, 'zone names of a firewall: the network segmentation')
ON CONFLICT (table_name, column_name) DO NOTHING;
