-- V12 -- device first-contact recorded identity, read facts, identity
-- mismatch state and C4 section 4.2 target modifiers, per
-- docs/design/DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md (FROZEN) section 10
-- "C1 successor migration" items 4 and 5, and
-- docs/design/PO_DECISION_RECORD_2026_09_14_DEVICE_ADD_ENTRY_PEER_FOLLOW_AND_CREDENTIAL_STORE.md
-- (FROZEN) section 2 (PF-1..PF-5).
--
-- Additive to V1-V11 (append-only; none of them is edited). No CREATE
-- TABLE: every column below lives on the existing devices row (contract
-- section 10 leaves the column-vs-linked-table choice to this movement;
-- one physical device's own facts, read once per confirm, do not need a
-- second table).
--
-- No new trigger: trg_audit_devices (V1) already captures the full row via
-- to_jsonb(NEW)/to_jsonb(OLD) (C1 section 3.5), so every column this file
-- adds is audited without further wiring, exactly as V3's own comment
-- records for the columns it added.

-- ---------------------------------------------------------------------
-- 1. Facts read at first contact (contract section 4.3's "hostname,
-- model, software version, HA/cluster role"). Nullable: a NULL column is
-- this schema's existing UNKNOWN representation (C4 section 2.6's rule,
-- applied here at the evidence-row level exactly as C1's own sketch
-- already does for a derived-fact column) -- never a magic string, never
-- a default that would silently invent a fact never read.
-- ---------------------------------------------------------------------

ALTER TABLE devices ADD COLUMN observed_hostname TEXT;
ALTER TABLE devices ADD COLUMN observed_model TEXT;
ALTER TABLE devices ADD COLUMN observed_software_version TEXT;
ALTER TABLE devices ADD COLUMN observed_ha_role TEXT;

-- ---------------------------------------------------------------------
-- 2. Recorded identity (contract section 4.3, EC-5): the baseline every
-- later contact is compared against. CP carries the SSH host-key
-- fingerprint in the "primary" column and the observed hostname in
-- "secondary"; PAN carries the serial in "primary" and the TLS
-- certificate identity in "secondary" -- one generic column pair rather
-- than four vendor-specific columns, since exactly one vendor's pair is
-- ever populated per row (vendor_hint already selects which). EC-9:
-- never silently replaced by application code once set; no UPDATE
-- statement in this migration touches these columns after creation.
-- ---------------------------------------------------------------------

ALTER TABLE devices ADD COLUMN recorded_identity_primary TEXT;
ALTER TABLE devices ADD COLUMN recorded_identity_secondary TEXT;
ALTER TABLE devices ADD COLUMN recorded_identity_recorded_at TIMESTAMPTZ;

-- ---------------------------------------------------------------------
-- 3. Identity-mismatch warning state (13F section 2, EC-6/EC-7): open or
-- none, never a boolean (a mismatch is a state with its own evidence, not
-- a flag) -- fails closed on any other value, mirroring
-- devices.enrollment_state's own CHECK pattern (V3). The presented values
-- at the moment of the mismatch are carried alongside the warning so a
-- screen can show them next to the recorded baseline (contract OP-3) --
-- this document's own scope has no separate management-plane read to
-- compare against (DA-2's dialog carries no management-server context),
-- so "the management plane's current view" for this movement's confirm
-- is the device's own presented-at-mismatch identity, shown beside the
-- previously recorded one; a discovery-sourced device's own
-- management-plane view is a later movement's successor column, not
-- invented here.
-- ---------------------------------------------------------------------

ALTER TABLE devices ADD COLUMN identity_mismatch_state TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE devices ADD CONSTRAINT chk_devices_identity_mismatch_state
    CHECK (identity_mismatch_state IN ('NONE', 'OPEN'));
ALTER TABLE devices ALTER COLUMN identity_mismatch_state DROP DEFAULT;

ALTER TABLE devices ADD COLUMN identity_mismatch_presented_primary TEXT;
ALTER TABLE devices ADD COLUMN identity_mismatch_presented_secondary TEXT;
ALTER TABLE devices ADD COLUMN identity_mismatch_detected_at TIMESTAMPTZ;

-- ---------------------------------------------------------------------
-- 4. C4 section 4.2 target-model modifiers -- never a devices/endpoints
-- row of their own (CP-D6), grouping/display labels only.
-- cluster_member_ref is set on both members by peer-follow (PF-2) only
-- when the peer's own read names the first device back; virtual_system_ref
-- stays unused by this movement (single-device add never resolves a VSX
-- context) but the column is added now so a physical row never needs a
-- second migration merely to carry a modifier this contract already names
-- (contract section 10, "C1 successor migration -- the target-modifier
-- columns C4 section 4.2 names but does not place").
-- ---------------------------------------------------------------------

ALTER TABLE devices ADD COLUMN cluster_member_ref TEXT;
ALTER TABLE devices ADD COLUMN virtual_system_ref TEXT;

CREATE INDEX idx_devices_cluster_member_ref ON devices(cluster_member_ref)
    WHERE cluster_member_ref IS NOT NULL;

-- ---------------------------------------------------------------------
-- 5. Peer-follow outcome (PF-2, PF-3): recorded on the first device even
-- when no unit forms, so "peer named, not confirmed" is a first-class,
-- visible outcome rather than a silent absence of a cluster_member_ref.
-- ---------------------------------------------------------------------

ALTER TABLE devices ADD COLUMN peer_follow_outcome TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE devices ADD CONSTRAINT chk_devices_peer_follow_outcome
    CHECK (peer_follow_outcome IN ('NONE', 'CORROBORATED', 'NOT_CONFIRMED'));
ALTER TABLE devices ALTER COLUMN peer_follow_outcome DROP DEFAULT;

ALTER TABLE devices ADD COLUMN peer_follow_reason TEXT;

-- No grant changes: ui2_app's INSERT/UPDATE/DELETE grant on devices (V1
-- section 7) already covers every column this file adds.
