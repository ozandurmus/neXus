-- V17 -- the C7 artefact-store manifest shape, per
-- docs/design/PO_DECISION_RECORD_2026_09_14H_BACKUP_STEP_SCOPE_PILOT_DEVICE_AND_THE_ASYNCHRONOUS_TRUTH.md
-- (FROZEN) BK-15..BK-18 and
-- docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md (FROZEN,
-- amendment A-1) sections 3.2-3.5 and 4.1.
--
-- Additive to V1-V16 (append-only; none of them is edited). No backup
-- capability, no restore path and no device contact are introduced here --
-- this migration only brings the artefact store's own manifest and ledger
-- shape up to the contract, ahead of anything using it to hold a backup.

-- ---------------------------------------------------------------------
-- 1. backup_artefact -- one manifest row per artefact this store holds,
-- for any artefact_class (BK-16 / C7 section 3.2). The configuration
-- path additionally records a row here (artefact_class = 'configuration')
-- alongside its own authoritative configuration_artefact table; a future
-- backup capability records artefact_class = 'backup' rows the same way.
--
-- Section 3.3's version-locking refusal (a check_point artefact with no
-- resolvable software_version is refused before any row is written) is
-- enforced in application code
-- (com.securityexpert.nexus.ui2.persistence.artefact.
-- BackupArtefactManifestRecord's own compact constructor), not by a
-- database CHECK -- the refusal must happen before any byte is written to
-- the store at all, which is earlier than any row this table could ever
-- reject.
--
-- recovery_volume_path is SERVER-SIDE ONLY (WORKER.md): no HTTP response
-- in this codebase may return it (BK-14, carried over from V16's own
-- artefact-path invariant).
-- ---------------------------------------------------------------------

CREATE TABLE backup_artefact (
    artefact_id           TEXT        PRIMARY KEY,
    device_id              TEXT        NOT NULL REFERENCES devices(device_id),
    virtual_system_ref      TEXT,
    artefact_class           TEXT        NOT NULL
        CHECK (artefact_class IN ('configuration', 'backup')),
    vendor                    TEXT        NOT NULL
        CHECK (vendor IN ('check_point', 'palo_alto')),
    software_version           TEXT,
    hostname_fingerprint         TEXT        NOT NULL,
    plaintext_sha256               TEXT        NOT NULL,
    plaintext_bytes                  BIGINT      NOT NULL,
    ciphertext_sha256                  TEXT        NOT NULL,
    ciphertext_bytes                     BIGINT      NOT NULL,
    key_id                                  TEXT        NOT NULL,
    wrapped_data_key                          BYTEA       NOT NULL,
    -- section 3.4: {"level_reached": "V1".."V4", "v3_status": ..., "restore_proven": bool}
    validation                                   JSONB       NOT NULL,
    retention_tier                                 TEXT        NOT NULL,
    expires_at                                        TIMESTAMPTZ,
    recovery_volume_path                                 TEXT        NOT NULL,
    created_at                                              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_backup_artefact_device_id ON backup_artefact(device_id, created_at);
CREATE INDEX idx_backup_artefact_artefact_class ON backup_artefact(artefact_class);

-- NXS-LOCAL-0170 authority decision: configuration_artefact (V16) stays the
-- authoritative table for the configuration-collection read path --
-- device_configuration_run.artefact_ref keeps its existing foreign key
-- into it, and every read/list query the Configuration screen already
-- issues is unchanged. It is not converted into a view over
-- backup_artefact: the two tables' column shapes differ enough (no
-- validation/retention/hostname-fingerprint/wrapped-key columns on
-- configuration_artefact) that a view would either hide real gaps behind
-- computed defaults or need one-to-one column parity that offers no
-- benefit over the row this migration already writes here. The
-- configuration job executor additionally records one backup_artefact
-- row per artefact (artefact_class = 'configuration') alongside its
-- configuration_artefact row, so every artefact the store holds -- of any
-- class -- has exactly one manifest row here regardless of which path
-- wrote it.
COMMENT ON TABLE configuration_artefact IS
    'Authoritative for the configuration-collection read path (device_configuration_run.artefact_ref '
    'still references it directly). backup_artefact (V17) is the separate, class-agnostic C7 manifest '
    'every artefact this store holds also gets a row in -- not a view over this table; see V17''s own '
    'comment for why.';

-- ---------------------------------------------------------------------
-- 2. artefact_retention_ledger -- BK-18 / C7 section 3.5: append-only from
-- its first row. Written whenever an artefact is created and whenever one
-- would be removed; the GFS policy numbers stay at the contract defaults
-- and nothing in this movement acts on them (no sweeper).
-- ---------------------------------------------------------------------

CREATE TABLE artefact_retention_ledger (
    ledger_id     TEXT        PRIMARY KEY,
    artefact_id    TEXT        NOT NULL REFERENCES backup_artefact(artefact_id),
    event           TEXT        NOT NULL
        CHECK (event IN ('created', 'removed')),
    retention_tier    TEXT        NOT NULL,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_artefact_retention_ledger_artefact_id ON artefact_retention_ledger(artefact_id);

-- ---------------------------------------------------------------------
-- 3. Audit triggers (C1 section 3.5), same generic fn_audit_capture V16 uses.
-- ---------------------------------------------------------------------

CREATE TRIGGER trg_audit_backup_artefact
    AFTER INSERT OR UPDATE OR DELETE ON backup_artefact
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('artefact_id');

CREATE TRIGGER trg_audit_artefact_retention_ledger
    AFTER INSERT OR UPDATE OR DELETE ON artefact_retention_ledger
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('ledger_id');

-- ---------------------------------------------------------------------
-- 4. Redaction (V5 contract): wrapped_data_key is live cryptographic
-- material outside its own key's lifecycle -- fn_audit_capture otherwise
-- captures the whole row (to_jsonb) into audit_log, which ui2_app can
-- read. Mirrors role_bindings.group_reference_encrypted's own tier-1 entry
-- exactly.
-- ---------------------------------------------------------------------

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('backup_artefact', 'wrapped_data_key', 1, 'wrapped per-artefact data key -- cryptographic material outside its key lifecycle');

-- ---------------------------------------------------------------------
-- 5. Grants. backup_artefact is a normal mutable table; the ledger is
-- append-only for ui2_app from its first row (BK-18) -- REVOKE then GRANT
-- SELECT, INSERT only, mirroring authz_decisions's own append-only grant
-- (V2) exactly.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE ON backup_artefact TO ui2_app;

REVOKE INSERT, UPDATE, DELETE ON artefact_retention_ledger FROM ui2_app;
GRANT SELECT, INSERT ON artefact_retention_ledger TO ui2_app;
