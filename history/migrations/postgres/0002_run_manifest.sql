-- Formalizes utils/evidence_backend.py's PostgresRunManifestBackend
-- (_RUN_MANIFEST_SCHEMA). DEV.3.3.
CREATE TABLE IF NOT EXISTS run_manifest (
    run_id        TEXT PRIMARY KEY,
    status        TEXT NOT NULL,
    job_id        TEXT,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL,
    manifest_json JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS run_manifest_status_idx ON run_manifest (status, updated_at DESC);
