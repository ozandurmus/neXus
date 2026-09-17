-- V26__custom_rbac_roles.sql
-- Custom RBAC Roles feature (docs/design/UI2_0_CUSTOM_RBAC_ROLES.md)

CREATE TABLE rbac_roles (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    token_string TEXT NOT NULL UNIQUE,
    description TEXT,
    is_system BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE rbac_permissions (
    id UUID PRIMARY KEY,
    action TEXT NOT NULL UNIQUE
);

CREATE TABLE rbac_role_permissions (
    role_id UUID NOT NULL REFERENCES rbac_roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES rbac_permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- Seed legacy roles
INSERT INTO rbac_roles (id, name, token_string, description, is_system) VALUES
    (gen_random_uuid(), 'Viewer', 'role:viewer', 'Read-only access', true),
    (gen_random_uuid(), 'Operator', 'role:operator', 'Operational access', true),
    (gen_random_uuid(), 'Onboarding Admin', 'role:onboarding_admin', 'Device onboarding', true),
    (gen_random_uuid(), 'Backup Admin', 'role:backup_admin', 'Backup administration', true),
    (gen_random_uuid(), 'Compliance Admin', 'role:compliance_admin', 'Compliance rule management', true),
    (gen_random_uuid(), 'Security Admin', 'role:security_admin', 'Full security administration', true);

-- Drop the closed enum constraint from role_bindings
ALTER TABLE role_bindings DROP CONSTRAINT chk_role_bindings_role_token;

-- Add foreign key from role_bindings to rbac_roles (optional depending on how we enforce, but good practice if token_string matches)
-- Wait, role_bindings has role_token TEXT. Let's link it implicitly or add FK? 
-- The design doc says: "Existing RoleBindingRecord entries will continue to function since the token_string will match the newly migrated rows."
-- So we just leave role_token as is.

GRANT SELECT, INSERT, UPDATE, DELETE ON rbac_roles TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON rbac_permissions TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON rbac_role_permissions TO ui2_app;
