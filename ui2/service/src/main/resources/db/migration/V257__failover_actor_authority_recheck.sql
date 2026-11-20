-- V257 -- failover actor authority recheck at worker admission (backlog failover_approver_role_recheck_at_worker).
-- Schema only, no device command.
--
-- Why a migration is needed: the worker re-checks, before any device contact, that the initiator and the approver of a
-- failover run, a resumed return or a Palo Alto config sync still hold the role that allowed them to act and are still
-- enabled. The role decision itself stays the service's (RbacEvaluator); the worker cannot repeat it, because a local
-- identity's role binding names the identity only inside an encrypted group reference and the worker's database role
-- must never read role_bindings, local_credentials or root_identity (DATABASE_ROLES design, DatabaseRoleGrantMatrix
-- test). So:
--   1. failover_actor_authority: the service records, in the same transaction as the request or approval, WHICH
--      authority admitted the actor (ui2_local / ui2_ldap / ui2_root) and the role binding it matched. Opaque ids only;
--      insert-only (no UPDATE / DELETE grant, a guard trigger, audited by trg_audit_*).
--   2. ui2_failover_actor_standing(actor, authority, binding_id): SECURITY DEFINER, answers one closed word -- HELD,
--      ROLE_NOT_HELD, IDENTITY_DISABLED, IDENTITY_UNKNOWN or NOT_RECORDED -- and nothing else. The binding must still
--      be active and grant security_admin or operation_admin; a local identity (or its probe actor) must still exist
--      and be enabled; a root authority must still be the root identity.
--   3. ui2_failover_same_person(a, b): SECURITY DEFINER, true when the two actors are the same local identity (its
--      normal and probe actors included) or the same actor string.
-- Neither function returns an identity value. EXECUTE is revoked from PUBLIC and granted to ui2_app and the module
-- roles that admit failover work. DDL uses IF NOT EXISTS / CREATE OR REPLACE / DROP ... IF EXISTS so the dry run can
-- execute the file twice inside one rolled-back transaction.

CREATE TABLE IF NOT EXISTS failover_actor_authority (
    authority_id TEXT PRIMARY KEY,
    record_kind TEXT NOT NULL CHECK (record_kind IN ('failover_approval', 'failover_resume', 'pan_config_sync')),
    record_id TEXT NOT NULL,
    party TEXT NOT NULL CHECK (party IN ('INITIATOR', 'APPROVER')),
    actor TEXT NOT NULL,
    authority TEXT NOT NULL CHECK (authority IN ('ui2_local', 'ui2_ldap', 'ui2_root')),
    binding_id TEXT,
    role_name TEXT NOT NULL CHECK (role_name IN ('role:security_admin', 'role:operation_admin')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((authority = 'ui2_root') = (binding_id IS NULL))
);
ALTER TABLE failover_actor_authority
    DROP CONSTRAINT IF EXISTS failover_actor_authority_record_kind_record_id_party_key;
CREATE INDEX IF NOT EXISTS idx_failover_actor_authority_lookup
    ON failover_actor_authority (record_kind, record_id, party, recorded_at DESC, authority_id DESC);

CREATE OR REPLACE FUNCTION ui2_failover_actor_authority_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'ACTOR_AUTHORITY_IMMUTABLE';
END;
$$;
DROP TRIGGER IF EXISTS failover_actor_authority_guard ON failover_actor_authority;
CREATE TRIGGER failover_actor_authority_guard BEFORE UPDATE OR DELETE ON failover_actor_authority
    FOR EACH ROW EXECUTE FUNCTION ui2_failover_actor_authority_guard();
DROP TRIGGER IF EXISTS trg_audit_failover_actor_authority ON failover_actor_authority;
CREATE TRIGGER trg_audit_failover_actor_authority AFTER INSERT OR UPDATE OR DELETE ON failover_actor_authority
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('authority_id');

-- The standing of one actor now. A local identity is matched through its actor fingerprint exactly as
-- LocalPrincipalFingerprint computes it: the first 12 hex digits of SHA-256 over 'local:' || id (':probe' appended for
-- the identity's diagnostic-probe actor).
CREATE OR REPLACE FUNCTION ui2_failover_actor_standing(p_actor TEXT, p_authority TEXT, p_binding_id TEXT)
RETURNS TEXT LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
    v_local TEXT;
    v_enabled BOOLEAN;
BEGIN
    IF p_actor IS NULL OR btrim(p_actor) = '' THEN
        RETURN 'IDENTITY_UNKNOWN';
    END IF;
    SELECT c.local_identity_id, c.enabled INTO v_local, v_enabled
      FROM local_credentials c
     WHERE left(encode(sha256(convert_to('local:' || c.local_identity_id, 'UTF8')), 'hex'), 12) = p_actor
        OR left(encode(sha256(convert_to('local:' || c.local_identity_id || ':probe', 'UTF8')), 'hex'), 12) = p_actor
     ORDER BY c.local_identity_id
     LIMIT 1;
    IF v_local IS NOT NULL AND NOT v_enabled THEN
        RETURN 'IDENTITY_DISABLED';
    END IF;
    IF p_authority IS NULL THEN
        RETURN 'NOT_RECORDED';
    END IF;
    IF p_authority = 'ui2_root' THEN
        IF v_local IS NOT NULL AND EXISTS (SELECT 1 FROM root_identity r WHERE r.local_identity_id = v_local) THEN
            RETURN 'HELD';
        END IF;
        RETURN 'ROLE_NOT_HELD';
    END IF;
    IF p_authority = 'ui2_local' AND v_local IS NULL THEN
        RETURN 'IDENTITY_UNKNOWN';
    END IF;
    IF p_authority NOT IN ('ui2_local', 'ui2_ldap') OR p_binding_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM role_bindings b
             WHERE b.binding_id = p_binding_id AND b.revoked_at IS NULL
               AND b.role_token IN ('role:security_admin', 'role:operation_admin')) THEN
        RETURN 'ROLE_NOT_HELD';
    END IF;
    RETURN 'HELD';
END;
$$;

CREATE OR REPLACE FUNCTION ui2_failover_same_person(p_first TEXT, p_second TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
    v_first TEXT;
    v_second TEXT;
BEGIN
    IF p_first IS NULL OR p_second IS NULL THEN
        RETURN false;
    END IF;
    IF p_first = p_second THEN
        RETURN true;
    END IF;
    SELECT c.local_identity_id INTO v_first FROM local_credentials c
     WHERE left(encode(sha256(convert_to('local:' || c.local_identity_id, 'UTF8')), 'hex'), 12) = p_first
        OR left(encode(sha256(convert_to('local:' || c.local_identity_id || ':probe', 'UTF8')), 'hex'), 12) = p_first
     ORDER BY c.local_identity_id LIMIT 1;
    SELECT c.local_identity_id INTO v_second FROM local_credentials c
     WHERE left(encode(sha256(convert_to('local:' || c.local_identity_id, 'UTF8')), 'hex'), 12) = p_second
        OR left(encode(sha256(convert_to('local:' || c.local_identity_id || ':probe', 'UTF8')), 'hex'), 12) = p_second
     ORDER BY c.local_identity_id LIMIT 1;
    RETURN v_first IS NOT NULL AND v_first = v_second;
END;
$$;

REVOKE ALL ON FUNCTION ui2_failover_actor_standing(TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ui2_failover_same_person(TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ui2_failover_actor_standing(TEXT, TEXT, TEXT) TO ui2_app;
GRANT EXECUTE ON FUNCTION ui2_failover_same_person(TEXT, TEXT) TO ui2_app;
GRANT SELECT, INSERT ON failover_actor_authority TO ui2_app;

DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT'),
        ('ui2_scheduler', 'SELECT'),
        ('ui2_failover', 'SELECT'),
        ('ui2_worker_general', 'SELECT')
    ) AS g(role_name, privs)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.failover_actor_authority TO %I', v_rec.privs, v_rec.role_name);
            EXECUTE format('GRANT EXECUTE ON FUNCTION public.ui2_failover_actor_standing(TEXT, TEXT, TEXT) TO %I',
                v_rec.role_name);
            EXECUTE format('GRANT EXECUTE ON FUNCTION public.ui2_failover_same_person(TEXT, TEXT) TO %I', v_rec.role_name);
        END IF;
    END LOOP;
END
$grants$;
