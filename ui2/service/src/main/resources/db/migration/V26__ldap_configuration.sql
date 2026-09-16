CREATE TABLE ldap_configuration (
    id VARCHAR(32) PRIMARY KEY,
    server_url TEXT NOT NULL,
    bind_dn TEXT NOT NULL,
    bind_password TEXT NOT NULL,
    base_dn TEXT NOT NULL,
    search_filter TEXT NOT NULL,
    ca_certificate TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION fn_audit_capture_ldap_configuration() RETURNS trigger AS $$
DECLARE
    v_actor  TEXT := current_setting('app.actor_fingerprint', true);
    v_action TEXT := current_setting('app.action_id', true);
BEGIN
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, correlation_run_id)
    VALUES ('ldap_configuration', COALESCE(NEW.id, OLD.id), TG_OP, v_actor, v_action, current_setting('app.correlation_run_id', true));
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
