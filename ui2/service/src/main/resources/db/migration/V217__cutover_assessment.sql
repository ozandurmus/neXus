-- ACI segmentation support, step 1 (docs/design/ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md section 4; PO decision
-- record 2026-11-14): a cutover segment and its pre-cutover impact assessments. Uploaded logs are read once in memory;
-- no raw row, header, mapping or file content is stored. Audit rows carry opaque identities and revisions only.
CREATE TABLE IF NOT EXISTS cutover_segment (
    segment_id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE CHECK (length(name) BETWEEN 1 AND 64),
    revision INTEGER NOT NULL CHECK (revision >= 1),
    blocks JSONB NOT NULL CHECK (jsonb_typeof(blocks) = 'array' AND jsonb_array_length(blocks) BETWEEN 1 AND 4096),
    peer_blocks JSONB NOT NULL CHECK (jsonb_typeof(peer_blocks) = 'array' AND jsonb_array_length(peer_blocks) BETWEEN 1 AND 4096),
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS cutover_assessment (
    assessment_id TEXT PRIMARY KEY,
    segment_id TEXT NOT NULL REFERENCES cutover_segment(segment_id) ON DELETE CASCADE,
    segment_revision INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor TEXT NOT NULL,
    target_source TEXT NOT NULL CHECK (target_source IN ('PATH', 'OPERATOR_OVERRIDE')),
    target_device_id TEXT,
    topology_snapshot_id TEXT,
    target_policy_id TEXT,
    target_collected_at TEXT,
    old_policy_id TEXT,
    old_collected_at TEXT,
    engine_version TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('COMPLETE', 'INCOMPLETE')),
    readiness TEXT NOT NULL CHECK (readiness IN ('BLOCKERS_FOUND', 'NOT_DECIDED', 'NO_BLOCKERS_IN_EVALUATED_FLOWS', 'NO_EVIDENCE')),
    summary JSONB NOT NULL CHECK (jsonb_typeof(summary) = 'object'
        AND summary ?& ARRAY['counts', 'coverage', 'uploads']
        AND NOT summary ?| ARRAY['rows', 'raw', 'file', 'files', 'csv', 'mapping', 'mappings', 'headers']),
    -- A routed-path assessment cites its topology snapshot; an operator override names its target device.
    CHECK (target_source = 'OPERATOR_OVERRIDE' AND target_device_id IS NOT NULL
        OR target_source = 'PATH' AND target_device_id IS NULL AND topology_snapshot_id IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS cutover_assessment_segment_time ON cutover_assessment(segment_id, created_at DESC);

-- One row per evaluated flow tuple or per missing-rule group; bounded by the engine (at most 50,000 tuples).
CREATE TABLE IF NOT EXISTS cutover_assessment_flow (
    assessment_id TEXT NOT NULL REFERENCES cutover_assessment(assessment_id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('FLOW', 'MISSING_RULE')),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    decision TEXT NOT NULL CHECK (decision IN ('WOULD_ALLOW', 'WOULD_DROP', 'NOT_DECIDED', 'NOT_EVALUABLE', 'MISSING_RULE')),
    flow JSONB NOT NULL CHECK (jsonb_typeof(flow) = 'object'
        AND NOT flow ?| ARRAY['rows', 'raw', 'file', 'csv', 'mapping', 'headers']),
    PRIMARY KEY (assessment_id, kind, ordinal)
);
CREATE INDEX IF NOT EXISTS cutover_assessment_flow_decision ON cutover_assessment_flow(assessment_id, kind, decision, ordinal);

CREATE OR REPLACE FUNCTION fn_audit_cutover_segment() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    actor TEXT := current_setting('app.actor_fingerprint', true);
    action TEXT := current_setting('app.action_id', true);
    identity TEXT;
BEGIN
    IF actor IS NULL OR actor = '' OR action IS DISTINCT FROM 'cutover_assessment_write' THEN
        RAISE EXCEPTION 'audit_context_missing';
    END IF;
    IF TG_OP = 'INSERT' AND (NEW.created_by IS DISTINCT FROM actor OR NEW.updated_by IS DISTINCT FROM actor) THEN
        RAISE EXCEPTION 'cutover_segment_actor_mismatch';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.updated_by IS DISTINCT FROM actor OR NEW.revision <> OLD.revision + 1
            OR NEW.segment_id IS DISTINCT FROM OLD.segment_id OR NEW.created_by IS DISTINCT FROM OLD.created_by) THEN
        RAISE EXCEPTION 'cutover_segment_update_invalid';
    END IF;
    identity := CASE WHEN TG_OP = 'DELETE' THEN OLD.segment_id ELSE NEW.segment_id END;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, before_state, after_state)
    VALUES ('cutover_segment', identity, TG_OP, actor, action,
        CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE jsonb_build_object('segment_id', identity, 'revision', OLD.revision) END,
        CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE jsonb_build_object('segment_id', identity, 'revision', NEW.revision) END);
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fn_audit_cutover_segment() FROM PUBLIC;
DROP TRIGGER IF EXISTS trg_audit_cutover_segment ON cutover_segment;
CREATE TRIGGER trg_audit_cutover_segment BEFORE INSERT OR UPDATE OR DELETE ON cutover_segment
    FOR EACH ROW EXECUTE FUNCTION fn_audit_cutover_segment();

CREATE OR REPLACE FUNCTION fn_audit_cutover_assessment() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    actor TEXT := current_setting('app.actor_fingerprint', true);
    action TEXT := current_setting('app.action_id', true);
    identity TEXT;
BEGIN
    IF actor IS NULL OR actor = '' OR action IS DISTINCT FROM 'cutover_assessment_write' THEN
        RAISE EXCEPTION 'audit_context_missing';
    END IF;
    IF TG_OP = 'UPDATE' THEN RAISE EXCEPTION 'cutover_assessment_immutable'; END IF;
    IF TG_OP = 'INSERT' AND NEW.actor IS DISTINCT FROM actor THEN RAISE EXCEPTION 'cutover_assessment_actor_mismatch'; END IF;
    identity := CASE WHEN TG_OP = 'DELETE' THEN OLD.assessment_id ELSE NEW.assessment_id END;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, before_state, after_state)
    VALUES ('cutover_assessment', identity, TG_OP, actor, action,
        CASE WHEN TG_OP = 'DELETE' THEN jsonb_build_object('assessment_id', identity) ELSE NULL END,
        CASE WHEN TG_OP = 'INSERT' THEN jsonb_build_object('assessment_id', identity, 'segment_id', NEW.segment_id,
            'readiness', NEW.readiness) ELSE NULL END);
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fn_audit_cutover_assessment() FROM PUBLIC;
DROP TRIGGER IF EXISTS trg_audit_cutover_assessment ON cutover_assessment;
CREATE TRIGGER trg_audit_cutover_assessment BEFORE INSERT OR UPDATE OR DELETE ON cutover_assessment
    FOR EACH ROW EXECUTE FUNCTION fn_audit_cutover_assessment();

-- Flow rows are written only inside the audited assessment insert and removed by its cascade; they are never updated.
CREATE OR REPLACE FUNCTION fn_guard_cutover_assessment_flow() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN RAISE EXCEPTION 'cutover_assessment_flow_immutable'; END IF;
    IF TG_OP = 'INSERT' AND current_setting('app.action_id', true) IS DISTINCT FROM 'cutover_assessment_write' THEN
        RAISE EXCEPTION 'audit_context_missing';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fn_guard_cutover_assessment_flow() FROM PUBLIC;
DROP TRIGGER IF EXISTS trg_guard_cutover_assessment_flow ON cutover_assessment_flow;
CREATE TRIGGER trg_guard_cutover_assessment_flow BEFORE INSERT OR UPDATE ON cutover_assessment_flow
    FOR EACH ROW EXECUTE FUNCTION fn_guard_cutover_assessment_flow();

GRANT SELECT, INSERT, UPDATE, DELETE ON cutover_segment TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON cutover_assessment TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON cutover_assessment_flow TO ui2_app;
