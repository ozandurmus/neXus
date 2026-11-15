-- V230 -- support bundle collection, steps S1 (core) and S2 (Check Point light reads)
-- (docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md, FROZEN 2026-11-15;
-- docs/design/PO_DECISION_RECORD_2026_11_15_SUPPORT_BUNDLE_COLLECTION.md).
--
--   1. support_bundle_request / support_bundle_item: one request per device, one item per artefact kind. The extension
--      columns of the first design are not carried: PO 2026-11-15 (Q2) replaced them with delete-after-download and a
--      14-day expiry with a warning first (downloaded_at, expiry_warned_at).
--   2. backup_artefact admits artefact_class 'support_bundle' (V190 pattern); a support bundle artefact always expires.
--      It is excluded from the backup / configuration storage budget in the application queries (JooqRetentionQueries).
--   3. Audit: trg_audit_support_bundle_request (insert and update), trg_audit_support_bundle_item (terminal states
--      only) and audit_support_bundle_download(), which the service calls before the first byte is streamed.
--      audit_redaction_policy lists every free-text column.
--   4. Gate rows for the Check Point light reads (PO approved the literals exactly: C0-C7), and the two cpdata_collector literals the PO
--      approved later the same day (--list, --run); both are gate rows only, no executor uses them in this step.
--
-- Dry-run in BEGIN/ROLLBACK on the live database before deploy is an engineer step. Repeatable inside one rolled-back
-- transaction.
SELECT set_config('app.actor_fingerprint', 'migration:V230_support_bundle_collection', true);
SELECT set_config('app.action_id', 'support_bundle_schema_by_migration', true);

-- 1. Tables.
CREATE TABLE IF NOT EXISTS support_bundle_request (
    id                  UUID        PRIMARY KEY,
    device_id           TEXT        NOT NULL,
    vendor              TEXT        NOT NULL CHECK (vendor IN ('check_point', 'palo_alto', 'fortinet', 'cisco_asa')),
    platform_role       TEXT        NOT NULL,
    run_mode            TEXT        NOT NULL CHECK (run_mode IN ('NOW', 'AT')),
    run_at              TIMESTAMPTZ NOT NULL,
    case_ref            TEXT        CHECK (case_ref IS NULL OR case_ref ~ '^[A-Za-z0-9_-]{1,40}$'),
    reason              TEXT        NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 8 AND 500),
    requested_by        TEXT        NOT NULL,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ NOT NULL,
    expiry_warned_at    TIMESTAMPTZ,
    downloaded_at       TIMESTAMPTZ,
    downloaded_by       TEXT,
    download_count      INT         NOT NULL DEFAULT 0 CHECK (download_count >= 0),
    deleted_by          TEXT,
    deleted_at          TIMESTAMPTZ,
    deleted_reason      TEXT        CHECK (deleted_reason IS NULL OR char_length(btrim(deleted_reason)) BETWEEN 8 AND 500),
    job_id              TEXT        REFERENCES jobs(job_id),
    work_dir_path       TEXT        CHECK (work_dir_path IS NULL OR work_dir_path ~ '^/var/log/nexus-sb-[a-z0-9]{12}$'),
    work_dir_cleaned    BOOLEAN     NOT NULL DEFAULT TRUE,
    precheck_json       JSONB       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(precheck_json) = 'object'),
    state               TEXT        NOT NULL CHECK (state IN (
                            'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED', 'DELETED')),
    outcome             TEXT        CHECK (outcome IS NULL OR outcome IN (
                            'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'NOT_STARTED_DEVICE_BUSY')),
    CONSTRAINT support_bundle_request_deleted_has_actor CHECK (state <> 'DELETED' OR deleted_by IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_support_bundle_active_per_device
    ON support_bundle_request (device_id) WHERE state IN ('QUEUED', 'RUNNING');
CREATE INDEX IF NOT EXISTS idx_support_bundle_request_device_time
    ON support_bundle_request (device_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_support_bundle_request_expiry
    ON support_bundle_request (expires_at) WHERE state IN ('COMPLETED', 'PARTIAL');
CREATE INDEX IF NOT EXISTS idx_support_bundle_request_job
    ON support_bundle_request (job_id) WHERE job_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS support_bundle_item (
    id                  UUID        PRIMARY KEY,
    request_id          UUID        NOT NULL REFERENCES support_bundle_request(id) ON DELETE CASCADE,
    ordinal             INT         NOT NULL CHECK (ordinal BETWEEN 1 AND 32),
    kind                TEXT        NOT NULL CHECK (kind IN (
                            'cpdata_collector_present', 'cp_hcp_latest', 'cp_gaia_extras', 'cp_hcp_run', 'cp_cpinfo',
                            'pan_tsf', 'asa_crashinfo', 'asa_show_tech', 'fgt_crashlog', 'fgt_tac_report')),
    vs_id               INT         CHECK (vs_id IS NULL OR vs_id BETWEEN 0 AND 4095),
    domain_ref          TEXT,
    state               TEXT        NOT NULL CHECK (state IN (
                            'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'REFUSED_PRECHECK',
                            'UNSUPPORTED', 'TIMED_OUT', 'TOO_LARGE', 'DIGEST_MISMATCH',
                            'OUTCOME_UNKNOWN', 'CLEANUP_FAILED', 'REDACTION_FAILED', 'CANCELLED', 'FAILED')),
    outcome_code        TEXT        CHECK (outcome_code IS NULL OR outcome_code ~ '^[A-Z0-9_]{1,64}$'),
    outcome_detail      TEXT        CHECK (outcome_detail IS NULL OR char_length(outcome_detail) <= 500),
    archive_entry_name  TEXT        CHECK (archive_entry_name IS NULL OR archive_entry_name ~ '^[A-Za-z0-9_./-]{1,160}$'),
    artefact_id         TEXT        REFERENCES backup_artefact(artefact_id),
    size_bytes          BIGINT      CHECK (size_bytes IS NULL OR size_bytes >= 0),
    sha256              TEXT        CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    redaction_ruleset   TEXT        NOT NULL DEFAULT 'none',
    gate_ids_used       TEXT[]      NOT NULL DEFAULT '{}',
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_support_bundle_item_kind
    ON support_bundle_item (request_id, kind, COALESCE(vs_id, -1), COALESCE(domain_ref, ''));
CREATE INDEX IF NOT EXISTS idx_support_bundle_item_request ON support_bundle_item (request_id, ordinal);
CREATE INDEX IF NOT EXISTS idx_support_bundle_item_artefact ON support_bundle_item (artefact_id) WHERE artefact_id IS NOT NULL;

-- 2. The artefact class (V190 pattern for the class check; the report shape check is left as it is).
ALTER TABLE backup_artefact DROP CONSTRAINT IF EXISTS backup_artefact_artefact_class_check;
ALTER TABLE backup_artefact ADD CONSTRAINT backup_artefact_artefact_class_check
    CHECK (artefact_class IN ('configuration', 'backup', 'system_log', 'report', 'support_bundle'));
ALTER TABLE backup_artefact DROP CONSTRAINT IF EXISTS backup_artefact_support_bundle_shape_check;
ALTER TABLE backup_artefact ADD CONSTRAINT backup_artefact_support_bundle_shape_check
    CHECK (artefact_class <> 'support_bundle' OR expires_at IS NOT NULL);
CREATE INDEX IF NOT EXISTS idx_backup_artefact_support_bundle_expiry
    ON backup_artefact (expires_at) WHERE artefact_class = 'support_bundle';

-- 3a. Audit capture: every request insert and update; an item only when it reaches a terminal state.
DROP TRIGGER IF EXISTS trg_audit_support_bundle_request ON support_bundle_request;
CREATE TRIGGER trg_audit_support_bundle_request
    AFTER INSERT OR UPDATE ON support_bundle_request
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('id');
DROP TRIGGER IF EXISTS trg_audit_support_bundle_item ON support_bundle_item;
CREATE TRIGGER trg_audit_support_bundle_item
    AFTER UPDATE OF state ON support_bundle_item
    FOR EACH ROW
    WHEN (NEW.state NOT IN ('QUEUED', 'RUNNING') AND OLD.state IS DISTINCT FROM NEW.state)
    EXECUTE FUNCTION fn_audit_capture('id');

-- 3b. One audit row per download, written by the service before the first byte (fn_audit_capture's own rules).
CREATE OR REPLACE FUNCTION audit_support_bundle_download(p_request_id UUID)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_actor  TEXT := current_setting('app.actor_fingerprint', true);
    v_action TEXT := current_setting('app.action_id', true);
    v_row    JSONB;
BEGIN
    IF v_actor IS NULL OR v_actor = '' OR v_action IS NULL OR v_action = '' THEN
        RAISE EXCEPTION 'audit_context_missing on support_bundle_request.id: actor or action not set in this transaction';
    END IF;
    IF v_action <> 'support_bundle_retrieve' THEN
        RAISE EXCEPTION 'invalid support bundle audit action';
    END IF;
    SELECT fn_audit_redact('support_bundle_request', to_jsonb(r)) INTO v_row
      FROM support_bundle_request r WHERE r.id = p_request_id;
    IF v_row IS NULL THEN
        RAISE EXCEPTION 'support_bundle_request_not_found';
    END IF;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, before_state, after_state,
                          correlation_run_id)
    VALUES ('support_bundle_request', p_request_id::text, 'UPDATE', v_actor, v_action, v_row, v_row,
            current_setting('app.correlation_run_id', true));
END;
$$;
REVOKE ALL ON FUNCTION audit_support_bundle_download(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_support_bundle_download(UUID) TO ui2_app;

-- 3c. Free-text and reference columns are redacted in audit_log before and after states.
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('support_bundle_request', 'reason', 1, 'Operator justification may contain environment names'),
    ('support_bundle_request', 'deleted_reason', 1, 'Operator early deletion justification'),
    ('support_bundle_request', 'case_ref', 1, 'External vendor support case reference'),
    ('support_bundle_request', 'work_dir_path', 2, 'Device work directory name'),
    ('support_bundle_item', 'outcome_detail', 1, 'Precheck or failure detail string')
ON CONFLICT (table_name, column_name) DO NOTHING;

GRANT SELECT, INSERT, UPDATE ON support_bundle_request TO ui2_app;
GRANT SELECT, INSERT, UPDATE ON support_bundle_item TO ui2_app;

-- 4. Gate rows. PO approval 2026-11-15 (docs/design/PO_DECISION_RECORD_2026_11_15_SUPPORT_BUNDLE_COLLECTION.md): C0-C7
-- exactly as in the design section 6; every Check Point literal carries the CP_ENV prefix (no login shell,
-- CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md 6.1).
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields, source_document_pointer)
VALUES
    ('cp_sb_cpdata_collector_present', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; command -v cpdata_collector',
     'read', 'SIGNED_OFF', 30, 'none', 'once per run', 'one session per run',
     'not found or an error: the item records ABSENT, nothing else changes', 'none; the path of an installed tool only',
     '["exitStatus", "present"]'::jsonb, 'docs/design/PO_DECISION_RECORD_2026_11_15_SUPPORT_BUNDLE_COLLECTION.md (Q3, C0)'),
    ('cp_sb_cpu_multi', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cpstat os -f multi_cpu',
     'read', 'SIGNED_OFF', 30, 'none', 'once per run', 'one session per run',
     'an error or an unexpected shape: the CPU value is UNKNOWN and no heavy item may rely on it', 'none',
     '["exitStatus", "bytes"]'::jsonb, 'docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6 (C1); PO approval 2026-11-15'),
    ('cp_sb_df_var_log', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; df -P /var/log',
     'read', 'SIGNED_OFF', 30, 'none', 'once per run', 'one session per run',
     'an unparseable answer: free space is UNKNOWN and no heavy item may rely on it', 'none',
     '["exitStatus", "bytes"]'::jsonb, 'docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6 (C2); PO approval 2026-11-15'),
    ('cp_sb_show_asset_all', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; clish -c ''show asset all''',
     'read', 'SIGNED_OFF', 60, 'none', 'once per run', 'one session per run',
     'an error: the item records the command as UNSUPPORTED, the other reads still run', 'identity (serial numbers)',
     '["exitStatus", "bytes"]'::jsonb, 'docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6 (C3); PO approval 2026-11-15'),
    ('cp_sb_show_version_all', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; clish -c ''show version all''',
     'read', 'SIGNED_OFF', 30, 'none', 'once per run', 'one session per run',
     'an error: the item records the command as UNSUPPORTED, the other reads still run', 'none',
     '["exitStatus", "bytes"]'::jsonb, 'docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6 (C4); PO approval 2026-11-15'),
    ('cp_sb_cphaprob_stat', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cphaprob stat',
     'read', 'SIGNED_OFF', 30, 'none', 'once per run', 'one session per run',
     'an error (a standalone gateway has no cluster): the item records the command as UNSUPPORTED', 'identity (cluster member names)',
     '["exitStatus", "bytes"]'::jsonb, 'docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6 (C5); PO approval 2026-11-15'),
    ('cp_sb_ls_hcp_last', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -lgo --time-style=+%s /var/log/hcp/last/',
     'read', 'SIGNED_OFF', 30, 'none', 'once per run', 'one session per run',
     'a missing directory: the item records ABSENT', 'file names carry host names; masked on ingest',
     '["exitStatus", "fileCount"]'::jsonb, 'docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6 (C6); PO approval 2026-11-15'),
    ('cp_sb_sftp_get_hcp', 'check_point', 'cp_gaia_gateway', 'not_applicable', 'SFTP_GET',
     'sftp get of /var/log/hcp/last/<listed name>; name pattern [A-Za-z0-9_.-]+\.tar\.gz',
     'read', 'SIGNED_OFF', 300, 'none', 'once per run', 'same session (SFTP channel)',
     'a missing file or a path not printed by C6 in the same run: nothing is fetched; over 16 MiB: TOO_LARGE, nothing stored', 'report content: stored encrypted as received, never shown to aiview',
     '["sizeBytes", "sha256"]'::jsonb, 'docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6 (C7); PO approval 2026-11-15'),
    ('cp_sb_cpdata_collector_list', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cpdata_collector --list',
     'read', 'SIGNED_OFF', 60, 'none', 'once per run', 'one session per run',
     'an error or an unexpected shape: UNSUPPORTED; APPROVED here, built in a later lane (no executor uses it yet)', 'none',
     '["exitStatus", "bytes"]'::jsonb, 'docs/design/PO_DECISION_RECORD_2026_11_15_SUPPORT_BUNDLE_COLLECTION.md (cpdata_collector --list, PO approval relayed 2026-11-15)'),
    ('cp_sb_cpdata_collector_run', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cd %1$s && setsid nohup bash --noprofile --norc -c "timeout 1800 cpdata_collector --run -p %1$s/cpsdc.tgz -t 3 -m 100 > %1$s/cpsdc.log 2>&1; echo \$? > %1$s/cpsdc.rc" </dev/null >/dev/null 2>&1 &',
     'recovery-write', 'SIGNED_OFF', 60, 'none', 'once per run, never retried', 'one session per run',
     'rc file records the failure; APPROVED here (BW-2 transient artefact), built in a later lane (no executor uses it yet)', 'secret-bearing (collector archive)',
     '["exitStatus"]'::jsonb, 'docs/design/PO_DECISION_RECORD_2026_11_15_SUPPORT_BUNDLE_COLLECTION.md (cpdata_collector --run, PO approval relayed 2026-11-15)')
ON CONFLICT (gate_id) DO NOTHING;
