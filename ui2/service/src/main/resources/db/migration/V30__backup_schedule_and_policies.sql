-- V30 -- Backup schedule policies, retention configuration, and semantic deviation tracking
-- Supplements V17 and V18 for the 2027 BackBox replacement engine.

CREATE TABLE IF NOT EXISTS backup_policy (
    policy_id TEXT PRIMARY KEY,
    daily_backup_cron TEXT NOT NULL DEFAULT '0 2 * * *',
    weekly_snapshot_cron TEXT NOT NULL DEFAULT '0 3 * * 0',
    backup_retention_days INT NOT NULL DEFAULT 30,
    snapshot_retention_depth INT NOT NULL DEFAULT 2,
    major_alert_enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO backup_policy (policy_id, daily_backup_cron, weekly_snapshot_cron, backup_retention_days, snapshot_retention_depth, major_alert_enabled)
VALUES ('default', '0 2 * * *', '0 3 * * 0', 30, 2, true)
ON CONFLICT (policy_id) DO NOTHING;

ALTER TABLE backup_artefact 
    ADD COLUMN IF NOT EXISTS backup_type TEXT DEFAULT 'standard' CHECK (backup_type IN ('standard', 'snapshot'));

CREATE TABLE IF NOT EXISTS backup_deviation_record (
    deviation_id TEXT PRIMARY KEY,
    artefact_id TEXT NOT NULL REFERENCES backup_artefact(artefact_id),
    previous_artefact_id TEXT REFERENCES backup_artefact(artefact_id),
    device_id TEXT NOT NULL REFERENCES devices(device_id),
    deviation_class TEXT NOT NULL CHECK (deviation_class IN ('major', 'minor', 'unchanged', 'first_run')),
    summary TEXT NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_backup_deviation_device_id ON backup_deviation_record(device_id, detected_at);
CREATE INDEX IF NOT EXISTS idx_backup_deviation_class ON backup_deviation_record(deviation_class);
