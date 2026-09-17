CREATE TABLE directory_profiles (
    id UUID PRIMARY KEY,
    profile_name VARCHAR(255) UNIQUE NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    transport VARCHAR(50) NOT NULL,
    trust_format VARCHAR(50) NOT NULL,
    trust_material_pem TEXT,
    store_pin_encrypted VARCHAR(255),
    bind_dn_template VARCHAR(255) NOT NULL,
    group_search_base_dn VARCHAR(255) NOT NULL,
    access_group_reference VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION fn_audit_capture_directory_profiles() RETURNS trigger AS $$
DECLARE
    v_actor  TEXT := current_setting('app.actor_fingerprint', true);
    v_action TEXT := current_setting('app.action_id', true);
BEGIN
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, correlation_run_id)
    VALUES ('directory_profiles', COALESCE(NEW.id, OLD.id)::TEXT, TG_OP, v_actor, v_action, current_setting('app.correlation_run_id', true));
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE TRIGGER tr_audit_directory_profiles
    AFTER INSERT OR UPDATE OR DELETE ON directory_profiles
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture_directory_profiles();
