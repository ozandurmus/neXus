-- V13 -- device inventory collection storage, per
-- docs/design/PO_DECISION_RECORD_2026_09_14C_INVENTORY_AND_CONFIGURATION_COLLECTION_DESIGN.md
-- (FROZEN) D-4, and the READ CONTRACT shared by NXS-LOCAL-0159 and
-- NXS-LOCAL-0160.
--
-- Additive to V1-V12 (append-only; none of them is edited). No raw command
-- output is stored anywhere in this file (AGENTS.md raw-evidence law): the
-- worker's inventory parsers keep only the columns named below.
--
-- No "context" table: a run's contexts (physical, and each VSID/vsys) are
-- represented by the "context" column directly on device_interface and
-- device_route, not by a linking row of their own -- persistence.device.
-- inventory.InventoryContext (job-engine/worker side) is an in-memory
-- grouping shape over these two columns, not a fifth table.

-- ---------------------------------------------------------------------
-- 1. device_inventory_run -- one row per completed inventory_collect job.
-- ---------------------------------------------------------------------

CREATE TABLE device_inventory_run (
    run_id          TEXT        PRIMARY KEY,
    device_id       TEXT        NOT NULL REFERENCES devices(device_id),
    job_id          TEXT        NOT NULL REFERENCES jobs(job_id),
    collected_at    TIMESTAMPTZ NOT NULL,
    context_count   INTEGER     NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_inventory_run_device_collected
    ON device_inventory_run(device_id, collected_at);

-- ---------------------------------------------------------------------
-- 2. device_interface -- one row per interface, per context, per run.
-- "state" is the fixed up|down|unknown vocabulary read from the vendor's
-- own flag list, never inferred by a substring match (14C section 4).
-- ---------------------------------------------------------------------

CREATE TABLE device_interface (
    interface_id    TEXT        PRIMARY KEY,
    run_id          TEXT        NOT NULL REFERENCES device_inventory_run(run_id),
    context         TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    parent          TEXT,
    kind            TEXT        NOT NULL
        CHECK (kind IN ('physical', 'vlan', 'subinterface', 'loopback', 'bond', 'tunnel', 'other')),
    state           TEXT        NOT NULL
        CHECK (state IN ('up', 'down', 'unknown'))
);

CREATE INDEX idx_device_interface_run_id ON device_interface(run_id);

-- ---------------------------------------------------------------------
-- 3. device_interface_address -- one row per address on one interface.
-- "role" never mixes a member address (Check Point "ip addr", Palo Alto
-- interface read) with a cluster virtual address (Check Point
-- "cphaprob -a -m if") -- the two reads are kept separate at parse time
-- and this column records which one produced the row (14C section 3).
-- ---------------------------------------------------------------------

CREATE TABLE device_interface_address (
    address_id      TEXT        PRIMARY KEY,
    interface_id    TEXT        NOT NULL REFERENCES device_interface(interface_id),
    address         TEXT        NOT NULL,
    family          TEXT        NOT NULL
        CHECK (family IN ('ipv4', 'ipv6')),
    role            TEXT        NOT NULL
        CHECK (role IN ('member', 'cluster_virtual'))
);

CREATE INDEX idx_device_interface_address_interface_id ON device_interface_address(interface_id);

-- ---------------------------------------------------------------------
-- 4. device_route -- one row per route, per context, per run. "protocol"
-- preserves the vendor's own token (14C section 4: "dynamic routes
-- reported as static" is a named earlier-product shortcut, not carried).
-- ---------------------------------------------------------------------

CREATE TABLE device_route (
    route_id        TEXT        PRIMARY KEY,
    run_id          TEXT        NOT NULL REFERENCES device_inventory_run(run_id),
    context         TEXT        NOT NULL,
    destination     TEXT        NOT NULL,
    next_hop        TEXT,
    interface       TEXT,
    protocol        TEXT        NOT NULL
        CHECK (protocol IN ('static', 'connected', 'default', 'ospf', 'bgp', 'rip', 'host', 'kernel', 'unknown')),
    route_table     TEXT
);

CREATE INDEX idx_device_route_run_id ON device_route(run_id);

-- ---------------------------------------------------------------------
-- 5. Audit triggers (C1 section 3.5) -- every mutation-bearing table this
-- file adds is audited, exactly as job_step_attempt/gate_registry are in
-- V4.
-- ---------------------------------------------------------------------

CREATE TRIGGER trg_audit_device_inventory_run
    AFTER INSERT OR UPDATE OR DELETE ON device_inventory_run
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('run_id');

CREATE TRIGGER trg_audit_device_interface
    AFTER INSERT OR UPDATE OR DELETE ON device_interface
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('interface_id');

CREATE TRIGGER trg_audit_device_interface_address
    AFTER INSERT OR UPDATE OR DELETE ON device_interface_address
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('address_id');

CREATE TRIGGER trg_audit_device_route
    AFTER INSERT OR UPDATE OR DELETE ON device_route
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('route_id');

-- ---------------------------------------------------------------------
-- 6. Grants (contract section 5 / C1 section 5 pattern).
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE
  ON device_inventory_run, device_interface, device_interface_address, device_route
  TO ui2_app;
