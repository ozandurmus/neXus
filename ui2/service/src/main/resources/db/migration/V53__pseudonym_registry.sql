-- V53 -- collision-free AIView pseudonyms (backlog aiview_masking_leak_audit, F20, 2026-09-23).
-- A cluster pseudonym is one of 35 words x digits 1..9 (315 names); with ~40 clusters two real clusters
-- landed on the same name and merged in every aiview screen. Each real name now claims its computed
-- candidate, or the next free one, once; the claim is stored so it survives restarts. The raw name is never
-- stored: raw_key is an HMAC of it under the pseudonymizer's own key.
CREATE TABLE pseudonym_registry (
    kind        TEXT        NOT NULL CHECK (kind IN ('cluster', 'device')),
    raw_key     TEXT        NOT NULL,
    pseudonym   TEXT        NOT NULL,
    claimed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, raw_key),
    UNIQUE (kind, pseudonym)
);

GRANT SELECT, INSERT ON pseudonym_registry TO ui2_app;
