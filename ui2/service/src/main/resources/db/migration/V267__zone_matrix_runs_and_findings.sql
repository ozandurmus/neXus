-- V267 -- Zone matrix, delivery step 4b-1: durable evaluation runs and their findings
-- (docs/design/ZONE_MATRIX_CONTRACT.md sections 5.1, 5.9, 8.1-8.3 and 9, FROZEN 2026-11-13).
--
-- Contract 8.1 requires a BEGIN/ROLLBACK dry-run of this migration on the live database before rollout; it creates two
-- tables, two guard functions and their triggers, and grants. Nothing existing is altered.
--
-- zone_matrix_run: one row per evaluation of one stored policy against one published version. RUNNING while the
-- engine works; COMPLETE or FAILED once, and then final (fn_zone_matrix_run_guard). Findings and the COMPLETE state
-- are written in one transaction; a finding can only be inserted while its run is RUNNING, so a COMPLETE run's
-- findings never change and a FAILED run never gains any (section 5.1: partial findings never become visible). A run
-- left RUNNING by a pod kill is set FAILED with failure_reason INTERRUPTED_AT_STARTUP by the service at startup.
-- At most one RUNNING run per policy source (partial unique index; section 9).
--
-- The run stores what evidence needs at evaluation time because the snapshot is overwritten by the next collection
-- (section 5.1): snapshot collected_at and size, engine version, version id / number / content hash, coverage and the
-- count of every outcome. No raw device response, no full rule copy, no snapshot copy (section 8.1).
--
-- zone_matrix_finding: one row per rule whose outcome is VIOLATION, UNKNOWN or NOT_EVALUABLE in a run. Identity
-- columns (section 5.9: policy source, rule uid, inline parent path, violated zone pairs) are TEXT / JSONB arrays of
-- TEXT compared exactly (identity law: no cast, no case folding, no trimming). The lifecycle columns exist now so the
-- lifecycle step needs no second migration; only they may be updated (column grants).
--
-- No delete route and no DELETE grant on either table: until the bank states a retention (section 8.3), runs and
-- findings are not deleted; a future retention janitor brings its own grant. Findings reference their run with
-- ON DELETE RESTRICT.
--
-- Audit: these rows are derived evaluation output written by the service as a system job with no user actor; zone
-- blocks and witness addresses must never be captured into audit_log (section 8.2), so there is no audit trigger
-- (AuditCoverageCompletenessTest NOT_MUTATION_BEARING).

CREATE TABLE zone_matrix_run (
    run_id                TEXT        PRIMARY KEY CHECK (run_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    version_id            TEXT        NOT NULL REFERENCES zone_matrix_version(version_id),
    version_number        INTEGER     NOT NULL CHECK (version_number >= 1),
    content_sha256        TEXT        NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    policy_source_id      TEXT        NOT NULL CHECK (length(policy_source_id) BETWEEN 1 AND 512),
    engine_version        TEXT        NOT NULL CHECK (length(engine_version) BETWEEN 1 AND 64),
    state                 TEXT        NOT NULL CHECK (state IN ('RUNNING', 'COMPLETE', 'FAILED')),
    failure_reason        TEXT        CHECK (failure_reason IS NULL OR failure_reason IN
                              ('TIME_LIMIT', 'INTERRUPTED_AT_STARTUP', 'ENGINE_ERROR', 'SNAPSHOT_MISSING',
                               'POOL_SATURATED', 'STORE_ERROR')),
    started_at            TIMESTAMPTZ NOT NULL,
    finished_at           TIMESTAMPTZ,
    -- What the evaluated snapshot was (filled on COMPLETE; the snapshot itself is never copied).
    snapshot_collected_at TIMESTAMPTZ,
    snapshot_rule_count   INTEGER     CHECK (snapshot_rule_count IS NULL OR snapshot_rule_count >= 0),
    vendor                TEXT        CHECK (vendor IS NULL OR length(vendor) <= 16),
    evaluated             BOOLEAN,
    coverage              TEXT        CHECK (coverage IS NULL OR coverage IN ('COMPLETE', 'INCOMPLETE')),
    -- Coverage item codes only (section 7.1), never their free text.
    coverage_items        JSONB       CHECK (coverage_items IS NULL OR jsonb_typeof(coverage_items) = 'array'),
    collection_failures   INTEGER     CHECK (collection_failures IS NULL OR collection_failures >= 0),
    count_violation       INTEGER     CHECK (count_violation IS NULL OR count_violation >= 0),
    count_compliant       INTEGER     CHECK (count_compliant IS NULL OR count_compliant >= 0),
    count_not_evaluable   INTEGER     CHECK (count_not_evaluable IS NULL OR count_not_evaluable >= 0),
    count_unknown         INTEGER     CHECK (count_unknown IS NULL OR count_unknown >= 0),
    count_not_applicable  INTEGER     CHECK (count_not_applicable IS NULL OR count_not_applicable >= 0),
    count_findings        INTEGER     CHECK (count_findings IS NULL OR count_findings >= 0),
    duration_ms           BIGINT      CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CHECK ((state = 'RUNNING') = (finished_at IS NULL)),
    CHECK ((state = 'FAILED') = (failure_reason IS NOT NULL)),
    CHECK (state <> 'COMPLETE' OR (coverage IS NOT NULL AND count_violation IS NOT NULL AND count_compliant IS NOT NULL
        AND count_not_evaluable IS NOT NULL AND count_unknown IS NOT NULL AND count_not_applicable IS NOT NULL
        AND count_findings IS NOT NULL))
);
-- At most one RUNNING run per policy source (section 9); policy ids are opaque and compared exactly.
CREATE UNIQUE INDEX zone_matrix_run_one_running ON zone_matrix_run (policy_source_id) WHERE state = 'RUNNING';
CREATE INDEX zone_matrix_run_latest_complete ON zone_matrix_run (policy_source_id, finished_at DESC)
    WHERE state = 'COMPLETE';
CREATE INDEX zone_matrix_run_started ON zone_matrix_run (started_at DESC);

-- A run is RUNNING once and then COMPLETE or FAILED for good; what it evaluated never changes.
CREATE FUNCTION fn_zone_matrix_run_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
BEGIN
    IF OLD.state <> 'RUNNING' THEN
        RAISE EXCEPTION 'a finished zone matrix run is final';
    END IF;
    IF NEW.state NOT IN ('COMPLETE', 'FAILED')
            OR NEW.run_id <> OLD.run_id OR NEW.version_id <> OLD.version_id
            OR NEW.version_number <> OLD.version_number OR NEW.content_sha256 <> OLD.content_sha256
            OR NEW.policy_source_id <> OLD.policy_source_id OR NEW.engine_version <> OLD.engine_version
            OR NEW.started_at <> OLD.started_at THEN
        RAISE EXCEPTION 'a zone matrix run only moves from RUNNING to COMPLETE or FAILED';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fn_zone_matrix_run_guard() FROM PUBLIC;
CREATE TRIGGER tr_zone_matrix_run_guard
    BEFORE UPDATE ON zone_matrix_run
    FOR EACH ROW EXECUTE FUNCTION fn_zone_matrix_run_guard();

CREATE TABLE zone_matrix_finding (
    finding_id                 BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id                     TEXT        NOT NULL REFERENCES zone_matrix_run(run_id) ON DELETE RESTRICT,
    -- Identity (section 5.9), compared exactly.
    policy_source_id           TEXT        NOT NULL CHECK (length(policy_source_id) BETWEEN 1 AND 512),
    rule_uid                   TEXT        NOT NULL,
    inline_parent_path         JSONB       NOT NULL CHECK (jsonb_typeof(inline_parent_path) = 'array'),
    violated_pairs             JSONB       NOT NULL CHECK (jsonb_typeof(violated_pairs) = 'array'),
    -- The rule as stored in the snapshot (number, name, section); not a rule copy.
    rule_id                    TEXT,
    rule_number                INTEGER,
    rule_name                  TEXT,
    section_id                 TEXT,
    section_name               TEXT,
    target_device_ids          JSONB       NOT NULL CHECK (jsonb_typeof(target_device_ids) = 'array'),
    outcome                    TEXT        NOT NULL CHECK (outcome IN ('VIOLATION', 'UNKNOWN', 'NOT_EVALUABLE')),
    reason                     TEXT,
    highest_severity           TEXT,
    flags                      JSONB       NOT NULL CHECK (jsonb_typeof(flags) = 'array'),
    covering_rule_id           TEXT,
    -- Violated cells with their not-allowed parts, NAT view and witness tuple, as the engine returned them.
    violated_cells             JSONB       NOT NULL CHECK (jsonb_typeof(violated_cells) = 'array'),
    undecided_parts            JSONB       NOT NULL CHECK (jsonb_typeof(undecided_parts) = 'array'),
    nat_view                   TEXT,
    -- Lifecycle (section 5.9). The step that wires the lifecycle fills and updates these.
    first_observed_at          TIMESTAMPTZ NOT NULL,
    first_observed_any_version TIMESTAMPTZ,
    last_observed_at           TIMESTAMPTZ NOT NULL,
    resolved_at                TIMESTAMPTZ,
    resolution_reason          TEXT        CHECK (resolution_reason IS NULL OR length(resolution_reason) BETWEEN 1 AND 64),
    CHECK ((resolved_at IS NULL) = (resolution_reason IS NULL))
);
CREATE INDEX zone_matrix_finding_run ON zone_matrix_finding (run_id, finding_id);
CREATE INDEX zone_matrix_finding_identity ON zone_matrix_finding (policy_source_id, rule_uid);

-- Findings are written only while their run is RUNNING (in the transaction that completes it), so a finished run's
-- findings are final and a failed run has none.
CREATE FUNCTION fn_zone_matrix_finding_insert_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
    v_state  TEXT;
    v_policy TEXT;
BEGIN
    SELECT state, policy_source_id INTO v_state, v_policy FROM zone_matrix_run WHERE run_id = NEW.run_id;
    IF v_state IS DISTINCT FROM 'RUNNING' THEN
        RAISE EXCEPTION 'findings are written only while their zone matrix run is RUNNING';
    END IF;
    IF NEW.policy_source_id <> v_policy THEN
        RAISE EXCEPTION 'a finding belongs to the policy source of its run';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fn_zone_matrix_finding_insert_guard() FROM PUBLIC;
CREATE TRIGGER tr_zone_matrix_finding_insert_guard
    BEFORE INSERT ON zone_matrix_finding
    FOR EACH ROW EXECUTE FUNCTION fn_zone_matrix_finding_insert_guard();

-- The service writes and reads both tables; nothing deletes. On findings only the lifecycle columns may be updated.
GRANT SELECT, INSERT, UPDATE ON zone_matrix_run TO ui2_app;
GRANT SELECT, INSERT ON zone_matrix_finding TO ui2_app;
GRANT UPDATE (first_observed_any_version, last_observed_at, resolved_at, resolution_reason)
    ON zone_matrix_finding TO ui2_app;

DO $grants$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ui2_service') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE public.zone_matrix_run TO ui2_service';
        EXECUTE 'GRANT SELECT, INSERT ON TABLE public.zone_matrix_finding TO ui2_service';
        EXECUTE 'GRANT UPDATE (first_observed_any_version, last_observed_at, resolved_at, resolution_reason) '
             || 'ON TABLE public.zone_matrix_finding TO ui2_service';
    END IF;
END
$grants$;
