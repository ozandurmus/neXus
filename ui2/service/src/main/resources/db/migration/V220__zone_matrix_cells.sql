-- V2xx-PLACEHOLDER -- Zone matrix, delivery step 2: matrix edits in the revision history, reference confirmation
-- (docs/design/ZONE_MATRIX_CONTRACT.md sections 3.4, 3.5, 4, 6.1 and 8.1, FROZEN 2026-11-13).
--
-- PLACEHOLDER: this file sits outside db/migration until the ship order assigns its version number; it is then moved
-- to db/migration/V<n>__zone_matrix_cells.sql unchanged. It follows V216 (zone_matrix_versions).
--
-- The matrix itself (version default, cells, service sets, template origin) lives in the version content written by
-- the service (canonical JSON, schema 2), so no new content column is needed. This migration:
--   1. widens the revision change kinds to the step-2 edits;
--   2. maps a reference confirmation revision to its own audit action, zone_matrix_reference_confirm (section 3.5:
--      "Publish, reference confirmation, export, raw view and address lookup are audit events"); after_state keeps the
--      V216 allowlist of ids, change kind and content hash;
--   3. adds the append-only zone_matrix_reference_confirmation table of section 8.1: the cell (pair of zone ids), the
--      status, who confirmed it and when, and the hash of the cell they confirmed.

ALTER TABLE zone_matrix_revision DROP CONSTRAINT zone_matrix_revision_change_kind_check;
ALTER TABLE zone_matrix_revision ADD CONSTRAINT zone_matrix_revision_change_kind_check CHECK (change_kind IN
    ('DRAFT_CREATE', 'ZONE_CREATE', 'ZONE_UPDATE', 'ZONE_DELETE', 'DRAFT_DISCARD', 'PUBLISH',
     'CELL_SET', 'CELL_CLEAR', 'DEFAULT_SET', 'SERVICE_SET_SAVE', 'SERVICE_SET_DELETE', 'TEMPLATE_IMPORT',
     'REFERENCE_CONFIRM'));

CREATE OR REPLACE FUNCTION fn_audit_capture_zone_matrix_revision() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, after_state)
    VALUES ('zone_matrix_revision', NEW.version_id || ':' || NEW.revision::TEXT, 'INSERT', NEW.actor,
            CASE NEW.change_kind WHEN 'PUBLISH' THEN 'zone_matrix_publish'
                                 WHEN 'REFERENCE_CONFIRM' THEN 'zone_matrix_reference_confirm'
                                 ELSE 'zone_matrix_edit' END,
            jsonb_build_object('version_id', NEW.version_id, 'revision', NEW.revision, 'change_kind', NEW.change_kind,
                               'zone_id', NEW.zone_id, 'content_sha256', NEW.content_sha256,
                               'rationale_given', NEW.rationale IS NOT NULL));
    RETURN NEW;
END;
$$;

-- One row per confirmation, written in the same transaction as its REFERENCE_CONFIRM revision (the foreign key ties
-- them). A later change to the cell resets its status in the draft content; the confirmation row stays as history.
CREATE TABLE zone_matrix_reference_confirmation (
    confirmation_id  BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id       TEXT        NOT NULL,
    revision         BIGINT      NOT NULL,
    source_zone      TEXT        NOT NULL CHECK (source_zone = 'UNASSIGNED'
                         OR source_zone ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    destination_zone TEXT        NOT NULL CHECK (destination_zone = 'UNASSIGNED'
                         OR destination_zone ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    status           TEXT        NOT NULL CHECK (status = 'CONFIRMED'),
    cell_sha256      TEXT        NOT NULL CHECK (cell_sha256 ~ '^[0-9a-f]{64}$'),
    confirmed_by     TEXT        NOT NULL CHECK (length(confirmed_by) BETWEEN 1 AND 512),
    confirmed_at     TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (version_id, revision) REFERENCES zone_matrix_revision (version_id, revision)
);
CREATE INDEX zone_matrix_reference_confirmation_version ON zone_matrix_reference_confirmation (version_id, revision);
CREATE TRIGGER tr_zone_matrix_reference_confirmation_append_only
    BEFORE UPDATE OR DELETE ON zone_matrix_reference_confirmation
    FOR EACH ROW EXECUTE FUNCTION fn_zone_matrix_append_only();

GRANT SELECT, INSERT ON zone_matrix_reference_confirmation TO ui2_app;
