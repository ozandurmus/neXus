-- V77 -- device onboarding flow (PO, 2026-09-25: a device is never shown half-filled). One row per device added by
-- hand or imported from discovery: identity confirm, then inventory, then configuration, each its own job. The
-- service scheduler advances a RUNNING row when its current job is terminal. docs/design/DEVICE_ONBOARDING_FLOW_CONTRACT.md
SELECT set_config('app.actor_fingerprint', 'migration:V77_device_onboarding', true);
SELECT set_config('app.action_id', 'device_onboarding_by_migration', true);

CREATE TABLE device_onboarding (
    device_id    TEXT        PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
    source       TEXT        NOT NULL CHECK (source IN ('manual_registration', 'discovery_import')),
    state        TEXT        NOT NULL CHECK (state IN ('RUNNING', 'COMPLETED', 'STOPPED')),
    step         TEXT        NOT NULL CHECK (step IN ('identity', 'inventory', 'configuration')),
    step_job_id  TEXT,
    reason       TEXT        CHECK (reason IS NULL OR length(reason) <= 600),
    skipped      TEXT        NOT NULL DEFAULT '',
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_device_onboarding_running ON device_onboarding (state) WHERE state = 'RUNNING';

CREATE TRIGGER trg_audit_device_onboarding
    AFTER INSERT OR UPDATE OR DELETE ON device_onboarding
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('device_id');

GRANT SELECT, INSERT, UPDATE, DELETE ON device_onboarding TO ui2_app;
