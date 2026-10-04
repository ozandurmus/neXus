-- Formalizes utils/evidence_backend.py's PostgresConsoleJobBackend
-- (_CONSOLE_JOB_SCHEMA), CON.2. preview_json was an M9 additive column on
-- top of an already-shipped table in the ad-hoc path; folded into the
-- initial CREATE here since no real deployment predates this migration set.
CREATE TABLE IF NOT EXISTS console_job (
    job_id               TEXT PRIMARY KEY,
    idempotency_key      TEXT NOT NULL,
    job_type             TEXT NOT NULL,
    command_class        TEXT NOT NULL,
    targets_json         JSONB NOT NULL,
    state                TEXT NOT NULL,
    requested_at         TIMESTAMPTZ NOT NULL,
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    run_id               TEXT,
    coordinator_decision TEXT,
    outcome_counts_json  JSONB,
    error_code           TEXT,
    error_summary        TEXT,
    preview_json         JSONB
);

CREATE UNIQUE INDEX IF NOT EXISTS console_job_idempotency_idx
    ON console_job (idempotency_key);

CREATE INDEX IF NOT EXISTS console_job_requested_at_idx
    ON console_job (requested_at DESC);
