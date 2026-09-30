-- PO 2026-09-30: TOFU for HTTPS appliances, shared by discovery and device contacts. PAN is excluded.
CREATE TABLE https_endpoint_cert_trust (
    trust_entry_id TEXT PRIMARY KEY,
    management_address TEXT NOT NULL CHECK (length(management_address) BETWEEN 1 AND 253),
    management_port INTEGER NOT NULL CHECK (management_port BETWEEN 1 AND 65535),
    fingerprint_sha256 TEXT NOT NULL CHECK (fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    subject_cn TEXT NOT NULL,
    issuer_cn TEXT NOT NULL,
    not_after TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'PENDING', 'SUPERSEDED')),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    authorized_by TEXT,
    authorized_at TIMESTAMPTZ,
    CHECK (status <> 'ACTIVE' OR (authorized_by IS NOT NULL AND authorized_at IS NOT NULL)),
    CHECK (status <> 'PENDING' OR (authorized_by IS NULL AND authorized_at IS NULL))
);
CREATE UNIQUE INDEX https_endpoint_cert_trust_active
    ON https_endpoint_cert_trust(management_address, management_port) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX https_endpoint_cert_trust_pending
    ON https_endpoint_cert_trust(management_address, management_port, fingerprint_sha256) WHERE status = 'PENDING';
ALTER TABLE devices ADD COLUMN https_certificate_strict BOOLEAN NOT NULL DEFAULT false;

CREATE FUNCTION fn_audit_https_endpoint_cert_trust() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    actor TEXT := current_setting('app.actor_fingerprint', true);
    action TEXT := current_setting('app.action_id', true);
BEGIN
    IF actor IS NULL OR actor = '' OR action IS NULL OR action = '' THEN
        RAISE EXCEPTION 'audit_context_missing';
    END IF;
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'trust_history_immutable'; END IF;
    IF TG_OP = 'INSERT' AND (action <> 'https_certificate_observe'
        OR NEW.status NOT IN ('ACTIVE', 'PENDING')
        OR (NEW.status = 'ACTIVE' AND NEW.authorized_by IS DISTINCT FROM actor)) THEN
        RAISE EXCEPTION 'trust_authorization_required';
    END IF;
    IF TG_OP = 'UPDATE' AND (action <> 'https_certificate_accept'
        OR OLD.status NOT IN ('ACTIVE', 'PENDING')
        OR NOT (NEW.status = 'SUPERSEDED' OR (OLD.status = 'PENDING' AND NEW.status = 'ACTIVE'
            AND NEW.authorized_by = actor AND NEW.authorized_at IS NOT NULL))
        OR (NEW.status = 'SUPERSEDED' AND (NEW.authorized_by IS DISTINCT FROM OLD.authorized_by
            OR NEW.authorized_at IS DISTINCT FROM OLD.authorized_at))
        OR (to_jsonb(OLD) - 'status' - 'authorized_by' - 'authorized_at') IS DISTINCT FROM
           (to_jsonb(NEW) - 'status' - 'authorized_by' - 'authorized_at')) THEN
        RAISE EXCEPTION 'trust_history_immutable';
    END IF;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, before_state, after_state)
        VALUES ('https_endpoint_cert_trust', NEW.trust_entry_id, TG_OP, actor, action,
            CASE WHEN TG_OP = 'UPDATE' THEN jsonb_build_object('status', OLD.status) ELSE NULL END,
            jsonb_build_object('trust_entry_id', NEW.trust_entry_id, 'status', NEW.status,
                'first_seen_at', NEW.first_seen_at, 'authorized_at', NEW.authorized_at));
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fn_audit_https_endpoint_cert_trust() FROM PUBLIC;
CREATE TRIGGER trg_audit_https_endpoint_cert_trust BEFORE INSERT OR UPDATE OR DELETE ON https_endpoint_cert_trust
    FOR EACH ROW EXECUTE FUNCTION fn_audit_https_endpoint_cert_trust();
GRANT SELECT, INSERT, UPDATE, DELETE ON https_endpoint_cert_trust TO ui2_app;
