-- V2__identity_sessions_rbac.sql
--
-- UI 2.0 identity, session and RBAC schema, per
-- docs/design/UI2_0_B1_03_IDENTITY_SESSIONS_CONTRACT.md (FROZEN, as amended
-- by docs/design/UI2_0_B1_ADJUDICATION_2026_09_12.md, section 2 answer 2:
-- "B1-3 is V2") and docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md
-- (C3) sections 3.1, 4.2 and 5.3, whose DDL sketches this file realizes
-- literally (contract section 3 sketch discipline: illustrative DDL fixing
-- the load-bearing decisions; this file may add non-load-bearing columns
-- without a C3-successor movement, but removes/retypes/reassigns nothing
-- C3 fixed).
--
-- Additive to V1 (B1-2). V1 is immutable; this file adds four tables C1
-- section 3.1 reserved and declined to sketch: role_bindings, sessions,
-- actor_authz_state, authz_decisions.
--
-- Deliberately absent from this file: capability_registry / gate-registry
-- table (B1-4, V4, adjudication F2); job lifecycle columns (B1-4, V4,
-- adjudication F1); devices/endpoints enrollment columns (B1-4b, V3,
-- adjudication F5); any C7 backup/artefact/manifest table.

-- ---------------------------------------------------------------------
-- 1. role_bindings (C3 section 4.2)
-- ---------------------------------------------------------------------

CREATE TABLE role_bindings (
    binding_id                    TEXT        PRIMARY KEY,
    role_token                    TEXT        NOT NULL,
    group_reference_encrypted     BYTEA       NOT NULL,
    group_reference_key_id        TEXT        NOT NULL,
    created_by_actor_fingerprint  TEXT        NOT NULL,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at                    TIMESTAMPTZ,
    revoked_by_actor_fingerprint  TEXT
);

-- Section 4.1's closed role-token vocabulary. This CHECK is a non-load
-- -bearing hardening: C3 fixes the set is closed and repository-committed;
-- expressing that closure as a database constraint (rather than only an
-- application-side enum) is this movement's own choice, additive, not a
-- retyping of anything C3 fixed.
ALTER TABLE role_bindings ADD CONSTRAINT chk_role_bindings_role_token
    CHECK (role_token IN (
        'role:viewer', 'role:operator', 'role:onboarding_admin',
        'role:backup_admin', 'role:compliance_admin', 'role:security_admin'));

CREATE INDEX idx_role_bindings_role_token_active
    ON role_bindings(role_token) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------
-- 2. sessions (C3 section 3.1) -- the partial unique index is what makes
-- single-active-session structurally impossible to violate.
-- ---------------------------------------------------------------------

CREATE TABLE sessions (
    session_id                 TEXT        PRIMARY KEY,
    actor_fingerprint          TEXT        NOT NULL,
    csrf_secret                TEXT        NOT NULL,
    state                      TEXT        NOT NULL CHECK (state IN ('ACTIVE','SUPERSEDED','EXPIRED','REVOKED')),
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    idle_deadline_at           TIMESTAMPTZ NOT NULL,
    absolute_expires_at        TIMESTAMPTZ NOT NULL,
    superseded_by_session_id   TEXT        REFERENCES sessions(session_id),
    ended_by_actor_fingerprint TEXT,
    end_reason                 TEXT        CHECK (end_reason IN
        ('login_elsewhere', 'idle_timeout', 'absolute_lifetime', 'revoked_by_admin', 'access_group_lost'))
);

CREATE UNIQUE INDEX ux_sessions_one_active_per_actor
    ON sessions(actor_fingerprint) WHERE state = 'ACTIVE';

CREATE INDEX idx_sessions_actor_fingerprint ON sessions(actor_fingerprint);

-- ---------------------------------------------------------------------
-- 3. actor_authz_state -- derived, ephemeral cache of a directory answer
-- (C3 section 4.4, section 6.1's "A1'" row); no DDL sketch is given
-- verbatim by C3, so this file's shape is this movement's own, per
-- contract section 3.1's sketch discipline. Rewritten on every
-- re-validation cycle; deleted when its owning actor has no active
-- session; carries no decision of its own (never wired to
-- fn_audit_capture(), C3 section 3.5).
-- ---------------------------------------------------------------------

CREATE TABLE actor_authz_state (
    actor_fingerprint  TEXT        PRIMARY KEY,
    group_references   JSONB       NOT NULL,
    resolved_at        TIMESTAMPTZ NOT NULL,
    valid_until        TIMESTAMPTZ NOT NULL
);

-- ---------------------------------------------------------------------
-- 4. authz_decisions (C3 section 5.3) -- append-only by construction and
-- grant; records every E4 evaluation, not only ones gating a mutation.
-- ---------------------------------------------------------------------

CREATE TABLE authz_decisions (
    decision_id       BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    decided_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    session_id        TEXT        NOT NULL REFERENCES sessions(session_id),
    actor_fingerprint TEXT        NOT NULL,
    action_id         TEXT        NOT NULL,
    target_ref        TEXT,
    outcome           TEXT        NOT NULL CHECK (outcome IN ('PERMITTED','DENIED','AUTHZ_NOT_EVALUATED','NO_APPLICABLE_AUTHORITY')),
    authority         TEXT,
    reason_code       TEXT,
    binding_id        TEXT        REFERENCES role_bindings(binding_id)
);

CREATE INDEX idx_authz_decisions_actor_fingerprint ON authz_decisions(actor_fingerprint);
CREATE INDEX idx_authz_decisions_action_id ON authz_decisions(action_id);

-- ---------------------------------------------------------------------
-- 5. Audit triggers (C3 section 3.5 / section 4.2) -- role_bindings and
-- sessions extend C1-1's mechanism; sessions' trigger is deliberately
-- scoped to AFTER INSERT OR UPDATE OF state only (never a bare
-- last_seen_at/idle_deadline_at heartbeat touch). actor_authz_state and
-- authz_decisions are named, deliberate exceptions (section 3.5) and get
-- no trigger of their own.
-- ---------------------------------------------------------------------

CREATE TRIGGER trg_audit_role_bindings
    AFTER INSERT OR UPDATE OR DELETE ON role_bindings
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('binding_id');

CREATE TRIGGER trg_audit_sessions
    AFTER INSERT OR UPDATE OF state ON sessions
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('session_id');

-- ---------------------------------------------------------------------
-- 6. Grants (C1 section 2.4 / C3 section 5.3's literal grant block)
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE ON role_bindings, sessions, actor_authz_state TO ui2_app;

REVOKE INSERT, UPDATE, DELETE ON authz_decisions FROM ui2_app;
GRANT SELECT, INSERT ON authz_decisions TO ui2_app;
