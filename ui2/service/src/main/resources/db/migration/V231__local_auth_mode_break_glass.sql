-- Spring Security migration plan Step 3 / PO decision D4 (2026-11-15): the local account scope switch and the
-- break-glass login event log.
--
-- local_auth_mode_settings: one row. ALL_LOCAL (default) keeps every local login exactly as before. BREAK_GLASS_ONLY lets
-- only the root identity (nexusadmin) and aiview sign in interactively with a local password; aiview-e2e stays a
-- machine-session identity (port 8086) and every other local account is refused. Never switched automatically.
-- local_break_glass_event: insert-only record of a break-glass sign-in (alert = true for the root identity, which raises the
-- syslog line and the login_security mail digest) and of a correct-password sign-in that the mode refused. No account
-- names, only the opaque actor fingerprint.

CREATE TABLE IF NOT EXISTS local_auth_mode_settings (
    settings_id TEXT PRIMARY KEY CHECK (settings_id = 'default'),
    mode TEXT NOT NULL DEFAULT 'ALL_LOCAL' CHECK (mode IN ('ALL_LOCAL', 'BREAK_GLASS_ONLY')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);
COMMENT ON TABLE local_auth_mode_settings IS
    'Single row. ALL_LOCAL (default) = today''s behaviour; BREAK_GLASS_ONLY = only root identity and aiview sign in with a local password.';

CREATE TABLE IF NOT EXISTS local_break_glass_event (
    event_id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_fingerprint TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('ROOT', 'VERIFICATION', 'OTHER')),
    outcome TEXT NOT NULL CHECK (outcome IN ('LOGIN_SUCCEEDED', 'REFUSED_NON_BREAK_GLASS')),
    alert BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS local_break_glass_event_alert ON local_break_glass_event(event_id) WHERE alert;

GRANT SELECT, INSERT, UPDATE ON local_auth_mode_settings TO ui2_app;
GRANT SELECT, INSERT ON local_break_glass_event TO ui2_app;
GRANT USAGE, SELECT ON SEQUENCE local_break_glass_event_event_id_seq TO ui2_app;

DROP TRIGGER IF EXISTS trg_audit_local_auth_mode_settings ON local_auth_mode_settings;
CREATE TRIGGER trg_audit_local_auth_mode_settings
    AFTER INSERT OR UPDATE OR DELETE ON local_auth_mode_settings
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('settings_id');

DROP TRIGGER IF EXISTS trg_audit_local_break_glass_event ON local_break_glass_event;
CREATE TRIGGER trg_audit_local_break_glass_event
    AFTER INSERT OR UPDATE OR DELETE ON local_break_glass_event
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('event_id');

SELECT set_config('app.actor_fingerprint', 'migration:V231_local_auth_mode', true);
SELECT set_config('app.action_id', 'local_auth_mode_seed_by_migration', true);
INSERT INTO local_auth_mode_settings(settings_id, updated_by) VALUES ('default', 'local-auth-mode-migration')
ON CONFLICT (settings_id) DO NOTHING;
