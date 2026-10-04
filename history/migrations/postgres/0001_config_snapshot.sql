-- Formalizes the ad-hoc schema previously created inline by
-- utils/evidence_backend.py's PostgresConfigSnapshotBackend
-- (_CONFIG_SNAPSHOT_SCHEMA). CAS metadata index, DEV.3.3.
CREATE TABLE IF NOT EXISTS config_snapshot (
    snapshot_id       TEXT PRIMARY KEY,
    source            TEXT NOT NULL,
    entity_id         TEXT NOT NULL,
    artifact_type     TEXT,
    device            TEXT,
    management_ip     TEXT,
    collected_at      TIMESTAMPTZ,
    method            TEXT,
    status            TEXT,
    sha256            TEXT,
    size_bytes        BIGINT,
    collector_version TEXT,
    change_state      TEXT,
    previous_sha256   TEXT,
    previous_snapshot TEXT,
    object_path       TEXT,
    metadata_json     JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS config_snapshot_latest_idx
    ON config_snapshot (source, entity_id, artifact_type, snapshot_id DESC);

CREATE INDEX IF NOT EXISTS config_snapshot_sha256_idx ON config_snapshot (sha256);
