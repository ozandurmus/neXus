-- V44 -- backup policy and per-device baseline (backlog backup_screen_backbox_model,
-- Product Owner P1 2026-09-22: "Backup screen on the Backbox model").
--
-- Until now GET /api/v2/backups/policies answered constants and PUT answered
-- 405: no schedule ran, no retention ran. backup_policy is the one row the
-- scheduler and the retention pruner read; every change is an audited UPDATE.
-- backup_baseline names, per device, the artefact an operator declared the
-- reference point; Compare offers "vs baseline" next to "vs previous".
SELECT set_config('app.actor_fingerprint', 'migration:V44_backup_policy_and_baseline', true);
SELECT set_config('app.action_id', 'backup_policy_seed_by_migration', true);

CREATE TABLE backup_policy (
    policy_id                TEXT        PRIMARY KEY,
    schedule_enabled         BOOLEAN     NOT NULL DEFAULT false,
    daily_backup_cron        TEXT        NOT NULL,
    backup_retention_days    INTEGER     NOT NULL CHECK (backup_retention_days BETWEEN 1 AND 3650),
    snapshot_retention_depth INTEGER     NOT NULL CHECK (snapshot_retention_depth BETWEEN 1 AND 100),
    last_scheduled_run_at    TIMESTAMPTZ,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_audit_backup_policy
    AFTER INSERT OR UPDATE OR DELETE ON backup_policy
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('policy_id');

-- The seed keeps the values the screen has shown all along; the schedule starts
-- disabled so nothing runs until an operator turns it on.
INSERT INTO backup_policy (policy_id, schedule_enabled, daily_backup_cron, backup_retention_days, snapshot_retention_depth)
VALUES ('default', false, '0 2 * * *', 14, 4);

CREATE TABLE backup_baseline (
    device_id    TEXT        PRIMARY KEY REFERENCES devices(device_id),
    artefact_id  TEXT        NOT NULL REFERENCES backup_artefact(artefact_id),
    set_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_audit_backup_baseline
    AFTER INSERT OR UPDATE OR DELETE ON backup_baseline
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('device_id');

GRANT SELECT, UPDATE ON backup_policy TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON backup_baseline TO ui2_app;
