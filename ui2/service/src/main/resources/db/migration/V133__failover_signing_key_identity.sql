-- Legacy rows do not establish which key was used. Preserve them without inventing custody.
ALTER TABLE failover_schedules ADD COLUMN key_id TEXT;
ALTER TABLE failover_schedules ADD COLUMN algorithm_version TEXT;
UPDATE failover_schedules
SET status = 'ABORTED_KEY_UNAVAILABLE', abort_reason_code = 'KEY_UNAVAILABLE',
    abort_reason = 'Stored authorization has no verifiable key identity; fresh booking required', version = version + 1
WHERE status = 'SCHEDULED';
ALTER TABLE failover_schedules ADD CONSTRAINT failover_scheduled_key_identity
    CHECK (status <> 'SCHEDULED' OR
        (key_id IS NOT NULL AND length(key_id) > 0 AND algorithm_version IS NOT NULL AND length(algorithm_version) > 0));
