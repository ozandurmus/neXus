-- V251 -- Script Execution module, slice 1: script record, append-only versions, encrypted write-only script_body
-- artefact metadata, secret-scan findings, declared credential_refs and egress_device_targets
-- (docs/design/SCRIPT_EXECUTION_CONTRACT.md, FROZEN 2026-09-22, and Amendment 2026-11-18 in
-- docs/design/PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md, RATIFIED 2026-11-18).
--
-- 1. script: one row per named operator script, holding current_version, schedule, timeout_s, egress,
--    egress_device_targets (opaque device_ids matched against stored endpoints), credential_refs, notify and run_as.
-- 2. script_version: append-only monotonic versions per script. Every script body (text or binary) is stored
--    encrypted at rest in the artefact store (class 'script_body'); no plaintext body column exists here.
-- 3. Audit: trg_audit_script and trg_audit_script_version capture mutations via fn_audit_capture('script_id').
--    script.egress and script_version.wrapped_data_key are registered in audit_redaction_policy.

SELECT set_config('app.actor_fingerprint', 'migration:V251_script_execution_slice1', true);
SELECT set_config('app.action_id', 'audit_redaction_policy_seed_by_migration', true);

CREATE TABLE IF NOT EXISTS script (
    script_id                     TEXT        PRIMARY KEY CHECK (btrim(script_id) <> ''),
    name                          TEXT        NOT NULL UNIQUE CHECK (char_length(btrim(name)) BETWEEN 1 AND 128),
    kind                          TEXT        NOT NULL CHECK (kind IN ('sh', 'bash', 'py', 'jar', 'js', 'mjs', 'pl', 'rb', 'ps1', 'bin')),
    current_version               INTEGER     NOT NULL CHECK (current_version >= 1),
    enabled                       BOOLEAN     NOT NULL DEFAULT FALSE,
    schedule                      TEXT        CHECK (schedule IS NULL OR char_length(btrim(schedule)) BETWEEN 9 AND 120),
    timeout_s                     INTEGER     NOT NULL DEFAULT 300 CHECK (timeout_s BETWEEN 1 AND 3600),
    egress                        TEXT[]      NOT NULL DEFAULT '{}',
    egress_device_targets         TEXT[]      NOT NULL DEFAULT '{}',
    credential_refs               TEXT[]      NOT NULL DEFAULT '{}',
    notify                        TEXT        NOT NULL DEFAULT 'none' CHECK (notify IN ('syslog', 'smtp', 'both', 'none')),
    run_as                        TEXT        NOT NULL DEFAULT 'role:operator'
                                              CHECK (btrim(run_as) <> '' AND run_as <> 'role:security_admin' AND run_as <> 'security_admin'),
    created_by_actor_fingerprint  TEXT        NOT NULL CHECK (btrim(created_by_actor_fingerprint) <> ''),
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_script_enabled_schedule ON script (enabled) WHERE enabled = TRUE AND schedule IS NOT NULL;

CREATE TABLE IF NOT EXISTS script_version (
    script_id              TEXT        NOT NULL REFERENCES script(script_id) ON DELETE RESTRICT,
    version                INTEGER     NOT NULL CHECK (version >= 1),
    sha256                 TEXT        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes             BIGINT      NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    artefact_ref           TEXT        NOT NULL CHECK (btrim(artefact_ref) <> ''),
    key_id                 TEXT        NOT NULL CHECK (btrim(key_id) <> ''),
    wrapped_data_key       BYTEA       NOT NULL CHECK (octet_length(wrapped_data_key) BETWEEN 28 AND 256),
    secret_findings        JSONB       NOT NULL DEFAULT '{"count":0,"line_numbers":[],"findings":[]}'::jsonb
                                       CHECK (jsonb_typeof(secret_findings) = 'object'
                                           AND (secret_findings ? 'count')
                                           AND (secret_findings ? 'findings')),
    credential_refs        TEXT[]      NOT NULL DEFAULT '{}',
    egress_device_targets  TEXT[]      NOT NULL DEFAULT '{}',
    created_by             TEXT        NOT NULL CHECK (btrim(created_by) <> ''),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (script_id, version)
);

CREATE INDEX IF NOT EXISTS idx_script_version_script_created ON script_version (script_id, version DESC);

CREATE OR REPLACE FUNCTION fn_guard_script_version_append_only() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    v_expected INTEGER;
BEGIN
    IF TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'script_version_append_only: script versions are immutable';
    END IF;
    SELECT COALESCE(MAX(version), 0) + 1 INTO v_expected
      FROM script_version WHERE script_id = NEW.script_id;
    IF NEW.version <> v_expected THEN
        RAISE EXCEPTION 'script_version_not_monotonic: expected %, got %', v_expected, NEW.version;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fn_guard_script_version_append_only() FROM PUBLIC;

DROP TRIGGER IF EXISTS trg_guard_script_version_append_only ON script_version;
CREATE TRIGGER trg_guard_script_version_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON script_version
    FOR EACH ROW EXECUTE FUNCTION fn_guard_script_version_append_only();

DROP TRIGGER IF EXISTS trg_audit_script ON script;
CREATE TRIGGER trg_audit_script
    AFTER INSERT OR UPDATE OR DELETE ON script
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('script_id');

DROP TRIGGER IF EXISTS trg_audit_script_version ON script_version;
CREATE TRIGGER trg_audit_script_version
    AFTER INSERT OR UPDATE OR DELETE ON script_version
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('script_id');

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('script', 'egress', 2, 'Operator-declared egress host:port targets may include internal endpoints'),
    ('script_version', 'wrapped_data_key', 1, 'Wrapped per-version script body data key -- cryptographic material outside its key lifecycle')
ON CONFLICT (table_name, column_name) DO NOTHING;

GRANT SELECT, INSERT, UPDATE ON script TO ui2_app;
REVOKE INSERT, UPDATE, DELETE ON script_version FROM ui2_app;
GRANT SELECT, INSERT ON script_version TO ui2_app;

DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT, UPDATE', 'script'),
        ('ui2_scheduler', 'SELECT', 'script'),
        ('ui2_worker_general', 'SELECT', 'script'),
        ('ui2_service', 'SELECT, INSERT', 'script_version'),
        ('ui2_scheduler', 'SELECT', 'script_version'),
        ('ui2_worker_general', 'SELECT', 'script_version')
    ) AS g(role_name, privs, tbl_name)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.%I TO %I', v_rec.privs, v_rec.tbl_name, v_rec.role_name);
        END IF;
    END LOOP;
END
$grants$;
