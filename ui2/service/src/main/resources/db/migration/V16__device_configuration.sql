-- V16 -- configuration collection storage, per
-- docs/design/PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md
-- (FROZEN) CG-1..CG-11, docs/design/PO_DECISION_RECORD_2026_09_13F_
-- COLLECTION_TRANSPORT_AND_IDENTITY_DECISIONS.md (FROZEN) CF-1..CF-3, and
-- docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md (FROZEN)
-- section 4's minimum viable artefact-store form.
--
-- Additive to V1-V15 (append-only; none of them is edited). No raw
-- configuration line is stored in this file or in any column it creates
-- (AGENTS.md raw-evidence law): device_configuration_run.sanitized_text
-- holds only the already-sanitized Check Point view (secret-bearing lines
-- withheld per CG-3); the untouched read exists only as encrypted bytes in
-- the filesystem-backed artefact store this migration's own
-- configuration_artefact row points at (artefact_ref, never the bytes
-- themselves).

-- ---------------------------------------------------------------------
-- 1. configuration_artefact -- one row per encrypted raw copy (C7 section
-- 4 minimum: envelope-encrypted, hash recorded, opaque ref; "never a
-- database row" governs the bytes, not this metadata row).
-- ---------------------------------------------------------------------

CREATE TABLE configuration_artefact (
    artefact_ref        TEXT        PRIMARY KEY,
    device_id           TEXT        NOT NULL REFERENCES devices(device_id),
    job_id              TEXT        NOT NULL REFERENCES jobs(job_id),
    vendor              TEXT        NOT NULL
        CHECK (vendor IN ('check_point', 'palo_alto')),
    plaintext_sha256     TEXT        NOT NULL,
    plaintext_bytes      BIGINT      NOT NULL,
    ciphertext_sha256    TEXT        NOT NULL,
    ciphertext_bytes     BIGINT      NOT NULL,
    compression          TEXT        NOT NULL
        CHECK (compression IN ('none', 'gzip')),
    key_id               TEXT        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 2. device_configuration_run -- one row per (device, job, read_kind).
-- A Check Point job produces one row (show_configuration); a Palo Alto job
-- produces three (active, effective_running, merged) sharing job_id.
-- effective_running/show_configuration are "primary" (CG-11's device list
-- and index view); active/merged are supplementary rows of their own read
-- kind (AC-2: "active and merged are recorded as their own read kinds").
-- ---------------------------------------------------------------------

CREATE TABLE device_configuration_run (
    run_id                TEXT        PRIMARY KEY,
    device_id             TEXT        NOT NULL REFERENCES devices(device_id),
    job_id                TEXT        NOT NULL REFERENCES jobs(job_id),
    collected_at           TIMESTAMPTZ NOT NULL,
    vendor                 TEXT        NOT NULL
        CHECK (vendor IN ('check_point', 'palo_alto')),
    read_kind              TEXT        NOT NULL
        CHECK (read_kind IN ('show_configuration', 'active', 'effective_running', 'merged')),
    is_primary              BOOLEAN     NOT NULL,
    -- CG-2: Check Point's canonical hash is SHA-256 over the `set` lines
    -- only (the raw text's own header timestamp changes every read). Palo
    -- Alto's structured XML reads carry no such header, so their canonical
    -- hash is the raw plaintext hash -- see DeviceConfigurationRepository's
    -- own class comment for this design note.
    canonical_hash          TEXT        NOT NULL,
    raw_hash                TEXT        NOT NULL,
    raw_bytes                BIGINT      NOT NULL,
    artefact_ref             TEXT        NOT NULL REFERENCES configuration_artefact(artefact_ref),
    withheld_line_count       INTEGER     NOT NULL DEFAULT 0,
    sanitized_text            TEXT,
    change_state              TEXT        NOT NULL
        CHECK (change_state IN ('changed', 'unchanged', 'first_run')),
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_configuration_run_device_collected
    ON device_configuration_run(device_id, read_kind, collected_at);

-- ---------------------------------------------------------------------
-- 3. device_configuration_index -- the sanitized index/category view
-- (CF-1, CG-6). "source" carries Palo Alto's per-element provenance
-- (CG-7a: tpl/dg/shared/local) counted per category (CG-7b); NULL for
-- Check Point, which has no such attribute.
-- ---------------------------------------------------------------------

CREATE TABLE device_configuration_index (
    index_id       TEXT        PRIMARY KEY,
    run_id          TEXT        NOT NULL REFERENCES device_configuration_run(run_id),
    context          TEXT        NOT NULL,
    section           TEXT        NOT NULL,
    source             TEXT
        CHECK (source IS NULL OR source IN ('tpl', 'dg', 'shared', 'local')),
    entry_count         INTEGER     NOT NULL
);

CREATE INDEX idx_device_configuration_index_run_id ON device_configuration_index(run_id);

-- ---------------------------------------------------------------------
-- 4. device_configuration_override -- CG-7b's per-device override list:
-- category + element path only, never a value (CG-7b: "never the value").
-- ---------------------------------------------------------------------

CREATE TABLE device_configuration_override (
    override_id     TEXT        PRIMARY KEY,
    run_id           TEXT        NOT NULL REFERENCES device_configuration_run(run_id),
    context            TEXT        NOT NULL,
    category            TEXT        NOT NULL,
    element_path         TEXT        NOT NULL,
    panorama_source        TEXT
);

CREATE INDEX idx_device_configuration_override_run_id ON device_configuration_override(run_id);

-- ---------------------------------------------------------------------
-- 5. panorama_assignment -- CG-7's own reduction of Panorama's config
-- (device serial -> template stack / templates / device groups), fixture-
-- driven only at this movement (no live Panorama target, 14F DR-4).
-- ---------------------------------------------------------------------

-- templates/device_groups are newline-joined lists, not a native array
-- column (this repository's jOOQ raw-SQL execute() pattern binds scalar
-- parameters only; no existing table here uses a native array column).

CREATE TABLE panorama_assignment (
    assignment_id     TEXT        PRIMARY KEY,
    device_serial       TEXT        NOT NULL,
    template_stack        TEXT,
    templates               TEXT        NOT NULL DEFAULT '',
    device_groups            TEXT        NOT NULL DEFAULT '',
    computed_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_panorama_assignment_device_serial ON panorama_assignment(device_serial);

-- ---------------------------------------------------------------------
-- 6. configuration_notification -- CG-7d's one-notification-per-device-
-- per-run surface. No functional notification surface exists yet in this
-- repository (WORKER.md scope note); this is the minimal table plus its
-- own read route/shell badge this movement adds for it.
-- ---------------------------------------------------------------------

-- override_paths is a newline-joined list, matching panorama_assignment's own note above.

CREATE TABLE configuration_notification (
    notification_id     TEXT        PRIMARY KEY,
    device_id             TEXT        NOT NULL REFERENCES devices(device_id),
    run_id                  TEXT        NOT NULL REFERENCES device_configuration_run(run_id),
    kind                      TEXT        NOT NULL
        CHECK (kind IN ('configuration_override_detected')),
    summary                    TEXT        NOT NULL,
    override_paths                TEXT        NOT NULL DEFAULT '',
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at                           TIMESTAMPTZ
);

CREATE INDEX idx_configuration_notification_device_id ON configuration_notification(device_id, created_at);

-- ---------------------------------------------------------------------
-- 7. Audit triggers (C1 section 3.5).
-- ---------------------------------------------------------------------

CREATE TRIGGER trg_audit_configuration_artefact
    AFTER INSERT OR UPDATE OR DELETE ON configuration_artefact
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('artefact_ref');

CREATE TRIGGER trg_audit_device_configuration_run
    AFTER INSERT OR UPDATE OR DELETE ON device_configuration_run
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('run_id');

CREATE TRIGGER trg_audit_device_configuration_index
    AFTER INSERT OR UPDATE OR DELETE ON device_configuration_index
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('index_id');

CREATE TRIGGER trg_audit_device_configuration_override
    AFTER INSERT OR UPDATE OR DELETE ON device_configuration_override
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('override_id');

CREATE TRIGGER trg_audit_panorama_assignment
    AFTER INSERT OR UPDATE OR DELETE ON panorama_assignment
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('assignment_id');

CREATE TRIGGER trg_audit_configuration_notification
    AFTER INSERT OR UPDATE OR DELETE ON configuration_notification
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('notification_id');

-- ---------------------------------------------------------------------
-- 8. Grants.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE
  ON configuration_artefact, device_configuration_run, device_configuration_index,
     device_configuration_override, panorama_assignment, configuration_notification
  TO ui2_app;

-- ---------------------------------------------------------------------
-- 9. gate_registry seed rows (14G section 5's gate consequence: SIGNED_OFF
-- on the Product Owner's hardware-run approval, mirroring V15's own
-- pattern exactly -- inserted here directly since no seeder runs at
-- startup). docs/design/CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md and
-- docs/design/PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md carry the full
-- ten-item record per entry; these columns are the runtime projection.
-- ---------------------------------------------------------------------

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    -- Check Point (CG-1): identity refresh (three reads) then the one Gaia
    -- configuration read, all bare Expert-shell literals -- no VSX
    -- repetition (host-level config, measured identical inside vsenv).
    ('cp_configuration_show_hostname', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'clish -c ''show hostname''', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'a hostname of zero length is not observed; treat empty output as a result',
     'none', '[]'::jsonb, 'docs/design/CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md entry 1'),
    ('cp_configuration_show_version_all', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'clish -c ''show version all''', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'a field this parser does not recognize is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md entry 2'),
    ('cp_configuration_cpstat_os_hw_info', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'clish -c ''cpstat os -f hw_info''', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'a field this parser does not recognize is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md entry 3'),
    ('cp_configuration_show_configuration', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'clish -c ''show configuration''', 'read', 'SIGNED_OFF', 60, 'none', 'once per device per run',
     'one session per device per run, no per-virtual-system repetition (VSX host measured identical)',
     'the read carries secret-bearing lines -- withheld in the sanitized view, encrypted at rest in the artefact store',
     'none', '[]'::jsonb, 'docs/design/CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md entry 4'),

    -- Palo Alto (CG-4): identity refresh, then active/effective-running/merged, in that order.
    ('pan_configuration_show_system_info', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<show><system><info/></system></show>', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'the one key-generation session per run', 'non-XML or error envelope is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md entry 1'),
    ('pan_configuration_active', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     'type=config&action=show&xpath=/config', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'the one key-generation session per run', 'the read carries secret-bearing leaves -- withheld in the view, '
     || 'encrypted at rest', 'none', '[]'::jsonb, 'docs/design/PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md entry 2'),
    ('pan_configuration_effective_running', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<show><config><effective-running/></config></show>', 'read', 'SIGNED_OFF', 120, 'none',
     'once per device per run', 'the one key-generation session per run',
     'the read carries secret-bearing leaves -- withheld in the view, encrypted at rest; response measured at 11.8 MB, '
     || 'streamed, never held whole', 'none', '[]'::jsonb,
     'docs/design/PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md entry 3'),
    ('pan_configuration_merged', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<show><config><merged/></config></show>', 'read', 'SIGNED_OFF', 60, 'none', 'once per device per run',
     'the one key-generation session per run', 'the read carries secret-bearing leaves -- withheld in the view, '
     || 'encrypted at rest', 'none', '[]'::jsonb, 'docs/design/PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md entry 4'),

    -- Panorama own configuration read: named per WORKER.md scope, gate
    -- entry recorded now, target wiring waits for 14F DR-4 management-
    -- server rows -- no capability spec issues this literal yet.
    ('pan_configuration_panorama_own_config', 'palo_alto', 'panorama', 'not_applicable', 'PAN_XML_API',
     'type=config&action=show&xpath=/config', 'read', 'SIGNED_OFF', 120, 'none', 'once per Panorama per run',
     'the one key-generation session per run', 'response measured at 84.8 MB, streamed, never held whole -- target '
     || 'wiring later (14F DR-4)', 'none', '[]'::jsonb,
     'docs/design/PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md entry 5, target wiring later');
