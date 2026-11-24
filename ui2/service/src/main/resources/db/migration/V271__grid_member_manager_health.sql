-- V271 -- Managed-device health as a Management Center reports it (PO 2026-11-24, docs/design/MC_DEVICE_HEALTH_READ_PROPOSAL_2026_11_24.md).
-- Five nullable columns on grid_member, written only for a Symantec Management Center's managed devices when the worker's
-- UI2_MC_DEVICE_HEALTH_READ switch is on and the gate for GET /api/devices/status is approved; every other row (Infoblox,
-- FortiManager, a run recorded before this migration) leaves them NULL, which the screen reads as UNKNOWN, never OK.
-- Schema only: additive, no backfill, no gate_registry row. health_messages holds at most three short, pre-masked texts
-- (newline-separated, or a closed reason token such as GATE_NOT_APPROVED); no raw vendor JSON is ever stored.
ALTER TABLE grid_member
    ADD COLUMN health_state      TEXT CHECK (health_state IS NULL OR health_state IN ('ERROR', 'WARNING', 'OK', 'INACTIVE', 'UNKNOWN')),
    ADD COLUMN health_errors     INTEGER CHECK (health_errors IS NULL OR health_errors >= 0),
    ADD COLUMN health_warnings   INTEGER CHECK (health_warnings IS NULL OR health_warnings >= 0),
    ADD COLUMN health_checked_at TEXT CHECK (health_checked_at IS NULL OR length(health_checked_at) <= 40),
    ADD COLUMN health_messages   TEXT CHECK (health_messages IS NULL OR length(health_messages) <= 1024);
