-- Bootstrap tracking table for utils/db_migrations.py's runner. Applied
-- automatically before any other migration file; never edit after it has
-- shipped (add a new numbered file instead).
CREATE TABLE IF NOT EXISTS schema_migrations (
    version     TEXT PRIMARY KEY,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
