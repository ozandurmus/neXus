-- Formalizes utils/coordinator_backend.py's PostgresCoordinatorBackend
-- (_SCHEMA_STATEMENTS), DEV.3.2. Depends on 0008_collection_job.sql.
CREATE TABLE IF NOT EXISTS collection_job_lock (
    job_id   TEXT NOT NULL REFERENCES collection_job(job_id) ON DELETE CASCADE,
    lock_key BIGINT NOT NULL,
    PRIMARY KEY (job_id, lock_key)
);

CREATE INDEX IF NOT EXISTS collection_job_lock_key_idx ON collection_job_lock(lock_key);
CREATE INDEX IF NOT EXISTS collection_job_status_idx ON collection_job(status, budget_key);
