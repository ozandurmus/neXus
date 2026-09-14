-- V14 -- the discovery run and candidate tables, per
-- docs/design/PO_DECISION_RECORD_2026_09_14F_DISCOVERY_FROM_THE_UI_RUN_AND_CANDIDATE_SET.md
-- (FROZEN) DR-1/DR-2, and the jobs.target kind extension DR-1 requires
-- (a discovery run is a C2 job whose target is a run row, not a device).
--
-- Additive to V1-V13 (append-only; none of them is edited).
--
-- No audit trigger on either new table: AGENTS.md "Sensitive identity
-- reporting law" forbids an address, hostname, serial or name in an audit
-- row, and discovery_run.management_address / discovery_candidate's
-- display_name/own_address/management_address are exactly those CLASS 2
-- fields (DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md PR-1). The generic
-- fn_audit_capture() trigger (V1) captures the full row via to_jsonb, which
-- would put those fields in audit_log; the job row's own admission/claim
-- audit trail (jobs, already audited) is this movement's control-plane
-- record instead.

-- ---------------------------------------------------------------------
-- 1. jobs -- target kind extension (DR-1): "if jobs.target_device_id is
-- NOT NULL with an FK, add a nullable target_ref + target_kind pair and
-- keep target_device_id for device jobs." target_device_id becomes
-- nullable (a discovery job has none); the CHECK constraint below keeps
-- the two shapes mutually exclusive and total, so a row is always exactly
-- one of "device job" or "discovery_run job".
-- ---------------------------------------------------------------------

ALTER TABLE jobs ALTER COLUMN target_device_id DROP NOT NULL;

ALTER TABLE jobs ADD COLUMN target_kind TEXT NOT NULL DEFAULT 'device';
ALTER TABLE jobs ALTER COLUMN target_kind DROP DEFAULT;

ALTER TABLE jobs ADD COLUMN target_ref TEXT;

ALTER TABLE jobs ADD CONSTRAINT chk_jobs_target_shape
    CHECK (
        (target_kind = 'device' AND target_device_id IS NOT NULL AND target_ref IS NULL)
        OR (target_kind = 'discovery_run' AND target_ref IS NOT NULL AND target_device_id IS NULL)
    );

-- ---------------------------------------------------------------------
-- 2. discovery_run (DR-1). The run row IS the job target -- job_id links
-- back to the one jobs row admitted against it (jobs.target_kind =
-- 'discovery_run', jobs.target_ref = discovery_run.run_id).
-- ---------------------------------------------------------------------

CREATE TABLE discovery_run (
    run_id                          TEXT        PRIMARY KEY,
    vendor                          TEXT        NOT NULL,
    management_address              TEXT        NOT NULL,
    credential_reference_id         TEXT        NOT NULL REFERENCES credential_references(credential_reference_id),
    requested_by_actor_fingerprint  TEXT        NOT NULL,
    state                           TEXT        NOT NULL,
    job_id                          TEXT        REFERENCES jobs(job_id),
    started_at                      TIMESTAMPTZ,
    finished_at                     TIMESTAMPTZ,
    outcome_summary                 JSONB,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE discovery_run ADD CONSTRAINT chk_discovery_run_state
    CHECK (state IN ('REQUESTED', 'RUNNING', 'FINISHED', 'FAILED'));

-- DR-3 retention: at most 24h, swept on read or on a schedule -- the sweep
-- selects by finished_at, so this index is what keeps that scan cheap.
CREATE INDEX idx_discovery_run_finished_at ON discovery_run(finished_at) WHERE finished_at IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON discovery_run TO ui2_app;

-- ---------------------------------------------------------------------
-- 3. discovery_candidate (DR-2). Selection-only, never a device row
-- (DI-1 completed by DR-2: "a candidate row is not a device and creates
-- no endpoint"). parent_candidate_id and cluster_reference are plain TEXT
-- with no FK: CP's MC-2 ("the reference is absent... NOT_EVALUABLE, no
-- fallback") and HL-1's MISSING/AMBIGUOUS outcomes mean a reference may
-- legitimately name no row in this run's own candidate set.
-- ---------------------------------------------------------------------

CREATE TABLE discovery_candidate (
    candidate_id         TEXT        PRIMARY KEY,
    run_id                TEXT        NOT NULL REFERENCES discovery_run(run_id) ON DELETE CASCADE,
    vendor                TEXT        NOT NULL,
    stable_identifier     TEXT        NOT NULL,
    owning_domain         TEXT,
    kind                  TEXT        NOT NULL,
    display_name          TEXT,
    own_address           TEXT,
    management_address    TEXT,
    cluster_reference     TEXT,
    parent_candidate_id   TEXT,
    model                 TEXT,
    software_version      TEXT,
    connection_state      TEXT,
    importable            BOOLEAN     NOT NULL,
    import_outcome        TEXT
);

CREATE INDEX idx_discovery_candidate_run_id ON discovery_candidate(run_id);
CREATE UNIQUE INDEX uq_discovery_candidate_run_stable_identifier
    ON discovery_candidate(run_id, stable_identifier);

ALTER TABLE discovery_candidate ADD CONSTRAINT chk_discovery_candidate_import_outcome
    CHECK (import_outcome IS NULL OR import_outcome IN ('new', 'already_imported', 'conflicting'));

GRANT SELECT, INSERT, UPDATE, DELETE ON discovery_candidate TO ui2_app;

-- ---------------------------------------------------------------------
-- 4. devices -- the RD-3 match key column import needs. DEVICE_IMPORT_
-- AND_ENROLLMENT_CONTRACT.md IM-10 fixes device_id as opaque and never a
-- vendor identifier; discovery_match_key is the separate, explicit
-- carrier for the vendor stable identifier RD-3/RD-5 match by, opaque to
-- this column too (never parsed, never the join key on its own). One
-- devices row carries at most one match key, so a unique partial index
-- (NULL for a manually registered device, which has none) is what RD-5's
-- "no existing devices row carries this match key" lookup relies on.
-- ---------------------------------------------------------------------

ALTER TABLE devices ADD COLUMN discovery_match_key TEXT;

CREATE UNIQUE INDEX uq_devices_discovery_match_key ON devices(discovery_match_key)
    WHERE discovery_match_key IS NOT NULL;
