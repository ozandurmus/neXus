-- V1__initial_schema.sql
--
-- UI 2.0 initial schema, per docs/design/UI2_0_B1_02_SCHEMA_V1_CONTRACT.md
-- (FROZEN, as amended by docs/design/UI2_0_B1_ADJUDICATION_2026_09_12.md,
-- section 2 answer 1) and docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md
-- section 3 (sketches) / section 5 (grants).
--
-- Touches: creates all nine tables of C1 section 3.1's ownership matrix --
-- devices, endpoints, credential_references, provenance_records, jobs,
-- job_steps, cp_inventory_projection, secrets_metadata, audit_log -- plus
-- the shared audit trigger function and one trigger per mutation-bearing
-- table, plus the ui2_app/ui2_migrate grants of C1 section 5.
--
-- Realizing: C1 sections 3.2 (devices/endpoints/credential_references,
-- minimal identity-placeholder column set -- B1-4b adds enrollment/lifecycle
-- columns by its own later, additive migration, per adjudication F5/section
-- 2 answer 1: this file adds none), 3.3 (jobs/job_steps, identity columns
-- only -- C2's lifecycle columns land in B1-4's V4, per adjudication F1),
-- 3.4 (cp_inventory_projection), 3.5 (audit_log and the fn_audit_capture
-- fail-closed mechanism, C1-1), 3.6 (secrets_metadata), and section 5
-- (grants).
--
-- Deliberately absent from this file, with reason (contract section 4 /
-- adjudication F1, F2): capability_registry and any gate-registry table
-- (B1-4, V4); role_bindings/sessions/actor_authz_state/authz_decisions
-- (B1-3, V2); job_steps' outcome/kind columns, job_step_attempt,
-- job_reconciliation (B1-4, V4); any C7 backup/artefact/manifest table.
--
-- Schema: this contract never names a non-default PostgreSQL schema, and
-- C1 section 2.1 fixes a dedicated database instance for this product
-- (never a shared multi-tenant instance) rather than a named schema inside
-- a shared one. This file therefore reads contract section 5's
-- "<ui2_schema>" placeholder as the database's default "public" schema.
--
-- Role creation: CREATE ROLE ui2_migrate / ui2_app is out of scope here
-- (contract section 2; UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md Amendment
-- B1-1-A item 4) -- both roles already exist, created by the integration
-- harness's bootstrap step that runs before Flyway. This file only
-- grants/revokes privileges against roles that already exist.

-- ---------------------------------------------------------------------
-- 1. devices / endpoints / credential_references (C1 section 3.2)
-- ---------------------------------------------------------------------

CREATE TABLE devices (
    device_id            TEXT        PRIMARY KEY,
    vendor_hint           TEXT        NOT NULL,
    registration_source    TEXT        NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_test_target         BOOLEAN     NOT NULL DEFAULT false
);

CREATE TABLE endpoints (
    endpoint_id     TEXT        PRIMARY KEY,
    device_id        TEXT        NOT NULL REFERENCES devices(device_id),
    transport_kind    TEXT        NOT NULL,
    address_ref        TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_endpoints_device_id ON endpoints(device_id);

CREATE TABLE credential_references (
    credential_reference_id TEXT PRIMARY KEY,
    purpose                   TEXT NOT NULL,
    backend_pointer            TEXT NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 2. provenance_records (C1 section 5)
-- ---------------------------------------------------------------------

CREATE TABLE provenance_records (
    provenance_id        TEXT        PRIMARY KEY,
    run_id                TEXT        NOT NULL,
    step_id                TEXT        NOT NULL,
    parser_version         TEXT        NOT NULL,
    capability_version      TEXT        NOT NULL, -- FK to C4's capability_registry once C4 lands; TEXT, no FK, per C1 §3.3 note
    capture_artifact_id    TEXT,
    source_location         TEXT        NOT NULL,
    sanitized_fragment      TEXT,
    fingerprint_sha256       TEXT        NOT NULL,
    collected_at             TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_provenance_run_id ON provenance_records(run_id);

-- ---------------------------------------------------------------------
-- 3. jobs / job_steps -- identity columns only (C1 section 3.3)
-- ---------------------------------------------------------------------

CREATE TABLE jobs (
    job_id                            TEXT        PRIMARY KEY,
    job_type                           TEXT        NOT NULL,
    capability_id                      TEXT, -- FK to C4's capability_registry once C4 lands; TEXT, no FK, per C1 §3.3 note
    target_device_id                   TEXT        NOT NULL REFERENCES devices(device_id),
    submitted_by_actor_fingerprint      TEXT        NOT NULL,
    submitted_at                        TIMESTAMPTZ NOT NULL DEFAULT now()
    -- C2 adds: state, lease_owner, lease_expires_at, heartbeat_at, outcome,
    -- attempt/duplicate-detection columns, approver identity for scheduled
    -- runs, schedule optimistic-concurrency token -- B1-4's V4 (adjudication F1).
);

CREATE INDEX idx_jobs_target_device_id ON jobs(target_device_id);

CREATE TABLE job_steps (
    job_step_id     TEXT        PRIMARY KEY,
    job_id           TEXT        NOT NULL REFERENCES jobs(job_id),
    step_index        INTEGER     NOT NULL
    -- C2 adds: kind, outcome, matched_expectation, duration_ms, error_class,
    -- output_fingerprint/output_bytes -- B1-4's V4 (adjudication F1).
);

CREATE INDEX idx_job_steps_job_id ON job_steps(job_id);

-- ---------------------------------------------------------------------
-- 4. cp_inventory_projection -- first capability's evidence table (C1 §3.4)
-- ---------------------------------------------------------------------

CREATE TABLE cp_inventory_projection (
    projection_id     TEXT        PRIMARY KEY,
    device_id          TEXT        NOT NULL REFERENCES devices(device_id),
    endpoint_id         TEXT        NOT NULL REFERENCES endpoints(endpoint_id),
    job_id               TEXT        NOT NULL REFERENCES jobs(job_id),
    provenance_id         TEXT        NOT NULL REFERENCES provenance_records(provenance_id),
    product_version        TEXT,
    ha_state                TEXT,
    collected_at             TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_cp_inv_device_id ON cp_inventory_projection(device_id);

-- ---------------------------------------------------------------------
-- 5. secrets_metadata (C1 section 3.6)
-- ---------------------------------------------------------------------

CREATE TABLE secrets_metadata (
    secret_id             TEXT        PRIMARY KEY,
    component              TEXT        NOT NULL,
    purpose                 TEXT        NOT NULL,
    backend_kind             TEXT        NOT NULL CHECK (backend_kind IN ('env_file', 'vault_reference')),
    reference_pointer         TEXT        NOT NULL,
    rotation_policy_ref        TEXT,
    rotated_at                  TIMESTAMPTZ,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 6. audit_log and the C1-1 fail-closed audit mechanism (C1 section 3.5)
-- ---------------------------------------------------------------------

CREATE TABLE audit_log (
    audit_id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    table_name           TEXT        NOT NULL,
    row_pk                TEXT        NOT NULL,
    operation              TEXT        NOT NULL CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE')),
    actor_fingerprint       TEXT        NOT NULL,
    action_id                TEXT        NOT NULL,
    occurred_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    before_state                 JSONB,
    after_state                   JSONB,
    correlation_run_id             TEXT
);

CREATE INDEX idx_audit_log_table_row ON audit_log(table_name, row_pk);
CREATE INDEX idx_audit_log_correlation_run_id ON audit_log(correlation_run_id);

CREATE OR REPLACE FUNCTION fn_audit_capture() RETURNS trigger AS $$
DECLARE
    v_actor  TEXT  := current_setting('app.actor_fingerprint', true);
    v_action TEXT  := current_setting('app.action_id', true);
    v_pk_col TEXT  := TG_ARGV[0];
    v_row    JSONB := to_jsonb(COALESCE(NEW, OLD));
BEGIN
    IF v_actor IS NULL OR v_actor = '' OR v_action IS NULL OR v_action = '' THEN
        RAISE EXCEPTION 'audit_context_missing on %.%: actor or action not set in this transaction',
            TG_TABLE_NAME, v_pk_col;
    END IF;

    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id,
                           before_state, after_state, correlation_run_id)
    VALUES (TG_TABLE_NAME, v_row ->> v_pk_col, TG_OP, v_actor, v_action,
            CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END,
            CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE to_jsonb(NEW) END,
            current_setting('app.correlation_run_id', true));
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- One trigger per mutation-bearing table (C1 §3.1's set, minus audit_log
-- itself, which is never self-audited -- contract §6). Each names its own
-- primary-key column as the trigger argument.

CREATE TRIGGER trg_audit_devices
    AFTER INSERT OR UPDATE OR DELETE ON devices
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('device_id');

CREATE TRIGGER trg_audit_endpoints
    AFTER INSERT OR UPDATE OR DELETE ON endpoints
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('endpoint_id');

CREATE TRIGGER trg_audit_credential_references
    AFTER INSERT OR UPDATE OR DELETE ON credential_references
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('credential_reference_id');

CREATE TRIGGER trg_audit_provenance_records
    AFTER INSERT OR UPDATE OR DELETE ON provenance_records
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('provenance_id');

CREATE TRIGGER trg_audit_jobs
    AFTER INSERT OR UPDATE OR DELETE ON jobs
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('job_id');

CREATE TRIGGER trg_audit_job_steps
    AFTER INSERT OR UPDATE OR DELETE ON job_steps
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('job_step_id');

CREATE TRIGGER trg_audit_cp_inventory_projection
    AFTER INSERT OR UPDATE OR DELETE ON cp_inventory_projection
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('projection_id');

CREATE TRIGGER trg_audit_secrets_metadata
    AFTER INSERT OR UPDATE OR DELETE ON secrets_metadata
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('secret_id');

-- ---------------------------------------------------------------------
-- 7. Roles and grants (contract section 5 / C1 section 2.4)
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE
  ON devices, endpoints, credential_references, provenance_records,
     jobs, job_steps, cp_inventory_projection, secrets_metadata
  TO ui2_app;

REVOKE INSERT, UPDATE, DELETE ON audit_log FROM ui2_app;
GRANT SELECT ON audit_log TO ui2_app;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM ui2_app;
GRANT USAGE ON SCHEMA public TO ui2_app;
