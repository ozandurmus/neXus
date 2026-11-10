-- V204 -- Advisory store, part 1 (docs/design/ADVISORY_MATCHING_CONTRACT.md section 5, FROZEN 2026-11-10).
--
-- Four tables of public vendor security advisories, written only by the ingest endpoint of ui2-service (section 4) from
-- documents the advisory-fetch workload hands over. They hold public data only: a title, a version rule, a date, a
-- link. They never hold a raw document (its SHA-256 and address are stored), a device name, a version or a count of
-- devices. Verdicts are not stored; they are computed when the view is read (section 5).
--
-- No audit trigger: machine-written rows, never entered by a person (the same rule as V200).
-- Every enumeration, length and code word the contract names is a CHECK, because the rows come from untrusted
-- documents (rule 7) and a value outside the contract must fail loudly instead of being stored.

-- One row per advisory. advisory_id is "<source>:<vendor id>", for example paloalto:CVE-2024-3400.
CREATE TABLE IF NOT EXISTS advisory (
    advisory_id    TEXT        PRIMARY KEY CHECK (length(advisory_id) BETWEEN 3 AND 240),
    source         TEXT        NOT NULL CHECK (source IN ('paloalto', 'fortinet', 'cisco', 'cisa_kev')),
    title          TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
    severity       TEXT        NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'NONE', 'UNKNOWN')),
    cvss_score     NUMERIC(3,1) CHECK (cvss_score IS NULL OR (cvss_score >= 0 AND cvss_score <= 10)),
    cve_ids        TEXT[]      NOT NULL DEFAULT '{}',
    published_on   DATE,
    updated_on     DATE,
    source_url     TEXT        NOT NULL CHECK (source_url LIKE 'https://%' AND length(source_url) <= 2000),
    parse_state    TEXT        NOT NULL CHECK (parse_state IN ('PARSED', 'NEEDS_REVIEW')),
    parse_note     TEXT        CHECK (parse_note IS NULL OR parse_note ~ '^[A-Z0-9_]{1,40}$'),
    content_sha256 TEXT        NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    loaded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (advisory_id LIKE source || ':%')
);
CREATE INDEX IF NOT EXISTS advisory_source ON advisory (source);

-- The normalised version rules of one advisory for one product and train (train '*' = the whole product).
-- rule holds the JSON the rule codec wrote, as text; the service reads it back with the same codec.
CREATE TABLE IF NOT EXISTS advisory_product_rule (
    advisory_id TEXT NOT NULL REFERENCES advisory(advisory_id) ON DELETE CASCADE,
    product_key TEXT NOT NULL CHECK (product_key ~ '^[a-z0-9_]{1,30}:[a-z0-9_-]{1,40}$'),
    train       TEXT NOT NULL CHECK (train ~ '^[A-Za-z0-9._*-]{1,40}$'),
    rule        TEXT NOT NULL CHECK (length(rule) BETWEEN 2 AND 200000),
    fixed_text  TEXT CHECK (fixed_text IS NULL OR length(fixed_text) <= 200),
    PRIMARY KEY (advisory_id, product_key, train)
);
CREATE INDEX IF NOT EXISTS advisory_product_rule_product ON advisory_product_rule (product_key);

-- The known-exploited catalog (CISA): the exploited flag of section 5. Replaced as a set on every load.
CREATE TABLE IF NOT EXISTS known_exploited_cve (
    cve_id         TEXT        PRIMARY KEY CHECK (cve_id ~ '^CVE-[0-9]{4}-[0-9]{4,10}$'),
    date_added     DATE        NOT NULL,
    ransomware_use BOOLEAN     NOT NULL,
    loaded_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per fetch run and source (section 4). An open run has no outcome and no end time.
CREATE TABLE IF NOT EXISTS advisory_feed_run (
    run_id                   TEXT        NOT NULL CHECK (run_id ~ '^[A-Za-z0-9_-]{8,64}$'),
    source                   TEXT        NOT NULL CHECK (source IN ('paloalto', 'fortinet', 'cisco', 'cisa_kev')),
    started_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at                 TIMESTAMPTZ,
    outcome                  TEXT        CHECK (outcome IS NULL OR outcome IN ('OK', 'PARTIAL', 'FAILED')),
    documents_received       INTEGER     NOT NULL DEFAULT 0 CHECK (documents_received >= 0),
    -- entries the parser could not read at all, plus documents that yielded nothing
    rejected                 INTEGER     NOT NULL DEFAULT 0 CHECK (rejected >= 0),
    advisories_stored        INTEGER     NOT NULL DEFAULT 0 CHECK (advisories_stored >= 0),
    advisories_needing_review INTEGER    NOT NULL DEFAULT 0 CHECK (advisories_needing_review >= 0),
    documents_fetched        INTEGER     CHECK (documents_fetched IS NULL OR documents_fetched >= 0),
    documents_failed         INTEGER     CHECK (documents_failed IS NULL OR documents_failed >= 0),
    reason                   TEXT        CHECK (reason IS NULL OR reason ~ '^[A-Z0-9_]{1,40}$'),
    PRIMARY KEY (run_id, source),
    CHECK ((outcome IS NULL) = (ended_at IS NULL))
);
CREATE INDEX IF NOT EXISTS advisory_feed_run_source_started ON advisory_feed_run (source, started_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON advisory, advisory_product_rule, known_exploited_cve, advisory_feed_run TO ui2_app;
