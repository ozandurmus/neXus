-- V19 — first-boot root identity marker (NXS-LOCAL-0178, 14J RT-3).
-- The opaque local identity id, not a name or client input, identifies root.
CREATE TABLE root_identity (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    local_identity_id TEXT NOT NULL UNIQUE REFERENCES local_credentials(local_identity_id)
);

GRANT SELECT, INSERT ON root_identity TO ui2_app;
