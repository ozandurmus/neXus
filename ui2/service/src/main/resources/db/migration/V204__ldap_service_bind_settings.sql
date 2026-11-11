-- V204 -- LDAP sign-in moves to the Active Directory service-bind model
-- (docs/design/PO_DECISION_RECORD_2026_11_11_LDAP_SERVICE_BIND.md,
--  docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md).
--
-- 1. ldap_settings: one row (id = 1), the Tufin-like form. The service account password is stored only as
--    AES-256-GCM ciphertext under the credential store key (CredentialStoreCipher); the browser never reads it.
-- 2. directory_profiles (V26, the %s bind-DN-template model) is retired. Its rows cannot be migrated: the model
--    has no service account, no domain DN and no admin/users groups. Its write history stays in audit_log.

CREATE TABLE ldap_settings (
    id                        SMALLINT    PRIMARY KEY CHECK (id = 1),
    enabled                   BOOLEAN     NOT NULL DEFAULT FALSE,
    server_type               TEXT        NOT NULL DEFAULT 'ACTIVE_DIRECTORY'
                                          CHECK (server_type IN ('ACTIVE_DIRECTORY')),
    servers                   TEXT[]      NOT NULL CHECK (cardinality(servers) BETWEEN 1 AND 5),
    port                      INTEGER     NOT NULL DEFAULT 636 CHECK (port BETWEEN 1 AND 65535),
    domain_dn                 TEXT        NOT NULL,
    admin_group_dn            TEXT        NOT NULL,
    users_group_dn            TEXT        NOT NULL,
    bind_dn                   TEXT        NOT NULL,
    bind_password_encrypted   BYTEA       NOT NULL,
    bind_password_key_id      TEXT        NOT NULL,
    connect_timeout_seconds   INTEGER     NOT NULL DEFAULT 10 CHECK (connect_timeout_seconds BETWEEN 1 AND 60),
    ldaps                     BOOLEAN     NOT NULL DEFAULT TRUE,
    trust_mode                TEXT        NOT NULL DEFAULT 'TRUST_PEM' CHECK (trust_mode IN ('TRUST_ANY', 'TRUST_PEM')),
    trust_pem                 TEXT,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                TEXT        NOT NULL,
    CHECK (NOT ldaps OR trust_mode = 'TRUST_ANY' OR (trust_pem IS NOT NULL AND trust_pem <> ''))
);

-- Narrow capture: table, key, operation, actor and action only; never a column value (the row holds the
-- encrypted service password and directory names).
CREATE FUNCTION fn_audit_capture_ldap_settings() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_actor TEXT := CASE WHEN TG_OP = 'DELETE' THEN current_setting('app.actor_fingerprint', true)
        ELSE NEW.updated_by END;
BEGIN
    IF v_actor IS NULL OR v_actor = '' THEN
        RAISE EXCEPTION 'ldap settings change requires an actor';
    END IF;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id)
    VALUES ('ldap_settings', COALESCE(NEW.id, OLD.id)::TEXT, TG_OP, v_actor, 'ldap_config_write');
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER tr_audit_ldap_settings
    AFTER INSERT OR UPDATE OR DELETE ON ldap_settings
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture_ldap_settings();

GRANT SELECT, INSERT, UPDATE ON ldap_settings TO ui2_app;

-- No directory principal may outlive the model that proved it (none on the live system; test databases may hold some).
DELETE FROM actor_authz_state WHERE directory_profile_id IS NOT NULL;

DROP TRIGGER IF EXISTS tr_audit_directory_profiles ON directory_profiles;
DROP TABLE directory_profiles;
DROP FUNCTION IF EXISTS fn_audit_capture_directory_profiles();
