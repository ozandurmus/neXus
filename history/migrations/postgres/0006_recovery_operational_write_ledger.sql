-- Formalizes utils/evidence_backend.py's PostgresOperationalWriteLedgerBackend
-- (_OPERATIONAL_LEDGER_SQL_SCHEMA), RB.3b.
CREATE TABLE IF NOT EXISTS recovery_operational_write_ledger (
    id            BIGSERIAL PRIMARY KEY,
    entity_id     TEXT        NOT NULL,
    command_class TEXT        NOT NULL,
    executed_at   TIMESTAMPTZ NOT NULL,
    outcome       TEXT        NOT NULL,
    run_id        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS recovery_opwrite_ledger_lookup_idx
    ON recovery_operational_write_ledger (entity_id, command_class, executed_at DESC);
