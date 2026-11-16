-- V240 -- support bundle collection: vendor upload (step S8) and the leftover work directory cleanup
-- (docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md, FROZEN 2026-11-15;
-- docs/design/PO_DECISION_RECORD_2026_11_15_SUPPORT_BUNDLE_COLLECTION.md; PO decisions of 2026-11-15 and 2026-11-16).
--
--   1. support_bundle_upload: one row per upload of a stored bundle (or selected items) to a vendor case or a generic SFTP
--      host. PO Q9: no four-eyes; the same security_admin requests, downloads and uploads, so there is no approval column.
--      The credential (case token, ticket password, case email, generic password) is typed per upload and is NEVER a column:
--      it lives in service memory for the transfer only. The row records what was sent, how many bytes, the SHA-256 and the
--      remote answer. The row insert is the audited "start" (committed before the first byte); the move to a final state is
--      the audited "end". Progress updates (bytes_sent, heartbeat_at) do not write audit rows.
--   2. support_bundle_upload_host_key: the host keys seen per destination. A changed key is recorded and warned about on the
--      upload row (host_key_changed); a connection is never refused over it (PO rule: hardening must not break connections).
--   3. support_bundle_request cleanup_* columns: the admin-confirmed "remove leftover work directory" (PO approved; the one
--      existing approved gate literal C20, nothing new). The work directory name still comes from work_dir_path only.
--
-- Dry-run in BEGIN/ROLLBACK on the live database before deploy is an engineer step. Idempotent.
SELECT set_config('app.actor_fingerprint', 'migration:V240_support_bundle_upload_and_cleanup', true);
SELECT set_config('app.action_id', 'support_bundle_schema_by_migration', true);

-- 1. Upload rows.
CREATE TABLE IF NOT EXISTS support_bundle_upload (
    id                  UUID        PRIMARY KEY,
    request_id          UUID        NOT NULL REFERENCES support_bundle_request(id) ON DELETE CASCADE,
    item_ids            UUID[]      NOT NULL DEFAULT '{}',
    destination_kind    TEXT        NOT NULL CHECK (destination_kind IN (
                            'cisco_cxd_https', 'cisco_cxd_sftp', 'cisco_cxd_scp', 'fortinet_sftp', 'paloalto_scp', 'generic_sftp')),
    destination_host    TEXT        NOT NULL CHECK (char_length(destination_host) BETWEEN 1 AND 253),
    destination_port    INT         NOT NULL CHECK (destination_port BETWEEN 1 AND 65535),
    remote_user         TEXT        NOT NULL CHECK (char_length(remote_user) BETWEEN 1 AND 128),
    remote_name         TEXT        NOT NULL CHECK (remote_name ~ '^[A-Za-z0-9._-]{1,160}$'),
    case_ref            TEXT        CHECK (case_ref IS NULL OR case_ref ~ '^[A-Za-z0-9_-]{1,40}$'),
    reason              TEXT        NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 8 AND 500),
    requested_by        TEXT        NOT NULL,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    heartbeat_at        TIMESTAMPTZ,
    owner               TEXT,
    state               TEXT        NOT NULL CHECK (state IN ('RUNNING', 'COMPLETED', 'FAILED', 'INTERRUPTED')),
    attempts            INT         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    bytes_total         BIGINT      CHECK (bytes_total IS NULL OR bytes_total >= 0),
    bytes_sent          BIGINT      NOT NULL DEFAULT 0 CHECK (bytes_sent >= 0),
    sha256              TEXT        CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    remote_status       TEXT        CHECK (remote_status IS NULL OR char_length(remote_status) <= 200),
    error_code          TEXT        CHECK (error_code IS NULL OR error_code ~ '^[A-Z0-9_]{1,64}$'),
    error_detail        TEXT        CHECK (error_detail IS NULL OR char_length(error_detail) <= 500),
    host_key_fingerprint TEXT       CHECK (host_key_fingerprint IS NULL OR char_length(host_key_fingerprint) <= 120),
    host_key_changed    BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_support_bundle_upload_running
    ON support_bundle_upload (request_id) WHERE state = 'RUNNING';
CREATE INDEX IF NOT EXISTS idx_support_bundle_upload_request
    ON support_bundle_upload (request_id, requested_at DESC);

-- Audit: the insert (start) and every state change (end); never the heartbeat.
DROP TRIGGER IF EXISTS trg_audit_support_bundle_upload ON support_bundle_upload;
CREATE TRIGGER trg_audit_support_bundle_upload
    AFTER INSERT OR UPDATE OF state ON support_bundle_upload
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('id');

-- 2. Host keys seen per destination (SHA-256 of the key blob, base64 as ssh-keygen prints it).
CREATE TABLE IF NOT EXISTS support_bundle_upload_host_key (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    host                TEXT        NOT NULL,
    port                INT         NOT NULL,
    fingerprint_sha256  TEXT        NOT NULL,
    key_type            TEXT        NOT NULL,
    first_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_support_bundle_upload_host_key UNIQUE (host, port, fingerprint_sha256)
);
-- Audit: each first sighting of a key (a later sighting only moves last_seen_at and is not an event).
DROP TRIGGER IF EXISTS trg_audit_support_bundle_upload_host_key ON support_bundle_upload_host_key;
CREATE TRIGGER trg_audit_support_bundle_upload_host_key
    AFTER INSERT ON support_bundle_upload_host_key
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('id');

-- 3. Cleanup of a leftover work directory.
ALTER TABLE support_bundle_request ADD COLUMN IF NOT EXISTS cleanup_job_id TEXT;
ALTER TABLE support_bundle_request ADD COLUMN IF NOT EXISTS cleanup_requested_by TEXT;
ALTER TABLE support_bundle_request ADD COLUMN IF NOT EXISTS cleanup_requested_at TIMESTAMPTZ;
ALTER TABLE support_bundle_request ADD COLUMN IF NOT EXISTS cleanup_reason TEXT;
ALTER TABLE support_bundle_request ADD COLUMN IF NOT EXISTS cleanup_state TEXT;
ALTER TABLE support_bundle_request ADD COLUMN IF NOT EXISTS cleanup_outcome TEXT;
ALTER TABLE support_bundle_request DROP CONSTRAINT IF EXISTS support_bundle_request_cleanup_state_check;
ALTER TABLE support_bundle_request ADD CONSTRAINT support_bundle_request_cleanup_state_check
    CHECK (cleanup_state IS NULL OR cleanup_state IN ('REQUESTED', 'DONE', 'FAILED'));
ALTER TABLE support_bundle_request DROP CONSTRAINT IF EXISTS support_bundle_request_cleanup_reason_check;
ALTER TABLE support_bundle_request ADD CONSTRAINT support_bundle_request_cleanup_reason_check
    CHECK (cleanup_reason IS NULL OR char_length(btrim(cleanup_reason)) BETWEEN 8 AND 500);
ALTER TABLE support_bundle_request DROP CONSTRAINT IF EXISTS support_bundle_request_cleanup_outcome_check;
ALTER TABLE support_bundle_request ADD CONSTRAINT support_bundle_request_cleanup_outcome_check
    CHECK (cleanup_outcome IS NULL OR cleanup_outcome ~ '^[A-Z0-9_]{1,64}$');

-- 4. Free text and references redacted in audit_log.
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('support_bundle_upload', 'reason', 1, 'Operator upload justification'),
    ('support_bundle_upload', 'case_ref', 1, 'External vendor support case reference'),
    ('support_bundle_upload', 'remote_user', 1, 'Vendor case number or remote account name'),
    ('support_bundle_upload', 'destination_host', 2, 'Upload destination host (free text for a generic SFTP server)'),
    ('support_bundle_upload', 'remote_name', 2, 'Remote file name'),
    ('support_bundle_upload', 'remote_status', 2, 'Remote server answer'),
    ('support_bundle_upload', 'error_detail', 1, 'Upload failure detail string'),
    ('support_bundle_upload', 'host_key_fingerprint', 2, 'Host key fingerprint of the destination'),
    ('support_bundle_upload_host_key', 'host', 2, 'Upload destination host'),
    ('support_bundle_upload_host_key', 'fingerprint_sha256', 2, 'Host key fingerprint of the destination'),
    ('support_bundle_request', 'cleanup_reason', 1, 'Operator work directory cleanup justification')
ON CONFLICT (table_name, column_name) DO NOTHING;

GRANT SELECT, INSERT, UPDATE ON support_bundle_upload TO ui2_app;
GRANT SELECT, INSERT, UPDATE ON support_bundle_upload_host_key TO ui2_app;
