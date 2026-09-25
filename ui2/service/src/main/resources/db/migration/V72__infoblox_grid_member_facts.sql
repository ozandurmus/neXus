-- V72 -- Infoblox grid member facts (PO 2026-09-25, "make the member list meaningful"): what the Grid Manager reports
-- per member, one row per member per inventory run -- role in the grid, hardware, HA, node health and services.
-- Measured on the production grid (WAPI 2.13.7): platform VNIOS, hwtype IB-V1516/V2326/V5005, hypervisor VMware,
-- ha_status NOT_CONFIGURED, member services DNS/DHCP/NTP/REPORTING/ATP/ANALYTICS/... with WORKING/INACTIVE/WARNING/UNKNOWN,
-- node services NODE_STATUS/REPLICATION/DISK_USAGE/MEMORY/CPU_USAGE/DB_OBJECT whose descriptions carry a percentage.
-- Free-text descriptions are not stored (they can carry addresses); services are service=status pairs.
CREATE TABLE grid_member (
    member_id        TEXT        PRIMARY KEY,
    run_id           TEXT        NOT NULL REFERENCES device_inventory_run(run_id),
    host_name        TEXT        NOT NULL,
    vip_address      TEXT,
    platform         TEXT,
    hardware_type    TEXT,
    hypervisor       TEXT,
    grid_master      BOOLEAN     NOT NULL DEFAULT FALSE,
    master_candidate BOOLEAN     NOT NULL DEFAULT FALSE,
    ha_enabled       BOOLEAN     NOT NULL DEFAULT FALSE,
    ha_status        TEXT,
    node_status      TEXT,
    replication      TEXT,
    disk_percent     INTEGER     CHECK (disk_percent IS NULL OR (disk_percent BETWEEN 0 AND 100)),
    memory_percent   INTEGER     CHECK (memory_percent IS NULL OR (memory_percent BETWEEN 0 AND 100)),
    cpu_percent      INTEGER     CHECK (cpu_percent IS NULL OR (cpu_percent BETWEEN 0 AND 100)),
    db_percent       INTEGER     CHECK (db_percent IS NULL OR (db_percent BETWEEN 0 AND 100)),
    services         TEXT        NOT NULL DEFAULT ''
);
CREATE INDEX idx_grid_member_run_id ON grid_member(run_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON grid_member TO ui2_app;
