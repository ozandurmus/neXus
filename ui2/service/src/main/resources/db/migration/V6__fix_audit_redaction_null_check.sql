-- V6 — correct V5's fail-closed redaction check
-- (UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md §5.4, Amendment A-2)
--
-- V5 verified "declared redactions took effect" by comparing the whole
-- redacted snapshot against the raw row:
--
--     IF (v_before IS NOT NULL AND v_before = to_jsonb(OLD))
--        OR (v_after IS NOT NULL AND v_after = to_jsonb(NEW)) THEN
--         RAISE EXCEPTION 'audit_redaction_not_applied ...'
--
-- That is wrong whenever every declared-redacted column of the row is NULL.
-- fn_audit_redact deliberately leaves NULL as NULL (contract §2, so a null is
-- distinguishable from a redacted value), so the redacted snapshot equals the
-- raw row, and the check fires on a row that had nothing to redact.
--
-- Reproduced against a real PostgreSQL 16 server, not inferred:
-- job_step_attempt.captured_variables is the table's only declared-redacted
-- column and is NULL at pre-contact insert time (C2 §5.1: unknown until the
-- step runs), so every pre-contact job_step_attempt insert raised
-- 'audit_redaction_not_applied' and rolled back. That blocked the collection
-- engine's first per-step write against any migrated database.
--
-- The check now asserts the property it meant to assert, per column and
-- NULL-safe: every declared-redacted key present in the snapshot must be
-- either NULL or a 'sha256:' digest. It is strictly stronger than the
-- whole-object comparison it replaces -- that one passed as long as *any*
-- column changed, while this one inspects each declared column individually --
-- and it no longer fires on a row with nothing to redact.

CREATE OR REPLACE FUNCTION fn_audit_redaction_is_applied(p_table TEXT, p_snapshot JSONB)
    RETURNS BOOLEAN AS $$
DECLARE
    v_col TEXT;
    v_val JSONB;
BEGIN
    IF p_snapshot IS NULL THEN
        RETURN TRUE;
    END IF;

    FOR v_col IN
        SELECT column_name FROM audit_redaction_policy WHERE table_name = p_table
    LOOP
        IF p_snapshot ? v_col THEN
            v_val := p_snapshot -> v_col;
            -- NULL is left as NULL by design; anything else must be a digest.
            IF v_val <> 'null'::jsonb
               AND (jsonb_typeof(v_val) <> 'string'
                    OR left(p_snapshot ->> v_col, 7) <> 'sha256:') THEN
                RETURN FALSE;
            END IF;
        END IF;
    END LOOP;

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

CREATE OR REPLACE FUNCTION fn_audit_capture() RETURNS trigger AS $$
DECLARE
    v_actor  TEXT  := current_setting('app.actor_fingerprint', true);
    v_action TEXT  := current_setting('app.action_id', true);
    v_pk_col TEXT  := TG_ARGV[0];
    v_row    JSONB := to_jsonb(COALESCE(NEW, OLD));
    v_before JSONB;
    v_after  JSONB;
BEGIN
    IF v_actor IS NULL OR v_actor = '' OR v_action IS NULL OR v_action = '' THEN
        RAISE EXCEPTION 'audit_context_missing on %.%: actor or action not set in this transaction',
            TG_TABLE_NAME, v_pk_col;
    END IF;

    v_before := CASE WHEN TG_OP = 'INSERT' THEN NULL
                     ELSE fn_audit_redact(TG_TABLE_NAME, to_jsonb(OLD)) END;
    v_after  := CASE WHEN TG_OP = 'DELETE' THEN NULL
                     ELSE fn_audit_redact(TG_TABLE_NAME, to_jsonb(NEW)) END;

    -- Fail closed rather than persist an unredacted value.
    IF NOT fn_audit_redaction_is_applied(TG_TABLE_NAME, v_before)
       OR NOT fn_audit_redaction_is_applied(TG_TABLE_NAME, v_after) THEN
        RAISE EXCEPTION 'audit_redaction_not_applied on %: a declared-redacted column holds a raw value',
            TG_TABLE_NAME;
    END IF;

    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id,
                           before_state, after_state, correlation_run_id)
    VALUES (TG_TABLE_NAME, v_row ->> v_pk_col, TG_OP, v_actor, v_action,
            v_before, v_after,
            current_setting('app.correlation_run_id', true));
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
