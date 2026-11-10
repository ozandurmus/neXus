-- V200 -- License, contract and Live Patch facts read from devices (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md).
--
-- Three tables written only by the worker's inventory, from the approved lifecycle reads (V198, V199). They hold
-- parsed facts: a label, a state, an expiry date. They never hold a license key, a signature, an authcode, a serial
-- number, a bound address, a contract or account identifier, or any raw device output.
--
-- No audit trigger: collected rows, never operator-written; the inventory job row is the audited record (the same
-- rule as device_platform_facts, V46).

-- One row per device and read kind: the outcome of the latest attempt, so that "no rows" is never read as "no license".
CREATE TABLE device_lifecycle_read (
    device_id    TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    read_kind    TEXT        NOT NULL CHECK (length(read_kind) BETWEEN 1 AND 64),
    outcome      TEXT        NOT NULL CHECK (outcome IN ('COLLECTED', 'EMPTY', 'UNPARSED', 'REFUSED', 'FAILED', 'SKIPPED')),
    reason       TEXT        CHECK (reason IS NULL OR reason ~ '^[A-Za-z0-9_.:-]{1,80}$'),
    item_count   INTEGER     NOT NULL DEFAULT 0 CHECK (item_count >= 0),
    observed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, read_kind)
);

-- The items of the latest successful read of one kind on one device; replaced as a set on every successful read.
CREATE TABLE device_license_item (
    item_id        TEXT        PRIMARY KEY,
    device_id      TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    read_kind      TEXT        NOT NULL CHECK (length(read_kind) BETWEEN 1 AND 64),
    category       TEXT        NOT NULL CHECK (category IN ('LICENSE', 'SUBSCRIPTION', 'SUPPORT_CONTRACT', 'SMART_LICENSING')),
    label          TEXT        NOT NULL CHECK (label ~ '^[A-Za-z0-9 _./+()&,:-]{1,120}$'),
    state          TEXT        NOT NULL CHECK (state IN ('VALID', 'EXPIRED', 'PERMANENT', 'NOT_LICENSED', 'OUT_OF_COMPLIANCE',
                                   'UNREGISTERED', 'EVAL_EXPIRED', 'NOT_APPLICABLE', 'UNKNOWN')),
    expires_on     DATE,
    detail         TEXT        CHECK (detail IS NULL OR detail ~ '^[A-Za-z0-9 _./+()&,:=-]{1,160}$'),
    member_ref     TEXT        CHECK (member_ref IS NULL OR length(member_ref) BETWEEN 1 AND 255),
    evidence_grade TEXT        NOT NULL CHECK (evidence_grade IN ('DIRECT_DEVICE', 'MANAGER_REPORTED')),
    observed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX device_license_item_device ON device_license_item (device_id, read_kind);

-- Check Point Live Patch: one row per patch of the latest `cplp list` answer; replaced as a set.
CREATE TABLE device_live_patch_item (
    device_id     TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    patch_id      TEXT        NOT NULL CHECK (patch_id ~ '^[A-Za-z0-9_.:-]{1,80}$'),
    status        TEXT        NOT NULL CHECK (status IN ('armed', 'ready', 'reverted', 'jumbofix', 'unsupp', 'nopatch', 'nolib', 'UNKNOWN')),
    mode          TEXT        CHECK (mode IS NULL OR mode ~ '^[A-Za-z0-9_-]{1,24}$'),
    pids_running  INTEGER     CHECK (pids_running IS NULL OR pids_running >= 0),
    pids_total    INTEGER     CHECK (pids_total IS NULL OR pids_total >= 0),
    installed_at  TIMESTAMP,
    refs          TEXT[]      NOT NULL DEFAULT '{}',
    observed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, patch_id)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON device_lifecycle_read, device_license_item, device_live_patch_item TO ui2_app;
