-- V213 -- Advisory store: the Check Point source (docs/design/ADVISORY_MATCHING_CONTRACT.md, amendment A-2026-11-13).
--
-- 1. The source key 'checkpoint' joins the closed source lists of advisory and advisory_feed_run.
-- 2. checkpoint_jumbo_statement: one row per train, Jumbo take and CVE id that a Check Point Jumbo Hotfix Accumulator
--    "All Resolved Issues" page names. It is the vendor's statement that this take of this train carries a resolution
--    of (kind FIX) or a response to (kind MENTION) the CVE. Written only by the ingest endpoint from the public page, and
--    replaced as a set per train when a page is read in full. The evidence column holds the one table line the statement
--    was read from (public vendor text, at most 300 characters); the raw page is never stored.
--
-- Public data only. No audit trigger: machine-written rows, never entered by a person (the same rule as V205).

ALTER TABLE advisory DROP CONSTRAINT IF EXISTS advisory_source_check;
ALTER TABLE advisory ADD CONSTRAINT advisory_source_check
    CHECK (source IN ('paloalto', 'fortinet', 'cisco', 'cisa_kev', 'checkpoint'));

ALTER TABLE advisory_feed_run DROP CONSTRAINT IF EXISTS advisory_feed_run_source_check;
ALTER TABLE advisory_feed_run ADD CONSTRAINT advisory_feed_run_source_check
    CHECK (source IN ('paloalto', 'fortinet', 'cisco', 'cisa_kev', 'checkpoint'));

CREATE TABLE IF NOT EXISTS checkpoint_jumbo_statement (
    train          TEXT        NOT NULL CHECK (train ~ '^R[0-9]{2}(\.[0-9]{1,2})?$'),
    take           INTEGER     NOT NULL CHECK (take BETWEEN 1 AND 100000),
    cve_id         TEXT        NOT NULL CHECK (cve_id ~ '^CVE-[0-9]{4}-[0-9]{4,10}$'),
    kind           TEXT        NOT NULL CHECK (kind IN ('FIX', 'MENTION')),
    product_text   TEXT        CHECK (product_text IS NULL OR length(product_text) BETWEEN 1 AND 80),
    sk_ref         TEXT        CHECK (sk_ref IS NULL OR sk_ref ~ '^sk[0-9]{1,10}$'),
    evidence       TEXT        NOT NULL CHECK (length(evidence) BETWEEN 1 AND 300),
    source_url     TEXT        NOT NULL CHECK (source_url LIKE 'https://sc1.checkpoint.com/%' AND length(source_url) <= 300),
    content_sha256 TEXT        NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    loaded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (train, take, cve_id, kind)
);
CREATE INDEX IF NOT EXISTS checkpoint_jumbo_statement_cve ON checkpoint_jumbo_statement (cve_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON checkpoint_jumbo_statement TO ui2_app;
