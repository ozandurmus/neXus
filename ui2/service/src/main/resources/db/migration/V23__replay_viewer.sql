-- C9 v1: exclusive session sub-state and durable, server-only projection key.
ALTER TABLE role_bindings DROP CONSTRAINT chk_role_bindings_role_token;
ALTER TABLE role_bindings ADD CONSTRAINT chk_role_bindings_role_token CHECK (role_token IN (
    'role:viewer', 'role:operator', 'role:onboarding_admin', 'role:backup_admin',
    'role:compliance_admin', 'role:security_admin', 'role:replay_viewer'));

ALTER TABLE sessions ADD COLUMN replay_viewer BOOLEAN NOT NULL DEFAULT false;
-- Activation inserts a distinct replay session atomically with supersession.
-- Deactivation ends that session; it never restores write authority in place.
CREATE TABLE replay_projection_key (
    key_id TEXT PRIMARY KEY CHECK (key_id = 'replay-v1'),
    encrypted_key BYTEA NOT NULL,
    wrapping_key_id TEXT NOT NULL
);
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('replay_projection_key', 'encrypted_key', 1, 'server-only wrapped HMAC key');
CREATE TRIGGER trg_audit_replay_projection_key
    AFTER INSERT OR UPDATE OR DELETE ON replay_projection_key
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('key_id');
-- No v1 rotation/deletion capability, including through ui2_app.
GRANT SELECT, INSERT ON replay_projection_key TO ui2_app;
