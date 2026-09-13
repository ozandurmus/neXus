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
-- RESOLVED by the Product Owner assistant before merge (RELAY_DECISION,
-- relay/NXS-LOCAL-0153): V9 landed first and owns `must_change_password`,
-- so this migration no longer adds it. This movement still SETS it on
-- create and on an administrative password reset (LIA-3.3); enforcing it at
-- login and reporting it on the session belong to V9's movement, which has
-- merged.
--
ALTER TABLE local_credentials
    ADD COLUMN enabled                  BOOLEAN     NOT NULL DEFAULT true,
    ADD COLUMN created_by_actor_fingerprint TEXT,
    ADD COLUMN password_set_at          TIMESTAMPTZ;
-- must_change_password is V9's (NXS-LOCAL-0152); this migration must not add it again.

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
