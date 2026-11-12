-- V209 -- FortiGate HA / Cisco ASA failover, Phase 0 (PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md,
-- RATIFIED). No device command and no gate row: every value comes from reads the inventory already makes
-- (Cisco ASA "show version" and "show failover | include This host"; FortiGate "get system status" and
-- "get system ha status").
--   1. device_inventory_ha accepts the two new HA-fact sources.
--   2. ha_pair_identity: a failover pair's two unit serials, recorded the first time both enrolled members of the pair
--      report two different serials, then only compared (MATCH / MISMATCH / MISSING / AMBIGUOUS), never re-bound.
--      For a Cisco ASA pair the two device rows are the pair's two management address slots, which follow the
--      active/standby role, so a unit is identified by its serial within the pair, never by its address.
-- No audit trigger: machine-written rows, never entered by a person (the rule of V200 and V205).
-- Dry-run in BEGIN/ROLLBACK on the live database before rollout.

SELECT set_config('app.actor_fingerprint', 'migration:V209_failover_phase0_identity', true);
SELECT set_config('app.action_id', 'failover_phase0_identity_by_migration', true);

ALTER TABLE device_inventory_ha DROP CONSTRAINT device_inventory_ha_source_check;
ALTER TABLE device_inventory_ha ADD CONSTRAINT device_inventory_ha_source_check
    CHECK (source IN ('cp_cphaprob_stat', 'cp_vsls_table', 'pan_high_availability_state',
                      'asa_show_failover', 'fgt_get_system_ha_status'));

CREATE TABLE ha_pair_identity (
    cluster_member_ref TEXT        PRIMARY KEY,
    vendor             TEXT        NOT NULL CHECK (vendor IN ('cisco_asa', 'fortinet')),
    -- The two serials, stored in a fixed order (plain text order) only so the pair has one representation; equality
    -- is exact, never normalised (AGENTS.md identity law).
    unit_serial_low    TEXT        NOT NULL CHECK (length(btrim(unit_serial_low)) BETWEEN 1 AND 64),
    unit_serial_high   TEXT        NOT NULL CHECK (length(btrim(unit_serial_high)) BETWEEN 1 AND 64),
    recorded_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    recorded_by        TEXT        NOT NULL,
    last_state         TEXT        NOT NULL CHECK (last_state IN ('MATCH', 'MISMATCH', 'MISSING', 'AMBIGUOUS')),
    last_checked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (unit_serial_low COLLATE "C" < unit_serial_high COLLATE "C")
);

GRANT SELECT, INSERT, UPDATE ON ha_pair_identity TO ui2_app;
