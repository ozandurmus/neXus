-- V17 -- HA role and VLAN id, the two facts the inventory worker already
-- parses and then discards for want of a column (NXS-LOCAL-0167, closing a
-- debt NXS-LOCAL-0165's own SESSION_CLOSE wrote down).
--
-- Additive to V1-V16 (append-only; none of them is edited, no existing
-- device_inventory_run/device_interface/device_route column is touched).
-- No raw command output is stored anywhere in this file (AGENTS.md
-- raw-evidence law).
--
-- docs/design/PO_DECISION_RECORD_2026_09_14C (FROZEN) D-3/D-4: the
-- inventory read set already names HA role; D-4's tables are the place a
-- column is added additively. docs/design/PO_DECISION_RECORD_2026_09_14D
-- (FROZEN) PR-4: the VSLS per-virtual-system role table
-- CheckPointHaStateParser already produces off the physical cphaprob stat
-- read.

-- ---------------------------------------------------------------------
-- 1. device_inventory_ha -- one row per (run, context) HA fact. Run-scoped
-- and context-scoped, never on the device row directly (14C D-3/D-4: a
-- device row is not per-context, and writing HA role there would lose
-- per-context truth across a run's own physical/VSID/vsys contexts).
--
-- "source" names which read produced the row: the Check Point physical
-- context's own `cphaprob stat` (14D CF-3), the per-VSID role the same
-- read's VSLS table yields (14D PR-4, no per-VSID `cphaprob stat` re-read),
-- or Palo Alto's `show high-availability state` (14E PF-1).
-- ---------------------------------------------------------------------

CREATE TABLE device_inventory_ha (
    ha_id           TEXT        PRIMARY KEY,
    run_id          TEXT        NOT NULL REFERENCES device_inventory_run(run_id),
    context         TEXT        NOT NULL,
    role            TEXT        NOT NULL,
    cluster_mode    TEXT,
    source          TEXT        NOT NULL
        CHECK (source IN ('cp_cphaprob_stat', 'cp_vsls_table', 'pan_high_availability_state'))
);

CREATE INDEX idx_device_inventory_ha_run_id ON device_inventory_ha(run_id);

-- ---------------------------------------------------------------------
-- 2. device_interface.vlan_id -- the VLAN id CheckPointIpAddrParser already
-- reads from a "vlan protocol 802.1Q id <n>" detail line (14D PR-2), and
-- PaloAltoInterfaceParser already reads from an ifnet entry's own "tag"
-- leaf. Nullable: most interfaces (physical, loopback, bond, tunnel) carry
-- no VLAN id at all -- this is not "not yet collected".
-- ---------------------------------------------------------------------

ALTER TABLE device_interface ADD COLUMN vlan_id INTEGER;

-- ---------------------------------------------------------------------
-- 3. Audit trigger (C1 section 3.5) -- device_inventory_ha is the only
-- mutation-bearing table this file adds; the ALTER TABLE above writes no
-- row and needs none.
-- ---------------------------------------------------------------------

CREATE TRIGGER trg_audit_device_inventory_ha
    AFTER INSERT OR UPDATE OR DELETE ON device_inventory_ha
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('ha_id');

-- ---------------------------------------------------------------------
-- 4. Grants (contract section 5 / C1 section 5 pattern).
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE ON device_inventory_ha TO ui2_app;
