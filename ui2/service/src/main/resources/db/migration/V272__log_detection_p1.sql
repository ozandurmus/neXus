-- V272 -- system-log detection engine, phase P1 (severity layer and hardware/kernel markers).
-- Authority: docs/design/LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md (FROZEN 2026-11-24) section 8.3 (what is stored),
-- section 7.2 (finding record), section 7.4 (coverage), section 10 phase P1; PO_DECISION_RECORD_2026_11_24_LOG_DETECTION_ENGINE.md.
-- This migration holds schema only. No table holds a log line, masked or not: evidence is counts, device-local times,
-- closed-vocabulary labels and pointers (chunk id, offset, length). Layer B (catalog, rarity, baselines) and layer C
-- (cards) have no tables here; their tables arrive with their phases.
--   1. log_detection_settings  one row: the P1 switch (off by default) and the end of the shadow period.
--   2. log_index_state         one row per (chunk, masking rule set, marker list version): what the indexer did.
--   3. log_event_count         evidence rows (device, source, marker, key token, bucket, count, <= 3 pointers).
--   4. log_coverage            one row per (device, source, collection job): the section 7.4 state, never "healthy by default".
--   5. log_finding             the finding record; severity by rule.
--   6. log_finding_action      operator acknowledgement events and the engine's REOPEN events (audited).
-- Audit (like V160): the operator-written tables (settings and finding actions) carry trg_audit_<table>. The four derived
-- tables are written by the service as a system job from the stored chunks and are recomputable; they are listed in
-- AuditCoverageCompletenessTest.NOT_MUTATION_BEARING with that reason (an audit row per evidence row would only copy
-- counts that the table already holds). A review-state change on log_finding is recorded by its log_finding_action row.
-- Every statement is repeatable (IF NOT EXISTS, ON CONFLICT DO NOTHING, drop-then-create), so the dry run can run the
-- file twice inside one rolled-back transaction.
SELECT set_config('app.actor_fingerprint', 'migration:V272_log_detection_p1', true);
SELECT set_config('app.action_id', 'log_detection_p1_by_migration', true);

-- 1. Settings (design section 10: "each phase is behind its own setting"; rollback = switch off, rows stay).
CREATE TABLE IF NOT EXISTS log_detection_settings (
    settings_id TEXT PRIMARY KEY CHECK (settings_id = 'default'),
    enabled BOOLEAN NOT NULL DEFAULT false,
    shadow_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);
COMMENT ON TABLE log_detection_settings IS
    'Log detection engine P1 switch. enabled=false (default) means the indexer does nothing; shadow_until in the future means findings are stored but shown only on the admin screen (design section 10).';

-- 2. Index state: idempotent per (chunk, masking rule set, marker list version) (design section 6.3).
CREATE TABLE IF NOT EXISTS log_index_state (
    chunk_id TEXT NOT NULL,
    masking_rule_set TEXT NOT NULL CHECK (masking_rule_set ~ '^[a-z0-9][a-z0-9._-]{0,31}$'),
    marker_list_version TEXT NOT NULL CHECK (marker_list_version ~ '^[a-z0-9][a-z0-9._-]{0,31}$'),
    device_id TEXT NOT NULL,
    source_key TEXT NOT NULL,
    job_id TEXT NOT NULL,
    log_day DATE NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('INDEXED', 'MASKING_UNAVAILABLE', 'UNREADABLE', 'PRUNED', 'LINE_TOO_LONG',
        'LINE_COUNT_MISMATCH', 'STORE_UNAVAILABLE', 'NOT_APPLICABLE', 'UNSUPPORTED')),
    lines BIGINT NOT NULL DEFAULT 0 CHECK (lines >= 0),
    matched BIGINT NOT NULL DEFAULT 0 CHECK (matched >= 0),
    unparsed BIGINT NOT NULL DEFAULT 0 CHECK (unparsed >= 0),
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chunk_id, masking_rule_set, marker_list_version)
);
CREATE INDEX IF NOT EXISTS log_index_state_device_source_day ON log_index_state(device_id, source_key, log_day);
CREATE INDEX IF NOT EXISTS log_index_state_job ON log_index_state(job_id);
COMMENT ON TABLE log_index_state IS 'What the log-analysis job did with one stored chunk; counters only, never a line.';
COMMENT ON COLUMN log_index_state.unparsed IS 'Lines of the chunk with no readable device-local time (the section 7.4 UNPARSED_HIGH input).';

-- 3. Evidence rows (design section 3.3, 3.4 rule 1: one row per device, marker, key token and bucket).
CREATE TABLE IF NOT EXISTS log_event_count (
    event_id TEXT PRIMARY KEY,
    chunk_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    source_key TEXT NOT NULL,
    marker_id TEXT NOT NULL CHECK (marker_id ~ '^[a-z][a-z0-9_.]{1,47}$'),
    key_token TEXT NOT NULL DEFAULT '' CHECK (key_token ~ '^[A-Za-z0-9#_.:-]{0,64}$'),
    bucket_start TIMESTAMP NOT NULL,
    time_basis TEXT NOT NULL DEFAULT 'DEVICE_LOCAL' CHECK (time_basis = 'DEVICE_LOCAL'),
    severity_weight NUMERIC(4, 2) NOT NULL CHECK (severity_weight >= 0.05 AND severity_weight <= 1),
    line_count INTEGER NOT NULL CHECK (line_count > 0),
    first_line_time TIMESTAMP NOT NULL,
    last_line_time TIMESTAMP NOT NULL,
    pointers JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(pointers) = 'array' AND jsonb_array_length(pointers) <= 3),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT log_event_count_unique UNIQUE (chunk_id, marker_id, key_token, bucket_start),
    CONSTRAINT log_event_count_times CHECK (last_line_time >= first_line_time)
);
CREATE INDEX IF NOT EXISTS log_event_count_device_marker ON log_event_count(device_id, marker_id, bucket_start);
CREATE INDEX IF NOT EXISTS log_event_count_source_marker_day ON log_event_count(source_key, marker_id, bucket_start);
COMMENT ON TABLE log_event_count IS
    'Evidence rows of the log detection engine: a counted occurrence of a marker in a bucket with up to three pointers. Not a finding by itself.';
COMMENT ON COLUMN log_event_count.key_token IS
    'Closed-vocabulary label (e.g. a memory slot label made of letters, digits, #, _ only) or a keyed opaque token (k- and 12 hex characters); equality only, never parsed (identity law). A process name is always a keyed token, never the name; the writer produces only this character set, so no line can make an evidence insert fail.';
COMMENT ON COLUMN log_event_count.pointers IS '[{chunk_id, offset, length}], at most 3; resolved to text only through the masked read path.';

-- 4. Coverage (design section 7.4): the state of one (device, source, collection job).
CREATE TABLE IF NOT EXISTS log_coverage (
    device_id TEXT NOT NULL,
    source_key TEXT NOT NULL,
    job_id TEXT NOT NULL,
    window_day DATE NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('EVALUATED', 'BASELINE_LEARNING', 'NOT_EVALUABLE', 'GAP', 'NOT_COLLECTED',
        'MASKING_UNAVAILABLE', 'UNPARSED_HIGH', 'UNSUPPORTED', 'EVALUATION_FAILED')),
    reason TEXT NOT NULL CHECK (reason ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    history_days INTEGER NOT NULL DEFAULT 0 CHECK (history_days >= 0),
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, source_key, job_id)
);
CREATE INDEX IF NOT EXISTS log_coverage_device_day ON log_coverage(device_id, window_day);
COMMENT ON TABLE log_coverage IS
    'Coverage state per device, source and collection window. A device with incomplete coverage is never shown healthy (design section 7.4).';

-- 5. Findings (design section 7.2 as far as P1 needs it; layer A only, no score, rarity or change factor yet).
CREATE TABLE IF NOT EXISTS log_finding (
    finding_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    source_key TEXT NOT NULL,
    layer TEXT NOT NULL CHECK (layer = 'A'),
    rule_id TEXT NOT NULL CHECK (rule_id ~ '^[a-z][a-z0-9._-]{1,47}$'),
    rule_version TEXT NOT NULL CHECK (rule_version ~ '^[a-z0-9][a-z0-9._-]{0,31}$'),
    kind TEXT NOT NULL CHECK (kind IN ('MARKER', 'VENDOR_SEVERITY')),
    marker_id TEXT NOT NULL CHECK (marker_id ~ '^[a-z][a-z0-9_.]{1,47}$'),
    key_token TEXT NOT NULL DEFAULT '' CHECK (key_token ~ '^[A-Za-z0-9#_.:-]{0,64}$'),
    window_day DATE NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    time_basis TEXT NOT NULL DEFAULT 'DEVICE_LOCAL' CHECK (time_basis = 'DEVICE_LOCAL'),
    severity TEXT NOT NULL CHECK (severity IN ('CRITICAL', 'ATTENTION', 'INFO', 'UNKNOWN')),
    severity_rule TEXT NOT NULL CHECK (severity_rule ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    severity_weight NUMERIC(4, 2) NOT NULL CHECK (severity_weight >= 0.05 AND severity_weight <= 1),
    provenance TEXT NOT NULL CHECK (provenance IN ('PROVISIONAL', 'MEASURED', 'VENDOR_DOC')),
    event_count BIGINT NOT NULL CHECK (event_count > 0),
    peak_bucket_count INTEGER NOT NULL CHECK (peak_bucket_count > 0),
    history_days INTEGER NOT NULL DEFAULT 0 CHECK (history_days >= 0),
    baseline_days INTEGER NOT NULL DEFAULT 0 CHECK (baseline_days >= 0),
    presence_days INTEGER NOT NULL DEFAULT 0 CHECK (presence_days >= 0),
    cohort_devices INTEGER NOT NULL DEFAULT 0 CHECK (cohort_devices >= 0),
    prevalence_devices INTEGER NOT NULL DEFAULT 0 CHECK (prevalence_devices >= 0),
    chronic BOOLEAN NOT NULL DEFAULT false,
    fleet_background BOOLEAN NOT NULL DEFAULT false,
    labels TEXT[] NOT NULL DEFAULT '{}'::text[] CHECK (labels <@ ARRAY['HISTORY_UNDER_7_DAYS', 'CHRONIC', 'FLEET_BACKGROUND',
        'PROVISIONAL_CAP']::text[]),
    incident_group TEXT NOT NULL CHECK (incident_group ~ '^[0-9a-f]{16}$'),
    coverage_state TEXT NOT NULL CHECK (coverage_state IN ('EVALUATED', 'BASELINE_LEARNING', 'NOT_EVALUABLE', 'GAP',
        'NOT_COLLECTED', 'MASKING_UNAVAILABLE', 'UNPARSED_HIGH', 'UNSUPPORTED', 'EVALUATION_FAILED')),
    pointers JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(pointers) = 'array' AND jsonb_array_length(pointers) <= 40),
    masking_rule_set TEXT NOT NULL,
    marker_list_version TEXT NOT NULL,
    config_version TEXT NOT NULL,
    review_state TEXT NOT NULL DEFAULT 'OPEN' CHECK (review_state IN ('OPEN', 'ACKNOWLEDGED', 'SUPPRESSED', 'CLOSED')),
    review_by TEXT,
    review_at TIMESTAMPTZ,
    first_raised_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT log_finding_unique UNIQUE (device_id, marker_id, key_token, window_day),
    CONSTRAINT log_finding_window CHECK (window_end >= window_start)
);
CREATE INDEX IF NOT EXISTS log_finding_device_day ON log_finding(device_id, window_day DESC);
CREATE INDEX IF NOT EXISTS log_finding_severity_day ON log_finding(severity, window_day DESC);
COMMENT ON TABLE log_finding IS
    'A record that one deterministic layer-A rule raised from evidence rows. Severity comes from rules; a statistical finding is never CRITICAL. No log text.';
COMMENT ON COLUMN log_finding.pointers IS '[{chunk_id, offset, length}] up to 40; resolved through the masked read path, "evidence expired" after chunk retention.';
COMMENT ON COLUMN log_finding.incident_group IS 'Findings of one device within 10 minutes share one incident group (design section 3.4 rule 2).';

-- 6. Operator actions on findings.
CREATE TABLE IF NOT EXISTS log_finding_action (
    action_id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('ACKNOWLEDGE', 'REOPEN')),
    actor TEXT NOT NULL,
    note TEXT CHECK (note IS NULL OR char_length(note) <= 500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS log_finding_action_finding ON log_finding_action(finding_id, created_at DESC);
COMMENT ON TABLE log_finding_action IS 'Acknowledgement events of log findings and the engine''s REOPEN events when a severity rise returns an acknowledged finding to OPEN (design section 7.3); the note is bounded text.';

-- ---------------------------------------------------------------------
-- Audit classification (UI2_0_B1_02A section 4): operator free text is redacted; everything else of the two audited
-- tables is listed in AuditRedactionPolicyTest.AUDITED_IN_FULL.
-- ---------------------------------------------------------------------
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('log_finding_action', 'note', 2, 'operator free text'),
    ('log_finding_action', 'actor', 2, 'actor fingerprint of the operator')
ON CONFLICT (table_name, column_name) DO NOTHING;

GRANT SELECT, INSERT, UPDATE, DELETE ON log_detection_settings TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON log_index_state TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON log_event_count TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON log_coverage TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON log_finding TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON log_finding_action TO ui2_app;

DROP TRIGGER IF EXISTS trg_audit_log_detection_settings ON log_detection_settings;
CREATE TRIGGER trg_audit_log_detection_settings
    AFTER INSERT OR UPDATE OR DELETE ON log_detection_settings
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('settings_id');
DROP TRIGGER IF EXISTS trg_audit_log_finding_action ON log_finding_action;
CREATE TRIGGER trg_audit_log_finding_action
    AFTER INSERT OR UPDATE OR DELETE ON log_finding_action
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('action_id');

-- Module-role grants (the V242 / V261 pattern, design section 4.3.2): the log-analysis job and the read routes run in ui2-service.
-- The service reads and writes all six tables; it reads system_log_chunk, system_log_run and system_log_gap under V242 already.
-- Nothing else touches them. The job, the findings and the settings are service-only.
DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT, UPDATE', 'log_detection_settings'),
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE', 'log_index_state'),
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE', 'log_event_count'),
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE', 'log_coverage'),
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE', 'log_finding'),
        ('ui2_service', 'SELECT, INSERT', 'log_finding_action')
    ) AS g(role_name, privs, tbl_name)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.%I TO %I', v_rec.privs, v_rec.tbl_name, v_rec.role_name);
        END IF;
    END LOOP;
END
$grants$;

-- The single settings row is seeded after its trigger exists, so the audit log records where the default came from.
INSERT INTO log_detection_settings(settings_id, enabled, updated_by) VALUES ('default', false, 'log-detection-migration')
ON CONFLICT (settings_id) DO NOTHING;
