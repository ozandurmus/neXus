-- V5 — audit redaction policy (UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md)
--
-- V1's fn_audit_capture persisted to_jsonb(OLD)/to_jsonb(NEW) — the whole
-- row — so a sessions INSERT placed its csrf_secret value verbatim into
-- audit_log.after_state, readable by ui2_app. V5 keeps full-row capture but
-- replaces every declared-redacted column's value with sha256:<hex>, which
-- preserves presence, change detection and equality comparison without
-- disclosure (contract section 2).
--
-- Forward-only: V1 is not edited (B1-2 section 2).

-- ---------------------------------------------------------------------
-- 1. Policy table (contract section 5.2)
-- ---------------------------------------------------------------------

CREATE TABLE audit_redaction_policy (
    table_name  TEXT NOT NULL,
    column_name TEXT NOT NULL,
    tier        SMALLINT NOT NULL CHECK (tier IN (1, 2)),
    reason      TEXT NOT NULL,
    PRIMARY KEY (table_name, column_name)
);

COMMENT ON TABLE audit_redaction_policy IS
    'Columns whose values fn_audit_capture must never persist. Read-only to '
    'ui2_app so the application cannot widen or narrow its own audit policy.';

-- Tier 1: secret or cryptographic material (contract section 3).
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('sessions',      'csrf_secret',                1, 'live secret material'),
    ('role_bindings', 'group_reference_encrypted',  1, 'ciphertext outside its key lifecycle');

-- Tier 2: operational identity, credential location, device-derived content.
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('credential_references', 'backend_pointer',     2, 'credential location'),
    ('secrets_metadata',      'reference_pointer',   2, 'credential location'),
    ('endpoints',             'address_ref',         2, 'management-path operational identity'),
    ('job_step_attempt',      'captured_variables',  2, 'device-derived captured content'),
    ('job_reconciliation',    'evidence',            2, 'free-text evidence of unbounded shape');

-- ---------------------------------------------------------------------
-- 2. Redaction helper
-- ---------------------------------------------------------------------

-- Replaces each declared-redacted key of one row snapshot with
-- sha256:<hex> of its text form. A NULL stays NULL and is therefore
-- distinguishable from a redacted non-null value (contract section 6.3).
CREATE OR REPLACE FUNCTION fn_audit_redact(p_table TEXT, p_row JSONB)
    RETURNS JSONB AS $$
DECLARE
    v_col TEXT;
    v_out JSONB := p_row;
BEGIN
    IF p_row IS NULL THEN
        RETURN NULL;
    END IF;

    FOR v_col IN
        SELECT column_name FROM audit_redaction_policy WHERE table_name = p_table
    LOOP
        IF v_out ? v_col AND v_out -> v_col <> 'null'::jsonb THEN
            v_out := jsonb_set(v_out, ARRAY[v_col],
                to_jsonb('sha256:' || encode(sha256((v_out ->> v_col)::bytea), 'hex')));
        END IF;
    END LOOP;

    RETURN v_out;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

-- ---------------------------------------------------------------------
-- 3. fn_audit_capture, redacting (contract section 5.3, 5.4)
-- ---------------------------------------------------------------------

-- The audit_context_missing check is unchanged and still runs first:
-- redaction is applied after it, never instead of it.
CREATE OR REPLACE FUNCTION fn_audit_capture() RETURNS trigger AS $$
DECLARE
    v_actor  TEXT  := current_setting('app.actor_fingerprint', true);
    v_action TEXT  := current_setting('app.action_id', true);
    v_pk_col TEXT  := TG_ARGV[0];
    v_row    JSONB := to_jsonb(COALESCE(NEW, OLD));
    v_declared INTEGER;
    v_before JSONB;
    v_after  JSONB;
BEGIN
    IF v_actor IS NULL OR v_actor = '' OR v_action IS NULL OR v_action = '' THEN
        RAISE EXCEPTION 'audit_context_missing on %.%: actor or action not set in this transaction',
            TG_TABLE_NAME, v_pk_col;
    END IF;

    -- Fail closed rather than persist an unredacted row: if this table has
    -- declared redactions, the policy must be readable here (contract 5.4).
    SELECT count(*) INTO v_declared
      FROM audit_redaction_policy WHERE table_name = TG_TABLE_NAME;

    v_before := CASE WHEN TG_OP = 'INSERT' THEN NULL
                     ELSE fn_audit_redact(TG_TABLE_NAME, to_jsonb(OLD)) END;
    v_after  := CASE WHEN TG_OP = 'DELETE' THEN NULL
                     ELSE fn_audit_redact(TG_TABLE_NAME, to_jsonb(NEW)) END;

    IF v_declared > 0 THEN
        IF (v_before IS NOT NULL AND v_before = to_jsonb(OLD))
           OR (v_after IS NOT NULL AND v_after = to_jsonb(NEW)) THEN
            RAISE EXCEPTION 'audit_redaction_not_applied on %: declared redactions did not take effect',
                TG_TABLE_NAME;
        END IF;
    END IF;

    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id,
                           before_state, after_state, correlation_run_id)
    VALUES (TG_TABLE_NAME, v_row ->> v_pk_col, TG_OP, v_actor, v_action,
            v_before, v_after,
            current_setting('app.correlation_run_id', true));
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ---------------------------------------------------------------------
-- 4. Purge pre-V5 rows (contract section 5.6)
-- ---------------------------------------------------------------------

-- Rows written before this migration hold unredacted values. There is no
-- production environment and no retention obligation over development rows;
-- an environment that acquires one needs its own successor decision.
DELETE FROM audit_log;

-- ---------------------------------------------------------------------
-- 5. Grants (contract section 5.2)
-- ---------------------------------------------------------------------

GRANT SELECT ON audit_redaction_policy TO ui2_app;
REVOKE INSERT, UPDATE, DELETE ON audit_redaction_policy FROM ui2_app;
