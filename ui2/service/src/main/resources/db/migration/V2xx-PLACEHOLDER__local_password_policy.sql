-- Configurable local-account password policy (backlog ui2_password_policy_configurable, PO 2026-09-14).
-- OFF by default: with enabled = false the password rules are exactly the pre-existing ones (minimum 12 characters,
-- not equal to the account name). LDAP and SAML users hold no local_credentials row and are never touched.
-- Rename this file to the next free V number when it is queued for a ship.

CREATE TABLE IF NOT EXISTS local_password_policy_settings (
    settings_id TEXT PRIMARY KEY CHECK (settings_id = 'default'),
    enabled BOOLEAN NOT NULL DEFAULT false,
    min_length INTEGER NOT NULL DEFAULT 12 CHECK (min_length BETWEEN 12 AND 128),
    require_upper BOOLEAN NOT NULL DEFAULT false,
    require_lower BOOLEAN NOT NULL DEFAULT false,
    require_digit BOOLEAN NOT NULL DEFAULT false,
    require_symbol BOOLEAN NOT NULL DEFAULT false,
    history_count INTEGER NOT NULL DEFAULT 0 CHECK (history_count BETWEEN 0 AND 24),
    max_age_days INTEGER NOT NULL DEFAULT 0 CHECK (max_age_days BETWEEN 0 AND 3650),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);
COMMENT ON TABLE local_password_policy_settings IS
    'Single row. enabled=false (default) keeps the original local password rules; history_count and max_age_days 0 mean off.';

-- Retired local password verifiers, kept only while the reuse rule (history_count > 0) is on. Never audited in full:
-- verifier and salt are tier-1 secrets, so this table has no audit trigger; the change itself is audited on
-- local_credentials (password_set_at) under the change action.
CREATE TABLE IF NOT EXISTS local_password_history (
    history_id BIGSERIAL PRIMARY KEY,
    local_identity_id TEXT NOT NULL REFERENCES local_credentials(local_identity_id) ON DELETE CASCADE,
    verifier BYTEA NOT NULL,
    salt BYTEA NOT NULL,
    algorithm_id TEXT NOT NULL,
    memory_cost_kib INTEGER NOT NULL,
    time_cost INTEGER NOT NULL,
    parallelism INTEGER NOT NULL,
    retired_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS local_password_history_identity ON local_password_history(local_identity_id, history_id DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON local_password_policy_settings TO ui2_app;
GRANT SELECT, INSERT, DELETE ON local_password_history TO ui2_app;
GRANT USAGE, SELECT ON SEQUENCE local_password_history_history_id_seq TO ui2_app;

DROP TRIGGER IF EXISTS trg_audit_local_password_policy_settings ON local_password_policy_settings;
CREATE TRIGGER trg_audit_local_password_policy_settings
    AFTER INSERT OR UPDATE OR DELETE ON local_password_policy_settings
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('settings_id');

-- Seeded after the trigger exists so the audit log records where the defaults came from.
INSERT INTO local_password_policy_settings(settings_id, updated_by) VALUES ('default', 'password-policy-migration')
ON CONFLICT (settings_id) DO NOTHING;
