-- V18 -- the Check Point gateway backup movement: gate_registry seed rows
-- for docs/design/CP_BACKUP_COMMAND_GATE_ENTRIES.md (14H section 5's seven
-- literals; entry 5's SFTP fetch carries no row of its own, per that
-- document's own "entry 5's gate is NOT_APPLICABLE" section), and the
-- backup_artefact_retrieval audit table 14I OR-3 requires.
--
-- Additive to V1-V17 (append-only; none of them is edited). No new
-- backup_artefact/artefact_retention_ledger schema is needed here -- V17
-- already carries the C7 manifest shape this movement stores through.

-- ---------------------------------------------------------------------
-- 1. gate_registry seed rows (14H section 5's gate consequence: SIGNED_OFF
-- for the allowlisted pilot device only, mirroring V16's own pattern
-- exactly -- inserted here directly since no seeder runs at startup).
-- docs/design/CP_BACKUP_COMMAND_GATE_ENTRIES.md carries the full ten-item
-- record per entry; these columns are the runtime projection.
-- ---------------------------------------------------------------------

SELECT set_config('app.actor_fingerprint', 'migration:V18_cp_gateway_backup', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_backup_show_diskspace', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'clish -c "show diskspace"', 'read', 'SIGNED_OFF', 30, 'none', 'once per run, before the submit',
     'one session held for the whole run',
     'CONFIRM-ON-HARDWARE (BK-7): if the Clish form fails, the Expert fallback df -P /var/log becomes primary',
     'none', '[]'::jsonb, 'docs/design/CP_BACKUP_COMMAND_GATE_ENTRIES.md entry 1'),

    ('cp_backup_add_backup_local', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'clish -c "add backup local"', 'recovery-write', 'SIGNED_OFF', 60,
     'never auto-retried (class-1): a second submission would start a second concurrent backup',
     'once per run', 'one session held for the whole run',
     'a vendor refusal (snapshot in progress, an open management client) is reported as the failure reason, never retried (BK-8)',
     'none', '[]'::jsonb, 'docs/design/CP_BACKUP_COMMAND_GATE_ENTRIES.md entry 2'),

    ('cp_backup_show_backup_status', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'clish -c "show backup status"', 'read', 'SIGNED_OFF', 30, 'none',
     'polled on a fixed interval for the duration of one run only', 'same session as the submit',
     'non-terminal output is a normal mid-run poll result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_BACKUP_COMMAND_GATE_ENTRIES.md entry 3'),

    ('cp_backup_show_backups', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'clish -c "show backups"', 'read', 'SIGNED_OFF', 30, 'none', 'at most once per run, if ever issued',
     'same session as the submit',
     'governed for 14H section 5 completeness; not issued by this movement''s own executor flow', 'none',
     '[]'::jsonb, 'docs/design/CP_BACKUP_COMMAND_GATE_ENTRIES.md entry 4'),

    ('cp_backup_archive_digest', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC', 'sha256sum <name>', 'read',
     'SIGNED_OFF', 60, 'none', 'once per run', 'same session as the submit',
     'a digest tool not on PATH is a failure, not a silent skip', 'none', '[]'::jsonb,
     'docs/design/CP_BACKUP_COMMAND_GATE_ENTRIES.md entry 6'),

    ('cp_backup_delete_backup_local', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'clish -c "delete backup <name>"', 'recovery-write', 'SIGNED_OFF', 60,
     'may be retried once: a failed delete is attempted a second time before the run records cleanup_failed',
     'at most twice per run', 'same session as the submit',
     'CONFIRM-ON-HARDWARE (BK-7): if the Clish form fails, the Expert fallback rm -f -- <exact path> becomes '
     || 'primary; a failed delete after the retry records cleanup_failed and marks the endpoint ineligible',
     'none', '[]'::jsonb, 'docs/design/CP_BACKUP_COMMAND_GATE_ENTRIES.md entry 7');

-- ---------------------------------------------------------------------
-- 2. backup_artefact.deviation_state -- 14I DV-1: "the run records
-- unchanged / changed / first against the previous backup artefact of the
-- same device". Nullable: the configuration path's own artefact_class =
-- 'configuration' rows leave it NULL (configuration already records its
-- own change_state on device_configuration_run, V16) -- only this
-- movement's artefact_class = 'backup' rows ever populate it. DV-3: never
-- a structural summary for the opaque Gaia archive, digest-level only.
-- ---------------------------------------------------------------------

ALTER TABLE backup_artefact
    ADD COLUMN deviation_state TEXT
        CHECK (deviation_state IN ('unchanged', 'changed', 'first'));

-- ---------------------------------------------------------------------
-- 3. backup_artefact_retrieval -- 14I OR-3: every operator retrieval
-- through the CLI is audited as its own typed action (actor, artefact id,
-- reason, destination path). The destination path is the operator's own
-- local output path (OR-2), never a device identity or a credential --
-- no redaction-policy row is needed for it (contrast wrapped_data_key on
-- backup_artefact, V17).
-- ---------------------------------------------------------------------

CREATE TABLE backup_artefact_retrieval (
    retrieval_id      TEXT        PRIMARY KEY,
    artefact_id        TEXT        NOT NULL REFERENCES backup_artefact(artefact_id),
    actor_fingerprint   TEXT        NOT NULL,
    reason               TEXT        NOT NULL,
    destination_path      TEXT        NOT NULL,
    retrieved_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_backup_artefact_retrieval_artefact_id ON backup_artefact_retrieval(artefact_id);

CREATE TRIGGER trg_audit_backup_artefact_retrieval
    AFTER INSERT OR UPDATE OR DELETE ON backup_artefact_retrieval
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('retrieval_id');

GRANT SELECT, INSERT ON backup_artefact_retrieval TO ui2_app;

-- ---------------------------------------------------------------------
-- 4. backup_endpoint_ineligibility -- WORKER.md: "record cleanup_failed as
-- a failure marking the endpoint ineligible if the delete fails." One row
-- per device currently marked ineligible (a fresh successful run's own
-- job executor clears the row again -- see BackupJobExecutor). Distinct
-- from devices.disabled: a cleanup failure is a backup-specific posture,
-- never a device-wide disable.
-- ---------------------------------------------------------------------

CREATE TABLE backup_endpoint_ineligibility (
    device_id     TEXT        PRIMARY KEY REFERENCES devices(device_id),
    reason         TEXT        NOT NULL,
    marked_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_audit_backup_endpoint_ineligibility
    AFTER INSERT OR UPDATE OR DELETE ON backup_endpoint_ineligibility
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('device_id');

GRANT SELECT, INSERT, DELETE ON backup_endpoint_ineligibility TO ui2_app;
