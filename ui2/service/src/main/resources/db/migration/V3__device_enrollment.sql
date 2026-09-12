-- V3__device_enrollment.sql
--
-- UI 2.0 device enrollment columns, per
-- docs/design/UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md (FROZEN,
-- as amended by docs/design/UI2_0_B1_ADJUDICATION_2026_09_12.md, section 2
-- answer 2: "B1-4b is V3") and section 2 answer 7 (enrollment vocabulary)
-- plus finding F5 (V1 wrongly forecloses these columns -- struck).
--
-- Additive to V1 (B1-2) and V2 (B1-3). Both are immutable; this file adds
-- no CREATE TABLE for devices/endpoints/credential_references (V1 already
-- created the three tables in full, per adjudication section 2 answer 1)
-- and issues only ALTER TABLE statements plus the credential-reference
-- linkage column the contract's section 2 relationship
-- ("devices *-1 credential_references") requires but C1 section 3.2's
-- sketch left to this movement's own full column set.
--
-- Deliberately absent from this file: capability_registry / gate-registry
-- table (B1-4, V4, adjudication F2); job lifecycle columns (B1-4, V4,
-- adjudication F1); any C7 backup/artefact/manifest table; a second
-- endpoint per device (REL-DISCOVERY, adjudication section 2 answer 9);
-- any device display-name/label column (C1 section 3.2's sketch names
-- none; not invented here to avoid adding an unspecified sensitive field).

-- ---------------------------------------------------------------------
-- 1. devices.enrollment_state -- contract section 3, adjudication answer 7:
-- DRAFT/ENROLLED/UNREACHABLE/DEGRADED, closed vocabulary, fails closed on
-- any other value via the CHECK constraint (never silently defaulted to
-- ENROLLED -- AGENTS.md UNKNOWN/fail-closed law). Every registration
-- inserts DRAFT explicitly (application code); the column default exists
-- only so the ALTER TABLE itself is well-defined over the table's DDL, not
-- as an implicit-registration path.
-- ---------------------------------------------------------------------

ALTER TABLE devices
    ADD COLUMN enrollment_state TEXT NOT NULL DEFAULT 'DRAFT';

ALTER TABLE devices
    ADD CONSTRAINT chk_devices_enrollment_state
        CHECK (enrollment_state IN ('DRAFT', 'ENROLLED', 'UNREACHABLE', 'DEGRADED'));

ALTER TABLE devices ALTER COLUMN enrollment_state DROP DEFAULT;

CREATE INDEX idx_devices_enrollment_state ON devices(enrollment_state);

-- ---------------------------------------------------------------------
-- 2. devices.disabled -- adjudication answer 7: a separate boolean column,
-- not a fifth enrollment_state value. Independent of enrollment_state
-- (contract section 3 "any -> disabled" row): a device can be disabled
-- from any enrollment state without losing that state's own value.
-- ---------------------------------------------------------------------

ALTER TABLE devices
    ADD COLUMN disabled BOOLEAN NOT NULL DEFAULT false;

-- ---------------------------------------------------------------------
-- 3. devices.credential_reference_id -- contract section 2's relationship
-- ("devices *-1 credential_references: one credential reference per
-- registration"). Not sketched in C1 section 3.2's devices DDL (which
-- fixes only identity-shape/audit-linkage/data-class columns); this
-- movement owns the full column set (C1 section 3.2's closing sentence)
-- and adds the linkage the relationship section already requires.
-- References an EXISTING credential_references row only -- registration
-- (contract section 4 "Validated") never creates one.
-- ---------------------------------------------------------------------

ALTER TABLE devices
    ADD COLUMN credential_reference_id TEXT
        REFERENCES credential_references(credential_reference_id);

ALTER TABLE devices
    ALTER COLUMN credential_reference_id SET NOT NULL;

CREATE INDEX idx_devices_credential_reference_id ON devices(credential_reference_id);

-- ---------------------------------------------------------------------
-- No new trigger is added: trg_audit_devices already exists (V1) and
-- covers every column this file adds, because fn_audit_capture() captures
-- the row via to_jsonb(NEW)/to_jsonb(OLD), not an enumerated column list
-- (C1 section 3.5). No grant changes: ui2_app's INSERT/UPDATE/DELETE grant
-- on devices already covers these columns (V1 section 7).
-- ---------------------------------------------------------------------
