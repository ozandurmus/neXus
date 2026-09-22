-- V41 -- backup archive content listing (Product Owner directive 2026-09-22:
-- "at minimum the Backbox standard" -- an operator can see what a backup
-- holds and compare two backups of the same device from the UI).
--
-- docs/design/BACKUP_ARCHIVE_CONTENT_LISTING_AND_COMPARE.md. Supersedes, for
-- the listing only, C7 DV-3's "never a structural summary for the opaque
-- Gaia archive": the summary recorded here is entry names, sizes and
-- per-entry digests -- never an entry's content. Names inside a Gaia backup
-- are system paths, not secrets; the content that would be (the account
-- database, private keys) stays inside the envelope-encrypted artefact.
--
-- Neither table carries an audit trigger: both are derived caches,
-- reproducible from the artefact itself by re-listing, and a Gaia archive
-- lists in the thousands of entries -- one audit_log row per entry would
-- bury the audit log under data it can regenerate. The artefact's own
-- manifest row (backup_artefact) stays the audited record of the backup.
-- AuditPresentationAllowlistMatchesLiveSchemaTest therefore stays untouched.

CREATE TABLE backup_artefact_content_listing (
    artefact_id   TEXT        PRIMARY KEY REFERENCES backup_artefact(artefact_id),
    state         TEXT        NOT NULL CHECK (state IN ('LISTED', 'FAILED')),
    entry_count   INTEGER     NOT NULL DEFAULT 0,
    reason        TEXT,
    listed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE backup_artefact_entry (
    artefact_id   TEXT        NOT NULL REFERENCES backup_artefact(artefact_id),
    entry_path    TEXT        NOT NULL,
    entry_type    TEXT        NOT NULL CHECK (entry_type IN ('file', 'dir', 'symlink', 'other')),
    entry_bytes   BIGINT      NOT NULL,
    entry_sha256  TEXT,
    PRIMARY KEY (artefact_id, entry_path)
);

CREATE INDEX idx_backup_artefact_entry_artefact_id ON backup_artefact_entry(artefact_id);
