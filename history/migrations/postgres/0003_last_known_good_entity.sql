-- Formalizes utils/evidence_backend.py's PostgresLastKnownGoodBackend
-- (_LAST_KNOWN_GOOD_SCHEMA). DEV.3.3.
CREATE TABLE IF NOT EXISTS last_known_good_entity (
    source                     TEXT NOT NULL,
    entity_key                 TEXT NOT NULL,
    item_json                  JSONB NOT NULL,
    last_successful_collection TIMESTAMPTZ,
    updated_at                 TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source, entity_key)
);
