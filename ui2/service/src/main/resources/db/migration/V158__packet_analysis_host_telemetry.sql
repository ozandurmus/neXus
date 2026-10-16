-- V158 -- Packet Analysis host telemetry (expert report stage B). A case can carry server-side text files (sar, vmstat,
-- pidstat, tc/softnet snapshots, syslog) next to its captures. They are operator-uploaded evidence like the captures:
-- stored only in the analysis volume (<root>/cases/<caseId>/telemetry/<id>.txt), kept with the case for the same 7 days
-- and removed by the same retention pruner (the pruner deletes the whole case directory). This schema keeps only their
-- identity (name, size, digest), what the content sniffer detected, and the clock alignment the user confirmed. File
-- content, host names and message text never enter PostgreSQL or the audit log.
--
-- Additive: one new table with its audit trigger and redaction classification, and three new ledger events. The only
-- existing object touched is the CHECK on packet_analysis_ledger.event, which is replaced by the same list plus
-- TELEMETRY_UPLOADED, TELEMETRY_ALIGNED and TELEMETRY_DELETED (every old value is kept; no row changes). No module row,
-- owner, capability mapping or job shape changes.
SELECT set_config('app.actor_fingerprint', 'system:migration', true);
SELECT set_config('app.action_id', 'packet_analysis_host_telemetry', true);
SELECT pg_advisory_xact_lock(294611);

-- 1. Attachments. The telemetry id doubles as the storage key's stem and, in the ledger, as capture_id.
CREATE TABLE packet_analysis_host_telemetry (
    telemetry_id        TEXT        PRIMARY KEY CHECK (telemetry_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    case_id             TEXT        NOT NULL REFERENCES packet_analysis_case(case_id) ON DELETE CASCADE,
    display_name        TEXT        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    size_bytes          BIGINT      NOT NULL CHECK (size_bytes > 0),
    sha256              TEXT        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- File name inside the case's telemetry directory; generated, never derived from the uploaded name.
    storage_key         TEXT        NOT NULL UNIQUE CHECK (storage_key ~ '^[0-9a-f-]{36}\.txt$'),
    -- What the content sniffer found at upload (closed set) and, when it found nothing, why (closed code).
    detected_format     TEXT        NOT NULL CHECK (detected_format IN ('sar', 'vmstat', 'pidstat', 'snapshots', 'syslog', 'unknown')),
    recognition_note    TEXT        CHECK (recognition_note IS NULL OR recognition_note ~ '^[A-Z][A-Z0-9_]{0,59}$'),
    -- Clock alignment. A NULL zone means "not confirmed": the report reads the file as UTC and says so.
    time_zone           TEXT        CHECK (time_zone IS NULL OR time_zone ~ '^[A-Za-z0-9_+:/-]{1,40}$'),
    offset_seconds      INTEGER     NOT NULL DEFAULT 0 CHECK (offset_seconds BETWEEN -86400 AND 86400),
    -- Local time (in time_zone) of the file's first line, for files that state no date or no time of their own.
    anchor_local        TEXT        CHECK (anchor_local IS NULL OR anchor_local ~ '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{1,3})?$'),
    uploaded_by         TEXT        NOT NULL,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    aligned_by          TEXT,
    aligned_at          TIMESTAMPTZ
);
CREATE INDEX idx_packet_analysis_host_telemetry_case ON packet_analysis_host_telemetry(case_id);

-- 2. Audit (C1 §3.5) and redaction: names, digests and storage keys reach audit_log as digests only.
CREATE TRIGGER trg_audit_packet_analysis_host_telemetry
    AFTER INSERT OR UPDATE OR DELETE ON packet_analysis_host_telemetry
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('telemetry_id');

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('packet_analysis_host_telemetry', 'display_name', 2, 'uploaded file name; free text that may name a customer, host or site'),
    ('packet_analysis_host_telemetry', 'sha256', 2, 'content digest of raw evidence; identifies the file'),
    ('packet_analysis_host_telemetry', 'storage_key', 2, 'location of raw evidence on the analysis volume'),
    ('packet_analysis_host_telemetry', 'anchor_local', 2, 'operator-typed time of the first line; ties the file to an incident time')
ON CONFLICT (table_name, column_name) DO NOTHING;

GRANT SELECT, INSERT, UPDATE, DELETE ON packet_analysis_host_telemetry TO ui2_app;

-- 3. Ledger events. The list is V154's, plus the three telemetry events; the constraint is found by what it checks
-- because V154 created it unnamed.
DO $$ DECLARE event_check TEXT; BEGIN
 SELECT conname INTO STRICT event_check FROM pg_constraint
 WHERE conrelid = 'packet_analysis_ledger'::regclass AND contype = 'c'
   AND pg_get_constraintdef(oid) LIKE '%CASE_CREATED%' AND pg_get_constraintdef(oid) LIKE '%CASE_PRUNED%';
 EXECUTE format('ALTER TABLE packet_analysis_ledger DROP CONSTRAINT %I', event_check);
END $$;
ALTER TABLE packet_analysis_ledger ADD CONSTRAINT packet_analysis_ledger_event_check
    CHECK (event IN ('CASE_CREATED', 'CAPTURE_UPLOADED', 'ANALYSIS_REQUESTED', 'REPORT_VIEWED', 'RAW_VIEWED',
                     'CAPTURE_DOWNLOADED', 'CASE_DELETED', 'CASE_PRUNED',
                     'TELEMETRY_UPLOADED', 'TELEMETRY_ALIGNED', 'TELEMETRY_DELETED'));
