-- PO-approved SNMP credential-store extension only; no device commands or polling.
-- Existing encrypted_secret stores community/auth material; encrypted_passphrase
-- stores SNMP privacy material (or the existing SSH private-key passphrase).
ALTER TABLE credentials DROP CONSTRAINT credentials_kind_check;
ALTER TABLE credentials ADD CONSTRAINT credentials_kind_check CHECK
    (kind IN ('ssh_password', 'ssh_private_key', 'api_password', 'snmp_v1_v2c', 'snmp_v3'));
ALTER TABLE credentials ALTER COLUMN encrypted_secret DROP NOT NULL;
ALTER TABLE credentials ADD COLUMN snmp_security_level TEXT;
ALTER TABLE credentials ADD COLUMN snmp_auth_protocol TEXT;
ALTER TABLE credentials ADD COLUMN snmp_priv_protocol TEXT;
ALTER TABLE credentials DROP CONSTRAINT chk_credentials_passphrase_only_for_private_key;
ALTER TABLE credentials ADD CONSTRAINT chk_credentials_passphrase_kind CHECK
    (kind IN ('ssh_private_key', 'snmp_v3') OR encrypted_passphrase IS NULL);
ALTER TABLE credentials ADD CONSTRAINT chk_credentials_snmp CHECK ((
    (kind <> 'snmp_v3' AND snmp_security_level IS NULL AND snmp_auth_protocol IS NULL
        AND snmp_priv_protocol IS NULL AND encrypted_secret IS NOT NULL
        AND (kind <> 'snmp_v1_v2c' OR username = ''))
    OR
    (kind = 'snmp_v3' AND length(trim(username)) > 0 AND (
        (snmp_security_level = 'noAuthNoPriv' AND snmp_auth_protocol IS NULL
            AND snmp_priv_protocol IS NULL AND encrypted_secret IS NULL AND encrypted_passphrase IS NULL)
        OR
        (snmp_security_level IN ('authNoPriv', 'authPriv')
            AND snmp_auth_protocol IN ('SHA-256', 'SHA-384', 'SHA-512', 'SHA-224', 'SHA1', 'MD5')
            AND encrypted_secret IS NOT NULL AND (
                (snmp_security_level = 'authNoPriv' AND snmp_priv_protocol IS NULL AND encrypted_passphrase IS NULL)
                OR (snmp_security_level = 'authPriv' AND snmp_priv_protocol IN ('AES-128', 'AES-192', 'AES-256', 'DES')
                    AND encrypted_passphrase IS NOT NULL)))))
) IS TRUE);

-- Preserve the narrow audit allowlist; neither encrypted slot is captured.
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
        'snmp_security_level', OLD.snmp_security_level,
        'snmp_auth_protocol', OLD.snmp_auth_protocol,
        'snmp_priv_protocol', OLD.snmp_priv_protocol,
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
        'snmp_security_level', NEW.snmp_security_level,
        'snmp_auth_protocol', NEW.snmp_auth_protocol,
        'snmp_priv_protocol', NEW.snmp_priv_protocol,
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
