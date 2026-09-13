-- V8 — local authentication credential storage, per
-- docs/design/UI2_0_C3A_LOCAL_AUTHENTICATION_CONTRACT.md (C3A, FROZEN
-- 2026-09-13) section 3.2 (literal DDL columns), section 3.2's own note
-- (local_identity_name is "assumed necessary" there but its exact shape is
-- left UNKNOWN, U-3 -- this file adds it, case-sensitive, since U-3 remains
-- open) and section 5.2 (failed_attempt_count, locked_until).
--
-- Additive to V1-V7 (append-only; none of them is edited).
--
-- Audit (contract section 8): local_credentials cannot use the generic
-- fn_audit_capture()/fn_audit_redact() path -- verifier, salt and the three
-- Argon2 parameter columns must never reach audit_log, redacted or not.
-- fn_audit_capture_local_credentials() is a named, narrower exception: it
-- builds before_state/after_state from an explicit column allowlist
-- (local_identity_id, failed_attempt_count, locked_until, created_at,
-- updated_at) rather than to_jsonb(OLD)/to_jsonb(NEW).

-- ---------------------------------------------------------------------
-- 1. local_credentials (contract section 3.2 / section 5.2)
-- ---------------------------------------------------------------------

CREATE TABLE local_credentials (
    local_identity_id    TEXT        PRIMARY KEY,
    local_identity_name  TEXT        NOT NULL UNIQUE,
    verifier              BYTEA       NOT NULL,
    salt                  BYTEA       NOT NULL,
    algorithm_id          TEXT        NOT NULL,
    memory_cost_kib       INTEGER     NOT NULL,
    time_cost             INTEGER     NOT NULL,
    parallelism           INTEGER     NOT NULL,
    failed_attempt_count  INTEGER     NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 2. Narrow audit capture (contract section 8) -- a named exception to
-- fn_audit_capture()/fn_audit_capture_local_credentials() is fn_audit_capture's
-- sibling, not a modification of it (C1 §3.5 is unchanged; V1/V5/V6 are
-- not edited).
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_audit_capture_local_credentials() RETURNS trigger AS $$
DECLARE
    v_actor  TEXT := current_setting('app.actor_fingerprint', true);
    v_action TEXT := current_setting('app.action_id', true);
    v_row    RECORD := COALESCE(NEW, OLD);
    v_before JSONB;
    v_after  JSONB;
BEGIN
    IF v_actor IS NULL OR v_actor = '' OR v_action IS NULL OR v_action = '' THEN
        RAISE EXCEPTION 'audit_context_missing on local_credentials: actor or action not set in this transaction';
    END IF;

    -- Explicit column allowlist -- never to_jsonb(OLD)/to_jsonb(NEW), which
    -- would place verifier/salt/parameter values into audit_log (forbidden,
    -- contract section 8).
    v_before := CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE jsonb_build_object(
        'local_identity_id', OLD.local_identity_id,
        'failed_attempt_count', OLD.failed_attempt_count,
        'locked_until', OLD.locked_until,
        'created_at', OLD.created_at,
        'updated_at', OLD.updated_at) END;
    v_after := CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE jsonb_build_object(
        'local_identity_id', NEW.local_identity_id,
        'failed_attempt_count', NEW.failed_attempt_count,
        'locked_until', NEW.locked_until,
        'created_at', NEW.created_at,
        'updated_at', NEW.updated_at) END;

    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id,
                           before_state, after_state, correlation_run_id)
    VALUES ('local_credentials', v_row.local_identity_id, TG_OP, v_actor, v_action,
            v_before, v_after, current_setting('app.correlation_run_id', true));
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE TRIGGER trg_audit_local_credentials
    AFTER INSERT OR UPDATE OR DELETE ON local_credentials
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture_local_credentials();

-- ---------------------------------------------------------------------
-- 3. Grants (C1 section 2.4 pattern: ui2_app reads and writes its own
-- application-owned tables; only audit_log itself is write-revoked)
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE ON local_credentials TO ui2_app;
