-- One closed FortiManager diagnostic parameter and safe result on the existing audited job.
SELECT set_config('app.actor_fingerprint', 'migration:V90_diagnostic_job_projection', true);
SELECT set_config('app.action_id', 'diagnostic_job_schema_by_migration', true);

ALTER TABLE jobs ADD COLUMN diagnostic_port TEXT;
ALTER TABLE jobs ADD COLUMN diagnostic_gate_revision INTEGER;
ALTER TABLE jobs ADD COLUMN diagnostic_status_token TEXT;
ALTER TABLE jobs ADD COLUMN diagnostic_line_count INTEGER;
ALTER TABLE jobs ADD COLUMN diagnostic_shape_id TEXT;

ALTER TABLE jobs ADD CONSTRAINT chk_jobs_diagnostic_port
    CHECK ((capability_id = 'fmg_interface_detail' AND diagnostic_port IS NOT NULL
            AND diagnostic_port ~ '^[A-Za-z0-9_.-]{1,31}$' AND diagnostic_gate_revision = 90)
        OR (capability_id <> 'fmg_interface_detail' AND diagnostic_port IS NULL AND diagnostic_gate_revision IS NULL));
ALTER TABLE jobs ADD CONSTRAINT chk_jobs_diagnostic_safe_result
    CHECK ((diagnostic_status_token IS NULL OR diagnostic_status_token IN ('UP', 'DOWN', 'OTHER', 'ABSENT'))
        AND (diagnostic_line_count IS NULL OR diagnostic_line_count BETWEEN 0 AND 256)
        AND (diagnostic_shape_id IS NULL OR diagnostic_shape_id IN ('FMG_DETAIL_STATUS', 'FMG_DETAIL_NO_STATUS', 'FMG_DETAIL_OTHER')));
CREATE INDEX idx_jobs_fmg_diagnostic_rate ON jobs(target_device_id, submitted_at DESC)
    WHERE capability_id = 'fmg_interface_detail';

UPDATE gate_registry SET max_frequency = 'one command per endpoint per minute',
    session_reuse_rule = 'one trusted SSH session for this diagnostic job, no second connection'
WHERE gate_id = 'fmg_ssh_fmnetwork_interface_detail';
