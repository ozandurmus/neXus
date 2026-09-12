-- V4__collection_engine_core.sql
--
-- UI 2.0 collection engine core, per
-- docs/design/UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md (FROZEN, as
-- amended by docs/design/UI2_0_B1_ADJUDICATION_2026_09_12.md) findings F1
-- and F2:
--
--   F1 -- B1-4 owns the jobs lifecycle/lease/fencing columns plus
--        job_step_attempt and job_reconciliation (C2 sections 2, 4, 5).
--   F2 -- B1-4 owns the gate_registry table (C4 section 3.2), seeded from
--        a version-controlled fixture committed alongside the capability
--        specs (capability-registry/src/main/resources/capabilities/
--        gate_registry_fixture.yaml), never authored freehand by a
--        capability spec.
--
-- Additive to V1 (B1-2), V2 (B1-3), V3 (B1-4b). All three are immutable;
-- this file only ALTERs jobs (already created, identity columns only, by
-- V1) and CREATEs the three new tables F1/F2 name. job_steps (created by
-- V1) is left untouched -- this movement's per-attempt record is the new
-- job_step_attempt table, not an extension of job_steps (F1's own text
-- names exactly "job_step_attempt and job_reconciliation", not job_steps).
--
-- Deliberately absent from this file: any C7 backup/artefact/manifest
-- table; BackupSchedule/optimistic-concurrency columns (C2 section 7, out
-- of this movement's own scope per contract section 1's deferred list);
-- owner/approver/origin columns (same deferral).

-- ---------------------------------------------------------------------
-- 1. jobs -- lease, fencing, lifecycle columns (C2 sections 2, 4, 8)
-- ---------------------------------------------------------------------

ALTER TABLE jobs
    ADD COLUMN state TEXT NOT NULL DEFAULT 'REQUESTED';

ALTER TABLE jobs
    ADD CONSTRAINT chk_jobs_state
        CHECK (state IN ('REQUESTED', 'CLAIMED', 'EXECUTING', 'COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED',
                          'OUTCOME_UNKNOWN', 'RECONCILED'));

ALTER TABLE jobs ALTER COLUMN state DROP DEFAULT;

-- Server-generated-or-client-supplied, de-duplicated at creation (C2
-- section 2.3, adjudication F4). NULL is never inserted by this
-- movement's own admission path (JobAdmissionService always supplies one),
-- but the column itself stays nullable so a future direct-DB fixture/tool
-- is not forced to invent one; the UNIQUE constraint is what does the
-- de-duplication work regardless.
ALTER TABLE jobs
    ADD COLUMN idempotency_key TEXT;

ALTER TABLE jobs
    ADD CONSTRAINT uq_jobs_idempotency_key UNIQUE (idempotency_key);

-- action_class: read from the capability the job references, never
-- client-supplied (C2 section 2.1). NOT NULL with no default -- every
-- INSERT this movement's own admission path performs sets it explicitly.
ALTER TABLE jobs
    ADD COLUMN action_class TEXT NOT NULL DEFAULT 'read';

ALTER TABLE jobs
    ADD CONSTRAINT chk_jobs_action_class
        CHECK (action_class IN ('read', 'recovery-write', 'controlled-restore-write',
                                 'operational-state-change', 'configuration-write', 'policy-deployment'));

ALTER TABLE jobs ALTER COLUMN action_class DROP DEFAULT;

-- Leasing/fencing (C2 section 4.1/4.2). lease_epoch starts at 0 (bumped to
-- 1 on first claim by the claim statement's own "lease_epoch + 1").
ALTER TABLE jobs
    ADD COLUMN lease_worker_id TEXT,
    ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN last_heartbeat_at TIMESTAMPTZ;

-- Terminal result (C2 section 2.1, section 8).
ALTER TABLE jobs
    ADD COLUMN outcome TEXT,
    ADD COLUMN terminal_reason TEXT,
    ADD COLUMN finished_at TIMESTAMPTZ;

-- Reconciliation linkage (C2 section 3.5) -- populated once a
-- job_reconciliation row exists for this job_id; enforced by the FK below,
-- added after job_reconciliation itself is created (section 3 of this
-- file).

-- Precheck trace (C2 section 6): an ordered, complete, truncated-at-first-
-- failure list an operator can read. This movement's own claim-time
-- checks are a narrower subset than C2 section 6's full six-check battery
-- (contract section 1's deferred list excludes E1-E6/schedule/approval
-- machinery); the column exists at the C2-specified shape regardless, so
-- a later movement extending the check battery adds list entries, not a
-- new column.
ALTER TABLE jobs
    ADD COLUMN precheck_results JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_jobs_state ON jobs(state);
CREATE INDEX idx_jobs_lease_expires_at ON jobs(lease_expires_at) WHERE lease_expires_at IS NOT NULL;

-- ---------------------------------------------------------------------
-- 2. job_step_attempt (C2 section 5.1) -- the durable pre-contact record
-- the crash matrix (C2 section 8) depends on.
-- ---------------------------------------------------------------------

CREATE TABLE job_step_attempt (
    attempt_id                  TEXT        PRIMARY KEY,
    job_id                       TEXT        NOT NULL REFERENCES jobs(job_id),
    lease_epoch                   BIGINT      NOT NULL,
    step_index                     INTEGER     NOT NULL,
    step_kind                       TEXT        NOT NULL,
    attempt_number                   INTEGER     NOT NULL DEFAULT 1,
    action_class                      TEXT        NOT NULL,
    -- The two-valued, never-UNKNOWN field the crash matrix depends on (C2
    -- section 5.1): false before contact, flipped to true in the same
    -- transaction that records "the command has been sent." NOT NULL with
    -- a default of false is exactly what makes the column well-defined at
    -- INSERT time -- every attempt row starts false, never NULL.
    mutation_boundary_crossed          BOOLEAN     NOT NULL DEFAULT false,
    sent_at                              TIMESTAMPTZ,
    -- outcome/error_class/output_bytes/output_lines/fingerprint_sha256 are
    -- all nullable: NULL is UNKNOWN (C1 section 3.4), never a sentinel
    -- string, and every one of them is unset until the post-response write
    -- (C2 section 5.1's "after the device responds" row).
    outcome                                TEXT,
    matched_expectation                     BOOLEAN,
    output_bytes                             BIGINT,
    output_lines                              BIGINT,
    fingerprint_sha256                         TEXT,
    captured_variables                          JSONB,
    error_class                                  TEXT,
    created_at                                    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_job_step_attempt_job_id ON job_step_attempt(job_id);
CREATE INDEX idx_job_step_attempt_job_step ON job_step_attempt(job_id, step_index);

-- Contract section 8 test 4/AC-5: the reconciler distinguishes "no attempt
-- row" / "every row boundary=false" / "one row boundary=true, unconfirmed"
-- purely from this table plus jobs.lease_expires_at -- no additional
-- column is needed on jobs for that branch.

-- ---------------------------------------------------------------------
-- 3. job_reconciliation (C2 section 3.5) -- the sole legal exit from
-- OUTCOME_UNKNOWN; a human decision, never a worker action.
-- ---------------------------------------------------------------------

CREATE TABLE job_reconciliation (
    job_id               TEXT        PRIMARY KEY REFERENCES jobs(job_id),
    evidence               TEXT        NOT NULL,
    recorded_by             TEXT        NOT NULL,
    recorded_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    reconciled_outcome         TEXT        NOT NULL
        CHECK (reconciled_outcome IN ('RECONCILED_SUCCESS', 'RECONCILED_FAILURE', 'RECONCILED_STILL_UNKNOWN'))
);

ALTER TABLE jobs
    ADD COLUMN reconciliation_ref TEXT REFERENCES job_reconciliation(job_id);

-- ---------------------------------------------------------------------
-- 4. gate_registry (C4 section 3.2, adjudication F2) -- the runtime,
-- queryable form of one docs/AI_DEVELOPMENT_PROTOCOL.md "Network-device
-- command gate" entry. Rows are seeded from the committed fixture
-- (capability-registry/src/main/resources/capabilities/
-- gate_registry_fixture.yaml) by GateRegistrySeeder at worker startup --
-- never authored freehand by a capability spec (C4 section 3.2: "gate_id
-- values are source-committed... never an author-typed free-text string a
-- spec can invent at will").
--
-- No UNIQUE constraint on the (vendor, platform_role_scope, shell_context,
-- transport_kind, canonical_command_key) tuple: C4 section 3.3 step 6
-- requires "more than one row matching the same canonical key" to be a
-- *detected, hard load-time error* raised by GateResolver at the
-- application layer, not silently prevented by the schema -- a unique
-- constraint here would turn an ambiguous seed (a real defect this
-- movement's own test 4/AC-4 must be able to construct and observe) into
-- a migration-time failure instead of the registry-load-time failure the
-- contract specifies.
-- ---------------------------------------------------------------------

CREATE TABLE gate_registry (
    gate_id                     TEXT        PRIMARY KEY,
    vendor                        TEXT        NOT NULL,
    platform_role_scope             TEXT        NOT NULL,
    shell_context                     TEXT        NOT NULL,
    transport_kind                     TEXT        NOT NULL,
    canonical_command_key                 TEXT        NOT NULL,
    action_class                            TEXT        NOT NULL,
    sign_off_state                            TEXT        NOT NULL
        CHECK (sign_off_state IN ('DRAFTED', 'SIGNED_OFF', 'SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION',
                                   'BLOCKED', 'SUPERSEDED')),
    timeout_s                                  INTEGER     NOT NULL,
    retry_rule                                    TEXT,
    max_frequency                                   TEXT,
    session_reuse_rule                                TEXT,
    unsupported_behavior_ref                            TEXT,
    secret_output_risk                                    TEXT,
    safe_telemetry_fields                                   JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_document_pointer                                   TEXT        NOT NULL,
    created_at                                                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_gate_registry_canonical_key
    ON gate_registry(vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key);

-- ---------------------------------------------------------------------
-- 5. Audit triggers (C1 section 3.5, adjudication F9's exclusion list --
-- job_step_attempt, job_reconciliation and gate_registry are all
-- mutation-bearing and none is named in F9's exclusion list, so each gets
-- one). trg_audit_jobs already exists (V1) and covers every column this
-- file adds to jobs, because fn_audit_capture() captures the row via
-- to_jsonb(NEW)/to_jsonb(OLD), not an enumerated column list.
-- ---------------------------------------------------------------------

CREATE TRIGGER trg_audit_job_step_attempt
    AFTER INSERT OR UPDATE OR DELETE ON job_step_attempt
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('attempt_id');

CREATE TRIGGER trg_audit_job_reconciliation
    AFTER INSERT OR UPDATE OR DELETE ON job_reconciliation
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('job_id');

CREATE TRIGGER trg_audit_gate_registry
    AFTER INSERT OR UPDATE OR DELETE ON gate_registry
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('gate_id');

-- ---------------------------------------------------------------------
-- 6. Grants (contract section 5 / C1 section 5 pattern: ui2_app gets
-- SELECT/INSERT/UPDATE/DELETE on every mutation-bearing table this file
-- adds; nothing here grants DDL to ui2_app).
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE
  ON job_step_attempt, job_reconciliation, gate_registry
  TO ui2_app;
