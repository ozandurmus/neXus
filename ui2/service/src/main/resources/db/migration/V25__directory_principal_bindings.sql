-- C3C additive successor. Corporate DIRECTORY-POSTURE remains separately gated.
ALTER TABLE role_bindings ADD COLUMN binding_kind TEXT NOT NULL DEFAULT 'LEGACY';
ALTER TABLE role_bindings ADD COLUMN directory_profile_id TEXT;
ALTER TABLE role_bindings ADD CONSTRAINT chk_role_binding_kind
    CHECK (binding_kind IN ('LEGACY', 'DIRECTORY_GROUP', 'DIRECTORY_PRINCIPAL'));
ALTER TABLE role_bindings ADD CONSTRAINT chk_role_binding_profile
    CHECK ((binding_kind = 'LEGACY' AND directory_profile_id IS NULL)
        OR (binding_kind IN ('DIRECTORY_GROUP', 'DIRECTORY_PRINCIPAL')
            AND directory_profile_id IS NOT NULL AND length(directory_profile_id) > 0));

ALTER TABLE actor_authz_state ADD COLUMN directory_profile_id TEXT;
ALTER TABLE actor_authz_state ADD COLUMN principal_reference_encrypted BYTEA;
ALTER TABLE actor_authz_state ADD COLUMN principal_reference_key_id TEXT;
ALTER TABLE actor_authz_state ADD CONSTRAINT chk_actor_directory_proof
    CHECK ((directory_profile_id IS NULL AND principal_reference_encrypted IS NULL AND principal_reference_key_id IS NULL)
        OR (directory_profile_id IS NOT NULL AND length(directory_profile_id) > 0
            AND principal_reference_encrypted IS NOT NULL AND octet_length(principal_reference_encrypted) >= 28
            AND principal_reference_key_id IS NOT NULL AND length(principal_reference_key_id) > 0));

-- Existing cache rows carry no proven namespace/principal. Do not guess provenance.
-- No binding backfill: known local rows stay LEGACY; ambiguous directory history is unevaluable.
DELETE FROM actor_authz_state;
-- Keep the existing role_bindings audit trigger and the ephemeral cache's no-trigger exception.
-- Reader/writer activation is atomic in the release. Typed AES-GCM uses namespace/kind AAD:
-- an old reader cannot decrypt principal ciphertext as group evidence. Rollback disables
-- directory composition, expires directory caches, and retains binding/revocation history.

-- Last-session expiry/revocation deletes typed proof atomically. Takeover's SUPERSEDED
-- transition retains proof until the replacement ACTIVE row is inserted in the same transaction.
CREATE FUNCTION fn_expire_directory_authz_after_session_end() RETURNS trigger AS $$
BEGIN
    DELETE FROM actor_authz_state
        WHERE actor_fingerprint = NEW.actor_fingerprint AND directory_profile_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM sessions
              WHERE actor_fingerprint = NEW.actor_fingerprint AND state = 'ACTIVE');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_expire_directory_authz_after_session_end
    AFTER UPDATE OF state ON sessions
    FOR EACH ROW WHEN (OLD.state = 'ACTIVE' AND NEW.state IN ('EXPIRED', 'REVOKED'))
    EXECUTE FUNCTION fn_expire_directory_authz_after_session_end();
