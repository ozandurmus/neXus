-- V215 -- Zone matrix, delivery step 1: zone-model versions, draft revisions, publish, access log
-- (docs/design/ZONE_MATRIX_CONTRACT.md sections 3.1-3.5 and 8.1, FROZEN 2026-11-13).
--
-- Zones are sets of IP blocks the bank maintains (PO decision 1). A version's content is the canonical JSON the
-- service writes (ZoneMatrixContent.canonicalJson); it is stored as TEXT, not JSONB, so the stored bytes are exactly
-- the bytes the SHA-256 was computed over. Statements and cells (step 2) and evaluation runs (step 4) come later.
--
-- Audit: narrow capture only (the V204 pattern, trigger prefix tr_audit_, not trg_audit_): table, key, operation,
-- actor, action and an allowlisted after_state of ids, change kind and content hash. Zone names, blocks and looked-up
-- addresses never enter audit_log (section 3.5 and 8.2). Full draft revisions live in the append-only
-- zone_matrix_revision table; the application role may only SELECT and INSERT it.

CREATE TABLE zone_matrix_version (
    version_id          TEXT        PRIMARY KEY CHECK (version_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    number              INTEGER     UNIQUE CHECK (number IS NULL OR number >= 1),
    state               TEXT        NOT NULL CHECK (state IN ('DRAFT', 'ACTIVE', 'RETIRED', 'DISCARDED')),
    revision            BIGINT      NOT NULL CHECK (revision >= 1),
    content             TEXT        NOT NULL CHECK (length(content) BETWEEN 2 AND 20000000),
    content_sha256      TEXT        NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    based_on_version_id TEXT        REFERENCES zone_matrix_version(version_id),
    created_by          TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          TEXT        NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by        TEXT,
    published_at        TIMESTAMPTZ,
    CHECK ((state IN ('ACTIVE', 'RETIRED')) = (number IS NOT NULL AND published_by IS NOT NULL AND published_at IS NOT NULL))
);
-- At most one draft and at most one active version (section 3.5).
CREATE UNIQUE INDEX zone_matrix_version_one_draft ON zone_matrix_version ((state)) WHERE state = 'DRAFT';
CREATE UNIQUE INDEX zone_matrix_version_one_active ON zone_matrix_version ((state)) WHERE state = 'ACTIVE';

-- Published versions are immutable: the only change after publish is ACTIVE -> RETIRED with every other column equal.
-- A discarded draft is final. No version row is ever deleted.
CREATE FUNCTION fn_zone_matrix_version_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'zone matrix versions are never deleted';
    END IF;
    IF OLD.state = 'DRAFT' THEN
        IF NEW.version_id <> OLD.version_id OR NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at
                OR NEW.based_on_version_id IS DISTINCT FROM OLD.based_on_version_id OR NEW.revision < OLD.revision THEN
            RAISE EXCEPTION 'a draft keeps its identity and its revision only grows';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'RETIRED'
            AND NEW.version_id = OLD.version_id AND NEW.number = OLD.number AND NEW.revision = OLD.revision
            AND NEW.content = OLD.content AND NEW.content_sha256 = OLD.content_sha256
            AND NEW.based_on_version_id IS NOT DISTINCT FROM OLD.based_on_version_id
            AND NEW.created_by = OLD.created_by AND NEW.created_at = OLD.created_at
            AND NEW.updated_by = OLD.updated_by AND NEW.updated_at = OLD.updated_at
            AND NEW.published_by = OLD.published_by AND NEW.published_at = OLD.published_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'published or discarded zone matrix versions are immutable';
END;
$$;
CREATE TRIGGER tr_zone_matrix_version_guard
    BEFORE UPDATE OR DELETE ON zone_matrix_version
    FOR EACH ROW EXECUTE FUNCTION fn_zone_matrix_version_guard();

-- Append-only history of every draft change and every publish (section 3.5).
CREATE TABLE zone_matrix_revision (
    revision_id    BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id     TEXT        NOT NULL REFERENCES zone_matrix_version(version_id),
    revision       BIGINT      NOT NULL CHECK (revision >= 1),
    actor          TEXT        NOT NULL CHECK (length(actor) BETWEEN 1 AND 512),
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    change_kind    TEXT        NOT NULL CHECK (change_kind IN
                       ('DRAFT_CREATE', 'ZONE_CREATE', 'ZONE_UPDATE', 'ZONE_DELETE', 'DRAFT_DISCARD', 'PUBLISH')),
    zone_id        TEXT        CHECK (zone_id IS NULL OR zone_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    content_sha256 TEXT        NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    content        TEXT        NOT NULL CHECK (length(content) BETWEEN 2 AND 20000000),
    rationale      TEXT        CHECK (rationale IS NULL OR length(rationale) BETWEEN 1 AND 2000),
    UNIQUE (version_id, revision)
);
CREATE INDEX zone_matrix_revision_version ON zone_matrix_revision (version_id, revision);

CREATE FUNCTION fn_zone_matrix_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;
CREATE TRIGGER tr_zone_matrix_revision_append_only
    BEFORE UPDATE OR DELETE ON zone_matrix_revision
    FOR EACH ROW EXECUTE FUNCTION fn_zone_matrix_append_only();

CREATE FUNCTION fn_audit_capture_zone_matrix_revision() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, after_state)
    VALUES ('zone_matrix_revision', NEW.version_id || ':' || NEW.revision::TEXT, 'INSERT', NEW.actor,
            CASE WHEN NEW.change_kind = 'PUBLISH' THEN 'zone_matrix_publish' ELSE 'zone_matrix_edit' END,
            jsonb_build_object('version_id', NEW.version_id, 'revision', NEW.revision, 'change_kind', NEW.change_kind,
                               'zone_id', NEW.zone_id, 'content_sha256', NEW.content_sha256,
                               'rationale_given', NEW.rationale IS NOT NULL));
    RETURN NEW;
END;
$$;
CREATE TRIGGER tr_audit_zone_matrix_revision
    AFTER INSERT ON zone_matrix_revision
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture_zone_matrix_revision();

-- Every raw view of blocks and every address lookup is on record (section 3.4); never the address itself.
CREATE TABLE zone_matrix_access_log (
    access_id   BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor       TEXT        NOT NULL CHECK (length(actor) BETWEEN 1 AND 512),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    kind        TEXT        NOT NULL CHECK (kind IN ('BLOCKS_VIEW', 'ADDRESS_LOOKUP')),
    version_id  TEXT        REFERENCES zone_matrix_version(version_id)
);
CREATE TRIGGER tr_zone_matrix_access_log_append_only
    BEFORE UPDATE OR DELETE ON zone_matrix_access_log
    FOR EACH ROW EXECUTE FUNCTION fn_zone_matrix_append_only();

CREATE FUNCTION fn_audit_capture_zone_matrix_access() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, after_state)
    VALUES ('zone_matrix_access_log', NEW.access_id::TEXT, 'INSERT', NEW.actor, 'zone_matrix_zones_read',
            jsonb_build_object('kind', NEW.kind, 'version_id', NEW.version_id));
    RETURN NEW;
END;
$$;
CREATE TRIGGER tr_audit_zone_matrix_access_log
    AFTER INSERT ON zone_matrix_access_log
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture_zone_matrix_access();

GRANT SELECT, INSERT, UPDATE ON zone_matrix_version TO ui2_app;
GRANT SELECT, INSERT ON zone_matrix_revision, zone_matrix_access_log TO ui2_app;
