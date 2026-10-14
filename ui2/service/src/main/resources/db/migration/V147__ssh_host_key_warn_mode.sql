-- RATIFIED PO decision 2026-10-14: mismatch admission is read-only.
CREATE TABLE IF NOT EXISTS ssh_trust_settings (
    settings_id TEXT PRIMARY KEY CHECK (settings_id = 'default'),
    mode TEXT NOT NULL DEFAULT 'WARN' CHECK (mode IN ('WARN', 'STRICT'))
);
INSERT INTO ssh_trust_settings(settings_id) VALUES ('default') ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS ssh_host_key_observation (
    observation_id TEXT PRIMARY KEY,
    device_id TEXT REFERENCES devices(device_id) ON DELETE CASCADE,
    endpoint_id TEXT NOT NULL,
    management_address TEXT NOT NULL,
    management_port INTEGER NOT NULL CHECK (management_port BETWEEN 1 AND 65535),
    key_algorithm TEXT NOT NULL,
    presented_fingerprint TEXT NOT NULL CHECK (presented_fingerprint ~ '^[0-9a-f]{64}$'),
    expected_fingerprint TEXT NOT NULL CHECK (expected_fingerprint ~ '^[0-9a-f]{64}$'),
    first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
    seen_count BIGINT NOT NULL DEFAULT 1 CHECK (seen_count > 0),
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED')),
    strict_refusal BOOLEAN NOT NULL DEFAULT false,
    last_job_id TEXT,
    connection_allowed BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (endpoint_id, management_address, management_port, key_algorithm, presented_fingerprint, expected_fingerprint)
);
CREATE INDEX IF NOT EXISTS ssh_host_key_observation_device ON ssh_host_key_observation(device_id, status);
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS ssh_identity_unverified BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS ssh_identity_unverified BOOLEAN NOT NULL DEFAULT false;

CREATE OR REPLACE FUNCTION fn_audit_ssh_host_key_policy() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    actor TEXT := current_setting('app.actor_fingerprint', true);
    action TEXT := current_setting('app.action_id', true);
    projection JSONB;
    pk TEXT;
BEGIN
    IF actor IS NULL OR actor = '' OR action IS NULL OR action = '' THEN
        RAISE EXCEPTION 'audit_context_missing';
    END IF;
    IF TG_TABLE_NAME = 'ssh_trust_settings' THEN
        pk := NEW.settings_id;
        projection := jsonb_build_object('mode', NEW.mode);
    ELSE
        pk := NEW.observation_id;
        projection := jsonb_build_object('observation_id', NEW.observation_id,
            'device_id', NEW.device_id, 'job_id', NEW.last_job_id,
            'status', NEW.status, 'strict_refusal', NEW.strict_refusal, 'seen_count', NEW.seen_count,
            'connection_allowed', NEW.connection_allowed);
    END IF;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, after_state)
    VALUES (TG_TABLE_NAME, pk, TG_OP, actor, action, projection);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fn_audit_ssh_host_key_policy() FROM PUBLIC;
DROP TRIGGER IF EXISTS trg_audit_ssh_trust_settings ON ssh_trust_settings;
CREATE TRIGGER trg_audit_ssh_trust_settings AFTER UPDATE ON ssh_trust_settings
    FOR EACH ROW EXECUTE FUNCTION fn_audit_ssh_host_key_policy();
DROP TRIGGER IF EXISTS trg_audit_ssh_host_key_observation ON ssh_host_key_observation;
CREATE TRIGGER trg_audit_ssh_host_key_observation AFTER INSERT OR UPDATE ON ssh_host_key_observation
    FOR EACH ROW EXECUTE FUNCTION fn_audit_ssh_host_key_policy();
GRANT SELECT, INSERT, UPDATE, DELETE ON ssh_trust_settings TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ssh_host_key_observation TO ui2_app;
