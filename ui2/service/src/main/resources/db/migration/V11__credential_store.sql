-- V11 — credential store, per
-- docs/design/PO_DECISION_RECORD_2026_09_14_DEVICE_ADD_ENTRY_PEER_FOLLOW_AND_CREDENTIAL_STORE.md
-- (FROZEN 2026-09-14) section 3 (CS-1..CS-5) and
-- docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md section 3.2
-- (credential_references) / 3.6 (secrets_metadata, whose successor backend
-- kind CS-1 names).
--
-- Additive to V1-V10 (append-only; none of them is edited).
--
-- Audit (C1 section 3.5): credentials cannot use the generic
-- fn_audit_capture() path -- encrypted_secret and encrypted_passphrase must
-- never reach audit_log, not even as ciphertext (an append-only ciphertext/
-- rotation history in the audit table is itself a standing risk).
-- fn_audit_capture_credentials() is a named, narrower exception, mirroring
-- V8's fn_audit_capture_local_credentials() exactly: it builds
-- before_state/after_state from an explicit column allowlist that omits the
-- two encrypted columns.

-- ---------------------------------------------------------------------
-- 1. credentials (CS-1)
-- ---------------------------------------------------------------------

CREATE TABLE credentials (
    credential_id                 TEXT        PRIMARY KEY,
    display_name                  TEXT        NOT NULL,
    kind                           TEXT        NOT NULL CHECK (kind IN ('ssh_password', 'ssh_private_key', 'api_password')),
    username                       TEXT        NOT NULL,
    encrypted_secret               BYTEA       NOT NULL,
    encrypted_passphrase           BYTEA,
    envelope_key_id                TEXT        NOT NULL,
    allows_check_point             BOOLEAN     NOT NULL DEFAULT false,
    allows_palo_alto               BOOLEAN     NOT NULL DEFAULT false,
    created_by_actor_fingerprint   TEXT        NOT NULL,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    secret_set_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_credentials_at_least_one_vendor CHECK (allows_check_point OR allows_palo_alto),
    CONSTRAINT chk_credentials_passphrase_only_for_private_key
        CHECK (kind = 'ssh_private_key' OR encrypted_passphrase IS NULL)
);

-- ---------------------------------------------------------------------
-- 2. secrets_metadata.backend_kind successor value (CS-1's C1 successor
-- clause) -- dropped and re-added, never editing V1's original constraint.
-- ---------------------------------------------------------------------

ALTER TABLE secrets_metadata DROP CONSTRAINT secrets_metadata_backend_kind_check;
ALTER TABLE secrets_metadata ADD CONSTRAINT secrets_metadata_backend_kind_check
    CHECK (backend_kind IN ('env_file', 'vault_reference', 'credential_store'));

-- ---------------------------------------------------------------------
-- 3. Narrow audit capture (C1 section 3.5) -- mirrors V8's
-- fn_audit_capture_local_credentials() exactly: an explicit column
-- allowlist, never to_jsonb(OLD)/to_jsonb(NEW), so encrypted_secret and
-- encrypted_passphrase never reach audit_log, redacted or not.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_audit_capture_credentials() RETURNS trigger AS $$
DECLARE
    v_actor  TEXT := current_setting('app.actor_fingerprint', true);
    v_action TEXT := current_setting('app.action_id', true);
    v_row    RECORD := COALESCE(NEW, OLD);
    v_before JSONB;
    v_after  JSONB;
BEGIN
    IF v_actor IS NULL OR v_actor = '' OR v_action IS NULL OR v_action = '' THEN
        RAISE EXCEPTION 'audit_context_missing on credentials: actor or action not set in this transaction';
    END IF;

    v_before := CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE jsonb_build_object(
        'credential_id', OLD.credential_id,
        'display_name', OLD.display_name,
        'kind', OLD.kind,
        'username', OLD.username,
        'envelope_key_id', OLD.envelope_key_id,
        'allows_check_point', OLD.allows_check_point,
        'allows_palo_alto', OLD.allows_palo_alto,
        'created_by_actor_fingerprint', OLD.created_by_actor_fingerprint,
        'created_at', OLD.created_at,
        'secret_set_at', OLD.secret_set_at) END;
    v_after := CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE jsonb_build_object(
        'credential_id', NEW.credential_id,
        'display_name', NEW.display_name,
        'kind', NEW.kind,
        'username', NEW.username,
        'envelope_key_id', NEW.envelope_key_id,
        'allows_check_point', NEW.allows_check_point,
        'allows_palo_alto', NEW.allows_palo_alto,
        'created_by_actor_fingerprint', NEW.created_by_actor_fingerprint,
        'created_at', NEW.created_at,
        'secret_set_at', NEW.secret_set_at) END;

    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id,
                           before_state, after_state, correlation_run_id)
    VALUES ('credentials', v_row.credential_id, TG_OP, v_actor, v_action,
            v_before, v_after, current_setting('app.correlation_run_id', true));
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE TRIGGER trg_audit_credentials
    AFTER INSERT OR UPDATE OR DELETE ON credentials
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture_credentials();

-- ---------------------------------------------------------------------
-- 4. Grants (C1 section 2.4 pattern: ui2_app reads and writes its own
-- application-owned tables; only audit_log itself is write-revoked). The
-- worker resolvers (SB-16) read this table under the same ui2_app role the
-- service already writes it under.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE ON credentials TO ui2_app;
