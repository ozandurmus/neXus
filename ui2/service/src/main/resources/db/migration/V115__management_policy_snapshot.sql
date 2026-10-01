-- Latest parsed management policy only. Collectors are not enabled by this migration.
CREATE TABLE policy_snapshot (
    policy_id TEXT PRIMARY KEY,
    collected_at TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object')
);
GRANT SELECT, INSERT, UPDATE, DELETE ON policy_snapshot TO ui2_app;
