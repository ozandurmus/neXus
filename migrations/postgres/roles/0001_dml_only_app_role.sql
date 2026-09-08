-- DEPLOY.1 least-privilege runtime role: DML-only, no DDL, no superuser.
--
-- Run this file AFTER the migrations/postgres/*.sql schema files, connected
-- as a role with GRANT authority on the target database (never as the
-- application role itself). It is idempotent and safe to re-run.
--
-- The role's password/auth is deliberately NOT set here -- a real deployment
-- must issue it out of band (secrets manager / KMS-backed credential), never
-- committed to a file. This migration only shapes privileges.
--
-- Scope note: TLS enforcement for the connection this role uses is a
-- DEPLOY.1-server concern (pg_hba.conf / connection string sslmode), not a
-- grant, and is not addressed by this file -- see project/backlog.json
-- "deploy1_database_migrations_and_roles" for the explicit remainder split.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'securityexpert_app') THEN
        CREATE ROLE securityexpert_app LOGIN;
    END IF;
END
$$;

-- No DDL, no ownership, no role management of any kind.
REVOKE ALL ON SCHEMA public FROM securityexpert_app;
GRANT USAGE ON SCHEMA public TO securityexpert_app;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM securityexpert_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO securityexpert_app;

REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM securityexpert_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO securityexpert_app;

-- Tables/sequences added by a future migration file are covered without a
-- new grants file, as long as the migration runner applies them under the
-- same owning role that ran this ALTER DEFAULT PRIVILEGES.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO securityexpert_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO securityexpert_app;
