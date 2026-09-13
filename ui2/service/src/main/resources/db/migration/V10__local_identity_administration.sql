-- V10 -- local identity administration, per
-- docs/design/PO_DECISION_RECORD_2026_09_13G_LOCAL_IDENTITY_ADMINISTRATION_AND_CLI_PARITY.md
-- (13G, FROZEN 2026-09-13) section 4 (the additive migration) and LIA-3
-- (the six proportionate constraints).
--
-- Additive to V1-V9 (append-only; none of them is edited). V9 does not
-- exist in this branch's history as of this movement's start (the parallel
-- movement, relay NXS-LOCAL-0152, lane feature/login-session-ux, owns V9
-- and has not landed it yet) -- this movement still takes V10, per its own
-- brief's explicit instruction.
--
-- RELAY_QUESTION (see relay/NXS-LOCAL-0153-local-identity-administration.json):
-- LIA-3.3 requires a created/administratively-reset identity to carry a
-- must-change-password flag, described in this movement's own brief as
-- "V9's, the parallel movement's". That flag does not exist anywhere in
-- either branch's history at this movement's start, and 13G section 3
-- separately requires every local-identity response/list to report "whether
-- a password change is required" -- a requirement this movement cannot
-- satisfy at all without a persisted column. Rather than leave section 3's
-- response contract unsatisfiable, this movement adds `must_change_password`
-- to `local_credentials` below and only ever SETS it (create, admin
-- set-password) -- it does not enforce it at login or extend
-- GET /session/status, which stay the parallel movement's own scope. This
-- is a disclosed, additive, reversible assumption: the Product Owner must
-- reconcile this column with NXS-LOCAL-0152's own V9 before merge (rename
-- or de-duplicate then, not now) rather than have two movements silently
-- invent two different flags for the same concept.

-- ---------------------------------------------------------------------
-- 1. local_credentials additive columns (13G section 4 / LIA-3.1/3.3)
-- ---------------------------------------------------------------------

ALTER TABLE local_credentials
    ADD COLUMN enabled                  BOOLEAN     NOT NULL DEFAULT true,
    ADD COLUMN created_by_actor_fingerprint TEXT,
    ADD COLUMN password_set_at          TIMESTAMPTZ,
    ADD COLUMN must_change_password     BOOLEAN     NOT NULL DEFAULT false;

-- Backfill (13G section 4): every existing row was created by the one path
-- that existed before this movement -- the deployment-controlled
-- `bootstrap-local-identity` CLI command, which always attributes its own
-- creation to SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR
-- ("system:bootstrap"). Its password was set at row creation, so
-- password_set_at backfills from created_at. Pre-existing rows are not
-- retroactively required to change their password (must_change_password's
-- column default, false, is left as-is for them).
UPDATE local_credentials
    SET created_by_actor_fingerprint = 'system:bootstrap',
        password_set_at = created_at
    WHERE created_by_actor_fingerprint IS NULL;

ALTER TABLE local_credentials
    ALTER COLUMN created_by_actor_fingerprint SET NOT NULL,
    ALTER COLUMN password_set_at SET NOT NULL,
    ALTER COLUMN password_set_at SET DEFAULT now();

-- ---------------------------------------------------------------------
-- 2. Narrow audit capture (C1 section 3.5) -- fn_audit_capture_local_credentials()
-- (V8) is extended to cover the four new columns exactly as it already
-- covers failed_attempt_count/locked_until/created_at/updated_at: still an
-- explicit allowlist, still never verifier/salt/algorithm_id/*_cost*.
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

    v_before := CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE jsonb_build_object(
        'local_identity_id', OLD.local_identity_id,
        'failed_attempt_count', OLD.failed_attempt_count,
        'locked_until', OLD.locked_until,
        'created_at', OLD.created_at,
        'updated_at', OLD.updated_at,
        'enabled', OLD.enabled,
        'created_by_actor_fingerprint', OLD.created_by_actor_fingerprint,
        'password_set_at', OLD.password_set_at,
        'must_change_password', OLD.must_change_password) END;
    v_after := CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE jsonb_build_object(
        'local_identity_id', NEW.local_identity_id,
        'failed_attempt_count', NEW.failed_attempt_count,
        'locked_until', NEW.locked_until,
        'created_at', NEW.created_at,
        'updated_at', NEW.updated_at,
        'enabled', NEW.enabled,
        'created_by_actor_fingerprint', NEW.created_by_actor_fingerprint,
        'password_set_at', NEW.password_set_at,
        'must_change_password', NEW.must_change_password) END;

    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id,
                           before_state, after_state, correlation_run_id)
    VALUES ('local_credentials', v_row.local_identity_id, TG_OP, v_actor, v_action,
            v_before, v_after, current_setting('app.correlation_run_id', true));
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
