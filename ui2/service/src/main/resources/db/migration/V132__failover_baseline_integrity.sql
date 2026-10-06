-- V2 binds canonical microsecond timestamps and required baseline dimensions.
-- Existing authorizations cannot be upgraded or re-signed without a fresh booking.
ALTER TABLE failover_schedules ADD COLUMN baseline_format_version INT NOT NULL DEFAULT 1;
UPDATE failover_schedules
SET status = 'ABORTED_TAMPERED', abort_reason_code = 'BASELINE_FORMAT_UNSUPPORTED',
    abort_reason = 'Stored baseline format requires a fresh authorized booking', version = version + 1
WHERE status = 'SCHEDULED';
-- Never relabel a potentially crossed boundary as a safe pre-mutation abort.
ALTER TABLE failover_schedules ALTER COLUMN baseline_format_version SET DEFAULT 2;
ALTER TABLE failover_schedules ADD CONSTRAINT failover_baseline_format_supported
    CHECK (baseline_format_version IN (1, 2));
GRANT SELECT, INSERT, UPDATE, DELETE ON failover_schedules TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON failover_schedule_ledger TO ui2_app;
GRANT USAGE, SELECT ON SEQUENCE failover_schedule_ledger_id_seq TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON failover_grant_consumption TO ui2_app;
