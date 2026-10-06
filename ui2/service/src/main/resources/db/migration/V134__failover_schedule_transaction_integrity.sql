-- Keep V1 entries intact; every subsequent append must use framed V2 encoding.
ALTER TABLE failover_schedule_ledger ADD COLUMN format_version INT NOT NULL DEFAULT 1;
ALTER TABLE failover_schedule_ledger ALTER COLUMN format_version SET DEFAULT 2;
ALTER TABLE failover_schedule_ledger ADD CONSTRAINT failover_ledger_format CHECK (format_version IN (1, 2));

-- PostgreSQL's built-in range GiST class needs no extension or new dependency.
ALTER TABLE failover_schedules ADD CONSTRAINT failover_booking_no_overlap
    EXCLUDE USING gist (tstzrange(window_start, execution_deadline, '[)') WITH &&)
    WHERE (status IN ('SCHEDULED', 'CLAIMED_VERIFYING', 'DISPATCHING'));
-- An uncertain attempt retains the fleet fence; it cannot silently free another dispatch.
CREATE UNIQUE INDEX failover_single_fleet_attempt ON failover_schedules ((1))
    WHERE status IN ('CLAIMED_VERIFYING', 'DISPATCHING', 'OUTCOME_UNKNOWN');
ALTER TABLE failover_schedules ADD CONSTRAINT failover_grant_schedule UNIQUE (grant_id, schedule_id);
ALTER TABLE failover_grant_consumption ADD CONSTRAINT failover_consumption_owner
    FOREIGN KEY (grant_id, schedule_id) REFERENCES failover_schedules (grant_id, schedule_id) NOT VALID;

CREATE FUNCTION failover_history_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'FAILOVER_HISTORY_IMMUTABLE';
END;
$$;
CREATE TRIGGER failover_ledger_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON failover_schedule_ledger
    FOR EACH STATEMENT EXECUTE FUNCTION failover_history_immutable();
CREATE TRIGGER failover_grants_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON failover_grant_consumption
    FOR EACH STATEMENT EXECUTE FUNCTION failover_history_immutable();

CREATE FUNCTION failover_ledger_append() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    tip_index BIGINT;
    tip_hash TEXT;
BEGIN
    -- Same lock as the application: even a direct app-role insert cannot fork the chain.
    PERFORM pg_advisory_xact_lock(13223402);
    SELECT entry_index, entry_hash INTO tip_index, tip_hash
        FROM failover_schedule_ledger ORDER BY entry_index DESC LIMIT 1;
    IF NEW.format_version <> 2 OR NEW.entry_index <> COALESCE(tip_index + 1, 0)
        OR NEW.prev_hash <> COALESCE(tip_hash, repeat('0', 64)) THEN
        RAISE EXCEPTION 'FAILOVER_LEDGER_APPEND_CONFLICT';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER failover_ledger_append_guard BEFORE INSERT ON failover_schedule_ledger
    FOR EACH ROW EXECUTE FUNCTION failover_ledger_append();
