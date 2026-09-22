-- V44 -- backup policy schedule state and per-device baseline (backlog
-- backup_screen_backbox_model, Product Owner P1 2026-09-22: "Backup screen on
-- the Backbox model").
--
-- V30 created backup_policy (cron, retention, depth, major_alert_enabled) but
-- nothing ever read it: GET /api/v2/backups/policies answered constants and
-- PUT answered 405, so no schedule and no retention ran. This migration gives
-- the row what the scheduler needs -- an on/off switch and the last slot it
-- ran -- an audit trigger (every change is an operator act), and the grants
-- V30 forgot. Values keep V30's seed; the schedule starts disabled so nothing
-- runs until an operator turns it on.
--
-- backup_baseline names, per device, the artefact an operator declared the
-- reference point; Compare offers "vs baseline" next to "vs previous".
SELECT set_config('app.actor_fingerprint', 'migration:V44_backup_policy_and_baseline', true);
SELECT set_config('app.action_id', 'backup_policy_schedule_by_migration', true);

ALTER TABLE backup_policy
    ADD COLUMN IF NOT EXISTS schedule_enabled      BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS last_scheduled_run_at TIMESTAMPTZ;

ALTER TABLE backup_policy
    ADD CONSTRAINT backup_policy_retention_days_range CHECK (backup_retention_days BETWEEN 1 AND 3650),
    ADD CONSTRAINT backup_policy_snapshot_depth_range CHECK (snapshot_retention_depth BETWEEN 1 AND 100);

CREATE TRIGGER trg_audit_backup_policy
    AFTER INSERT OR UPDATE OR DELETE ON backup_policy
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('policy_id');

INSERT INTO backup_policy (policy_id, daily_backup_cron, weekly_snapshot_cron, backup_retention_days,
    snapshot_retention_depth, major_alert_enabled, schedule_enabled)
VALUES ('default', '0 2 * * *', '0 3 * * 0', 14, 4, true, false)
ON CONFLICT (policy_id) DO NOTHING;

-- V30 seeded 30 days / depth 2; the Product Owner's stated values, shown on the
-- screen all along, are 14 days / depth 4. The row is aligned once here; from
-- now on only the audited PUT changes it.
UPDATE backup_policy SET backup_retention_days = 14, snapshot_retention_depth = 4, updated_at = now()
WHERE policy_id = 'default' AND backup_retention_days = 30 AND snapshot_retention_depth = 2;

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
