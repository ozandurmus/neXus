-- Formalizes utils/evidence_backend.py's PostgresSchedulerStateBackend
-- (_SCHEDULER_STATE_SCHEMA). DEV.3.3.
CREATE TABLE IF NOT EXISTS scheduler_state (
    workflow          TEXT PRIMARY KEY,
    last_completed_at TIMESTAMPTZ NOT NULL
);
