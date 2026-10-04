-- Formalizes utils/evidence_backend.py's PostgresActionRecordBackend
-- (_ACTION_RECORD_SCHEMA), RB.3b.
CREATE TABLE IF NOT EXISTS ha_action_record (
    action_id             TEXT PRIMARY KEY,
    operational_entity_id TEXT NOT NULL,
    state                 TEXT NOT NULL,
    created_at            TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    acknowledged_at       TIMESTAMPTZ,
    record_json           JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS ha_action_record_entity_idx
    ON ha_action_record (operational_entity_id);
