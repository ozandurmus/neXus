-- V237 -- SAML 2.0 service provider for ADFS (docs/design/SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md, Step 4; PO decisions
-- D1-D5 of 2026-11-15).
--
-- 1. saml_settings: one row (id = 1), OFF by default (no row = SAML off). It holds the SP identity, the IdP data taken from the
--    metadata the administrator pasted (entity id, single sign-on URL and binding, signing certificates: all public data),
--    the group claim attribute (D1, default the ADFS Group claim URI), the two mapped group values, the clock skew bound and
--    an optional SP key pair (D5: the private key only as AES-256-GCM ciphertext under the credential store key; the file
--    path alternative lives in the environment, never here).
-- 2. role_bindings.binding_kind accepts 'SAML_GROUP' (a group binding of the SAML profile, same shape as DIRECTORY_GROUP).

CREATE TABLE saml_settings (
    id                          SMALLINT    PRIMARY KEY CHECK (id = 1),
    enabled                     BOOLEAN     NOT NULL DEFAULT FALSE,
    sp_base_url                 TEXT        NOT NULL,
    sp_entity_id                TEXT        NOT NULL,
    idp_entity_id               TEXT        NOT NULL,
    idp_sso_url                 TEXT        NOT NULL,
    idp_sso_binding             TEXT        NOT NULL DEFAULT 'REDIRECT' CHECK (idp_sso_binding IN ('REDIRECT', 'POST')),
    idp_signing_cert_pem        TEXT        NOT NULL CHECK (idp_signing_cert_pem <> ''),
    group_claim_attribute       TEXT        NOT NULL DEFAULT 'http://schemas.xmlsoap.org/claims/Group'
                                            CHECK (group_claim_attribute <> ''),
    admin_group_value           TEXT        NOT NULL CHECK (admin_group_value <> ''),
    users_group_value           TEXT        NOT NULL CHECK (users_group_value <> ''),
    clock_skew_seconds          INTEGER     NOT NULL DEFAULT 60 CHECK (clock_skew_seconds BETWEEN 0 AND 300),
    sign_authn_requests         BOOLEAN     NOT NULL DEFAULT FALSE,
    sp_cert_pem                 TEXT,
    sp_private_key_encrypted    BYTEA,
    sp_private_key_key_id       TEXT,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                  TEXT        NOT NULL,
    CHECK (admin_group_value <> users_group_value),
    CHECK ((sp_private_key_encrypted IS NULL AND sp_private_key_key_id IS NULL)
        OR (sp_private_key_encrypted IS NOT NULL AND sp_private_key_key_id IS NOT NULL AND sp_cert_pem IS NOT NULL)),
    CHECK (NOT sign_authn_requests OR sp_cert_pem IS NOT NULL)
);

-- Narrow capture: table, key, operation, actor and action only; never a column value (the row holds the encrypted SP key and
-- the mapped group names).
CREATE FUNCTION fn_audit_capture_saml_settings() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_actor TEXT := CASE WHEN TG_OP = 'DELETE' THEN current_setting('app.actor_fingerprint', true)
        ELSE NEW.updated_by END;
BEGIN
    IF v_actor IS NULL OR v_actor = '' THEN
        RAISE EXCEPTION 'saml settings change requires an actor';
    END IF;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id)
    VALUES ('saml_settings', COALESCE(NEW.id, OLD.id)::TEXT, TG_OP, v_actor, 'saml_config_write');
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER tr_audit_saml_settings
    AFTER INSERT OR UPDATE OR DELETE ON saml_settings
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture_saml_settings();

GRANT SELECT, INSERT, UPDATE ON saml_settings TO ui2_app;

ALTER TABLE role_bindings DROP CONSTRAINT chk_role_binding_kind;
ALTER TABLE role_bindings ADD CONSTRAINT chk_role_binding_kind
    CHECK (binding_kind IN ('LEGACY', 'DIRECTORY_GROUP', 'DIRECTORY_PRINCIPAL', 'SAML_GROUP'));
ALTER TABLE role_bindings DROP CONSTRAINT chk_role_binding_profile;
ALTER TABLE role_bindings ADD CONSTRAINT chk_role_binding_profile
    CHECK ((binding_kind = 'LEGACY' AND directory_profile_id IS NULL)
        OR (binding_kind IN ('DIRECTORY_GROUP', 'DIRECTORY_PRINCIPAL', 'SAML_GROUP')
            AND directory_profile_id IS NOT NULL AND length(directory_profile_id) > 0));
