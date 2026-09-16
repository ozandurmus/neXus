-- C10: endpoint-scoped authorization, retained history, sanitized audit only.
CREATE TABLE management_endpoint_ssh_trust (
    trust_entry_id TEXT PRIMARY KEY,
    vendor TEXT NOT NULL CHECK (vendor = 'CHECK_POINT'),
    management_address TEXT NOT NULL CHECK (length(management_address) BETWEEN 1 AND 253),
    management_port INTEGER NOT NULL CHECK (management_port BETWEEN 1 AND 65535),
    key_algorithm TEXT NOT NULL CHECK (key_algorithm IN
        ('ssh-ed25519', 'ssh-rsa', 'ecdsa-sha2-nistp256', 'ecdsa-sha2-nistp384', 'ecdsa-sha2-nistp521')),
    fingerprint_sha256 TEXT NOT NULL CHECK (fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
    authorized_by TEXT NOT NULL,
    authorized_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL CHECK (observed_at <= authorized_at),
    superseded_by TEXT REFERENCES management_endpoint_ssh_trust(trust_entry_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK ((status = 'ACTIVE' AND superseded_by IS NULL)
        OR (status = 'SUPERSEDED' AND superseded_by IS NOT NULL)),
    CHECK (superseded_by IS DISTINCT FROM trust_entry_id)
);
CREATE UNIQUE INDEX management_endpoint_ssh_trust_active
    ON management_endpoint_ssh_trust(management_address, management_port, key_algorithm)
    WHERE status = 'ACTIVE';

CREATE FUNCTION fn_audit_management_endpoint_ssh_trust() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    actor TEXT := current_setting('app.actor_fingerprint', true);
    action TEXT := current_setting('app.action_id', true);
    safe_after JSONB;
BEGIN
    IF actor IS NULL OR actor = '' OR action IS NULL OR action = '' THEN
        RAISE EXCEPTION 'audit_context_missing';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'trust_history_immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND (OLD.status <> 'ACTIVE' OR NEW.status <> 'SUPERSEDED'
        OR (to_jsonb(OLD) - 'status' - 'superseded_by') IS DISTINCT FROM
           (to_jsonb(NEW) - 'status' - 'superseded_by')) THEN
        RAISE EXCEPTION 'trust_history_immutable';
    END IF;
    IF (TG_OP = 'INSERT' AND (NEW.status <> 'ACTIVE' OR NEW.authorized_by <> actor
        OR action NOT IN ('discovery_ssh_trust_enroll', 'discovery_ssh_trust_re_enroll')))
        OR (TG_OP = 'UPDATE' AND action <> 'discovery_ssh_trust_re_enroll') THEN
        RAISE EXCEPTION 'trust_authorization_required';
    END IF;
    safe_after := jsonb_build_object('trust_entry_id', NEW.trust_entry_id,
        'vendor', NEW.vendor, 'management_port', NEW.management_port,
        'key_algorithm', NEW.key_algorithm, 'status', NEW.status,
        'authorized_at', NEW.authorized_at, 'observed_at', NEW.observed_at,
        'classification', CASE WHEN action = 'discovery_ssh_trust_re_enroll'
            THEN 'RE-ENROLLED' ELSE 'ENROLLED' END);
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, before_state, after_state)
        VALUES ('management_endpoint_ssh_trust', NEW.trust_entry_id, TG_OP, actor, action,
            CASE WHEN TG_OP = 'UPDATE' THEN jsonb_build_object('status', OLD.status) ELSE NULL END, safe_after);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fn_audit_management_endpoint_ssh_trust() FROM PUBLIC;
CREATE TRIGGER trg_audit_management_endpoint_ssh_trust
    BEFORE INSERT OR UPDATE OR DELETE ON management_endpoint_ssh_trust
    FOR EACH ROW EXECUTE FUNCTION fn_audit_management_endpoint_ssh_trust();
GRANT SELECT, INSERT, UPDATE ON management_endpoint_ssh_trust TO ui2_app;
REVOKE DELETE ON management_endpoint_ssh_trust FROM ui2_app;
