-- Compatibility: legacy JSON rows remain readable until a successful new generation replaces them.
CREATE TABLE cp_policy_json_chunk (
    generation TEXT NOT NULL,
    source_id TEXT NOT NULL,
    domain_ref TEXT NOT NULL,
    object_type TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('INVENTORY', 'DOMAIN')),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    payload BYTEA NOT NULL CHECK (octet_length(payload) <= 1048576),
    plain_bytes INTEGER NOT NULL CHECK (plain_bytes BETWEEN 0 AND 1048576),
    PRIMARY KEY (generation, ordinal)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON cp_policy_json_chunk TO ui2_app;
ALTER TABLE cp_policy_object_inventory ADD COLUMN chunk_generation TEXT,
    ADD COLUMN chunk_count INTEGER CHECK (chunk_count >= 0);
ALTER TABLE cp_policy_domain_run ADD COLUMN chunk_generation TEXT,
    ADD COLUMN chunk_count INTEGER CHECK (chunk_count >= 0);
ALTER TABLE cp_policy_object_inventory ADD CONSTRAINT cp_inventory_chunk_manifest
    CHECK ((chunk_generation IS NULL) = (chunk_count IS NULL));
ALTER TABLE cp_policy_domain_run ADD CONSTRAINT cp_domain_chunk_manifest
    CHECK ((chunk_generation IS NULL) = (chunk_count IS NULL));
