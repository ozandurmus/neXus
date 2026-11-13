-- V214 -- Lifecycle catalog from vendor publications (docs/design/LIFECYCLE_CATALOG_SOURCES_CONTRACT.md, 2026-11-13).
--
-- The operator catalog of V144 (lifecycle_catalog) stays as it is: rows typed or imported as CSV by a security_admin.
-- This migration adds the vendor side next to it:
--   lifecycle_vendor_entry    one row per product (model or software train) read from a vendor publication, either
--                             fetched online by the advisory-fetch workload (origin FEED) or read from a vendor page an
--                             operator saved and uploaded (origin OPERATOR_PAGE)
--   lifecycle_catalog_upload  one row per operator page upload: who, which page form, the page hash and the counts.
--                             Audited (a person acts), like lifecycle_catalog.
--   lifecycle_feed_run        one row per online fetch run and source, as advisory_feed_run.
--
-- Public data only: model and version-train names, dates, a link, a hash. Never a raw document, a device, a serial, a
-- version of a device or a count of devices. A date the parser could not read is never stored: the entry is kept as
-- NEEDS_REVIEW with a code word and its dates empty.
--
-- lifecycle_vendor_entry and lifecycle_feed_run have no audit trigger: the FEED rows are machine-written (the V205 rule),
-- and every OPERATOR_PAGE row is tied by upload_id to an audited lifecycle_catalog_upload row written in the same
-- transaction. Every enumeration and code word is a CHECK, because the rows come from untrusted documents.

CREATE TABLE IF NOT EXISTS lifecycle_catalog_upload (
    upload_id              TEXT        PRIMARY KEY CHECK (upload_id ~ '^[A-Za-z0-9_-]{8,64}$'),
    page_form              TEXT        NOT NULL CHECK (page_form IN ('cisco_eol_bulletin', 'paloalto_software_eol',
                                                                     'paloalto_hardware_eol', 'checkpoint_lifecycle')),
    vendor                 TEXT        NOT NULL CHECK (vendor IN ('CHECKPOINT', 'PALOALTO', 'CISCO_ASA')),
    content_sha256         TEXT        NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    entries_parsed         INTEGER     NOT NULL CHECK (entries_parsed >= 0),
    entries_needing_review INTEGER     NOT NULL CHECK (entries_needing_review >= 0),
    rows_skipped           INTEGER     NOT NULL CHECK (rows_skipped >= 0),
    uploaded_by            TEXT        NOT NULL,
    uploaded_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS lifecycle_vendor_entry (
    entry_id                TEXT        PRIMARY KEY CHECK (length(entry_id) BETWEEN 3 AND 300),
    origin                  TEXT        NOT NULL CHECK (origin IN ('FEED', 'OPERATOR_PAGE')),
    source                  TEXT        NOT NULL CHECK (source IN ('cisco_eol', 'paloalto_eol', 'checkpoint_lifecycle')),
    upload_id               TEXT        REFERENCES lifecycle_catalog_upload(upload_id) ON DELETE CASCADE,
    vendor                  TEXT        NOT NULL CHECK (vendor IN ('CHECKPOINT', 'PALOALTO', 'CISCO_ASA')),
    kind                    TEXT        NOT NULL CHECK (kind IN ('HARDWARE', 'SOFTWARE')),
    -- the normalised match key (LifecycleKeys): a model key such as FPR4115, PA-3220, 6600, or a train key such as
    -- R81.20, PAN-OS 11.1, ASA 9.18
    product_key             TEXT        NOT NULL CHECK (product_key ~ '^[A-Za-z0-9 ._/-]{1,60}$'),
    -- the product as the vendor published it, cut to 200 characters
    product_label           TEXT        NOT NULL CHECK (length(product_label) BETWEEN 1 AND 200),
    end_of_sale             DATE,
    end_of_engineering      DATE,
    end_of_support          DATE,
    end_of_security_support DATE,
    end_of_extended_support DATE,
    -- the date fields the vendor gave as a month only (stored as the first day of that month, shown as the month)
    month_only_fields       TEXT[]      NOT NULL DEFAULT '{}'
        CHECK (month_only_fields <@ ARRAY['end_of_sale', 'end_of_engineering', 'end_of_support',
                                          'end_of_security_support', 'end_of_extended_support']::TEXT[]),
    parse_state             TEXT        NOT NULL CHECK (parse_state IN ('PARSED', 'NEEDS_REVIEW')),
    parse_note              TEXT        CHECK (parse_note IS NULL OR parse_note ~ '^[A-Z0-9_]{1,40}$'),
    -- the publication inside the source: a Cisco bulletin id (EOL15698) or a page section code word
    document_ref            TEXT        NOT NULL CHECK (document_ref ~ '^[A-Za-z0-9_.-]{1,80}$'),
    source_url              TEXT        NOT NULL CHECK (source_url LIKE 'https://%' AND length(source_url) <= 2000),
    content_sha256          TEXT        NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    -- the last time a fetch run or an upload read this entry from the vendor's publication; the age rule uses it
    confirmed_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((origin = 'OPERATOR_PAGE') = (upload_id IS NOT NULL)),
    CHECK (parse_state = 'PARSED' OR (end_of_sale IS NULL AND end_of_engineering IS NULL AND end_of_support IS NULL
                                      AND end_of_security_support IS NULL AND end_of_extended_support IS NULL))
);
CREATE INDEX IF NOT EXISTS lifecycle_vendor_entry_match ON lifecycle_vendor_entry (vendor, kind, product_key);
CREATE INDEX IF NOT EXISTS lifecycle_vendor_entry_document ON lifecycle_vendor_entry (origin, source, document_ref);

CREATE TABLE IF NOT EXISTS lifecycle_feed_run (
    run_id                 TEXT        NOT NULL CHECK (run_id ~ '^[A-Za-z0-9_-]{8,64}$'),
    source                 TEXT        NOT NULL CHECK (source IN ('cisco_eol')),
    started_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at               TIMESTAMPTZ,
    outcome                TEXT        CHECK (outcome IS NULL OR outcome IN ('OK', 'PARTIAL', 'FAILED')),
    documents_received     INTEGER     NOT NULL DEFAULT 0 CHECK (documents_received >= 0),
    -- documents that were not a lifecycle publication of a covered product (counted, not an error)
    documents_ignored      INTEGER     NOT NULL DEFAULT 0 CHECK (documents_ignored >= 0),
    rejected               INTEGER     NOT NULL DEFAULT 0 CHECK (rejected >= 0),
    entries_stored         INTEGER     NOT NULL DEFAULT 0 CHECK (entries_stored >= 0),
    entries_needing_review INTEGER     NOT NULL DEFAULT 0 CHECK (entries_needing_review >= 0),
    documents_fetched      INTEGER     CHECK (documents_fetched IS NULL OR documents_fetched >= 0),
    documents_failed       INTEGER     CHECK (documents_failed IS NULL OR documents_failed >= 0),
    reason                 TEXT        CHECK (reason IS NULL OR reason ~ '^[A-Z0-9_]{1,40}$'),
    PRIMARY KEY (run_id, source),
    CHECK ((outcome IS NULL) = (ended_at IS NULL))
);
CREATE INDEX IF NOT EXISTS lifecycle_feed_run_source_started ON lifecycle_feed_run (source, started_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON lifecycle_catalog_upload, lifecycle_vendor_entry, lifecycle_feed_run TO ui2_app;

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('lifecycle_catalog_upload', 'uploaded_by', 2, 'uploader identity')
ON CONFLICT (table_name, column_name) DO NOTHING;
DROP TRIGGER IF EXISTS trg_audit_lifecycle_catalog_upload ON lifecycle_catalog_upload;
CREATE TRIGGER trg_audit_lifecycle_catalog_upload
    AFTER INSERT OR UPDATE OR DELETE ON lifecycle_catalog_upload
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('upload_id');
