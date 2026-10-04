-- Formalizes utils/coordinator_backend.py's PostgresCoordinatorBackend
-- (_SCHEMA_STATEMENTS), DEV.3.2.
CREATE TABLE IF NOT EXISTS collection_job (
    job_id         TEXT PRIMARY KEY,
    vendor         TEXT NOT NULL,
    workflow_scope TEXT NOT NULL,
    budget_key     TEXT NOT NULL,
    provenance     TEXT NOT NULL,
    status         TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    admitted_at    TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    coalesced_to   TEXT,
    reason         TEXT
);
