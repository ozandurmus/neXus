-- V180 -- backup size anomaly and the "last good" pin (PO decision 2026-10-30,
-- docs/design/BACKUP_SIZE_ANOMALY_RETENTION.md).
--
-- A gateway's nightly backup jumped from about 1 GB to about 29 GB (local logs swept into the backup) and the 7-day
-- rule kept seven identical bloated copies while pruning every good one. From now on the worker records a size
-- anomaly at ingest (growth to >= 5x, or shrink to <= 20%, of the median of the device's last 3 non-anomalous live
-- standard backups, and a change of at least 1 GiB) as a backup_deviation_record row of class size_anomaly:
--
--   * previous_artefact_id is the device's last good backup; while the row is unresolved retention never prunes it;
--   * only the newest unresolved anomalous copy per device is kept, older anomalous copies are pruned;
--   * a security_admin resolves it ("accept new size", with a reason); nothing resolves it automatically.
--
-- The table (V30) was never written until now and had no ui2_app grant and no audit trigger (it was listed as derived
-- data). The resolution is an operator act, so the table gains trg_audit_backup_deviation_record and the free-text
-- reason is redacted from the audit image. Sizes and a ratio only: no hostname, address or serial is ever written here.
SELECT set_config('app.actor_fingerprint', 'migration:V180_backup_size_anomaly_retention', true);
SELECT set_config('app.action_id', 'backup_size_anomaly_schema_by_migration', true);

ALTER TABLE backup_deviation_record
    DROP CONSTRAINT backup_deviation_record_deviation_class_check;
ALTER TABLE backup_deviation_record
    ADD CONSTRAINT backup_deviation_record_deviation_class_check
        CHECK (deviation_class IN ('major', 'minor', 'unchanged', 'first_run', 'size_anomaly'));

ALTER TABLE backup_deviation_record
    ADD COLUMN resolved_at       TIMESTAMPTZ,
    ADD COLUMN resolved_by       TEXT,
    ADD COLUMN resolution_reason TEXT;

-- A resolution is all three facts or none; a size anomaly always names the last good backup it pins.
ALTER TABLE backup_deviation_record
    ADD CONSTRAINT backup_deviation_record_resolution_complete CHECK (
        (resolved_at IS NULL AND resolved_by IS NULL AND resolution_reason IS NULL)
        OR (resolved_at IS NOT NULL AND resolved_by IS NOT NULL AND length(btrim(resolution_reason)) > 0)),
    ADD CONSTRAINT backup_deviation_record_size_anomaly_pins CHECK (
        deviation_class <> 'size_anomaly' OR previous_artefact_id IS NOT NULL);

-- Retention asks, per candidate, "is this a pinned last good or an unresolved anomalous copy?".
CREATE INDEX idx_backup_deviation_open_size_anomaly_artefact
    ON backup_deviation_record(artefact_id) WHERE deviation_class = 'size_anomaly' AND resolved_at IS NULL;
CREATE INDEX idx_backup_deviation_open_size_anomaly_last_good
    ON backup_deviation_record(previous_artefact_id) WHERE deviation_class = 'size_anomaly' AND resolved_at IS NULL;
-- One record per anomalous artefact.
CREATE UNIQUE INDEX uq_backup_deviation_size_anomaly_artefact
    ON backup_deviation_record(artefact_id) WHERE deviation_class = 'size_anomaly';

CREATE TRIGGER trg_audit_backup_deviation_record
    AFTER INSERT OR UPDATE OR DELETE ON backup_deviation_record
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('deviation_id');

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('backup_deviation_record', 'resolution_reason', 2, 'operator free text (accept new size reason)');

-- The worker inserts at ingest; the service reads and resolves. No DELETE: a record outlives its artefacts.
GRANT SELECT, INSERT, UPDATE ON backup_deviation_record TO ui2_app;
