-- V154 -- Packet Analysis product shell (docs/design/PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md,
-- RATIFIED: §5 raw-evidence exception, §6 roles). Operator-uploaded captures are raw evidence kept 7 days in the
-- analysis volume only; this schema keeps case metadata, capture identity, run state and the MASKED summary.
-- Raw packets, report bodies and DuckDB files never enter PostgreSQL or the audit log.
--
-- Module ownership (2026-10-05 incident): this migration adds ONE module row, 'packet_analysis', owned by itself
-- (no fallback), and maps ONE capability, 'packet_analysis_run', to it. No existing module row, owner, generation,
-- drain flag or capability mapping changes. Until a packet-analysis pod heartbeats, packet_analysis_run jobs simply
-- wait in REQUESTED; no other job is affected.
SELECT set_config('app.actor_fingerprint', 'system:migration', true);
SELECT set_config('app.action_id', 'packet_analysis_shell', true);
SELECT pg_advisory_xact_lock(294611);

-- 1. Case, capture, run and the append-only ledger.
CREATE TABLE packet_analysis_case (
    case_id          TEXT        PRIMARY KEY,
    title            TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
    created_by       TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    state            TEXT        NOT NULL CHECK (state IN ('OPEN', 'QUEUED', 'ANALYZING', 'ANALYZED', 'FAILED')),
    total_bytes      BIGINT      NOT NULL DEFAULT 0 CHECK (total_bytes >= 0),
    -- Upload option (handover: "payload zeroing at upload -- PO to decide"); recorded and passed to the engine.
    payload_zeroing  BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Decision record §5: retained 7 days from upload, never longer.
    CONSTRAINT chk_packet_analysis_case_retention CHECK (expires_at = created_at + interval '7 days')
);
CREATE INDEX idx_packet_analysis_case_expiry ON packet_analysis_case(expires_at);

CREATE TABLE packet_analysis_capture (
    capture_id      TEXT        PRIMARY KEY,
    case_id         TEXT        NOT NULL REFERENCES packet_analysis_case(case_id) ON DELETE CASCADE,
    display_name    TEXT        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    capture_format  TEXT        NOT NULL CHECK (capture_format IN ('pcap', 'pcapng')),
    size_bytes      BIGINT      NOT NULL CHECK (size_bytes > 0),
    sha256          TEXT        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- File name inside the case directory; generated, never derived from the uploaded name.
    storage_key     TEXT        NOT NULL UNIQUE CHECK (storage_key ~ '^[0-9a-f-]{36}\.(pcap|pcapng)$'),
    uploaded_by     TEXT        NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_packet_analysis_capture_case ON packet_analysis_capture(case_id);

CREATE TABLE packet_analysis_run (
    job_id          TEXT        PRIMARY KEY REFERENCES jobs(job_id),
    case_id         TEXT        NOT NULL REFERENCES packet_analysis_case(case_id) ON DELETE CASCADE,
    state           TEXT        NOT NULL CHECK (state IN ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED')),
    requested_by    TEXT        NOT NULL,
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    engine_version  TEXT        CHECK (engine_version IS NULL OR engine_version ~ '^[A-Za-z0-9._-]{1,40}$'),
    -- The engine's masked projection only (no address, no name, no file name); the full report stays on the volume.
    masked_summary  JSONB       CHECK (masked_summary IS NULL OR jsonb_typeof(masked_summary) = 'object'),
    failure_reason  TEXT        CHECK (failure_reason IS NULL OR failure_reason ~ '^[A-Z][A-Z0-9_]{0,79}$')
);
CREATE INDEX idx_packet_analysis_run_case ON packet_analysis_run(case_id, requested_at DESC);

-- Decision record §6: every upload, analysis, raw view, download and deletion is recorded. Append-only for ui2_app
-- and keyed by opaque ids only, so it outlives the case it describes (like artefact_retention_ledger).
CREATE TABLE packet_analysis_ledger (
    ledger_id          TEXT        PRIMARY KEY,
    case_id            TEXT        NOT NULL,
    capture_id         TEXT,
    job_id             TEXT,
    event              TEXT        NOT NULL CHECK (event IN ('CASE_CREATED', 'CAPTURE_UPLOADED', 'ANALYSIS_REQUESTED',
                           'REPORT_VIEWED', 'RAW_VIEWED', 'CAPTURE_DOWNLOADED', 'CASE_DELETED', 'CASE_PRUNED')),
    actor_fingerprint  TEXT        NOT NULL,
    bytes              BIGINT      CHECK (bytes IS NULL OR bytes >= 0),
    occurred_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_packet_analysis_ledger_case ON packet_analysis_ledger(case_id, occurred_at);

-- 2. Audit (C1 §3.5): every mutation-bearing table carries trg_audit_<table>.
CREATE TRIGGER trg_audit_packet_analysis_case
    AFTER INSERT OR UPDATE OR DELETE ON packet_analysis_case
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('case_id');
CREATE TRIGGER trg_audit_packet_analysis_capture
    AFTER INSERT OR UPDATE OR DELETE ON packet_analysis_capture
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('capture_id');
CREATE TRIGGER trg_audit_packet_analysis_run
    AFTER INSERT OR UPDATE OR DELETE ON packet_analysis_run
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('job_id');
CREATE TRIGGER trg_audit_packet_analysis_ledger
    AFTER INSERT OR UPDATE OR DELETE ON packet_analysis_ledger
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('ledger_id');

-- Free text an operator types or a file carries, and values that identify capture content, reach audit_log as digests.
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('packet_analysis_case', 'title', 2, 'operator free text; may name a customer or site'),
    ('packet_analysis_capture', 'display_name', 2, 'uploaded file name; free text that may name a customer, host or site'),
    ('packet_analysis_capture', 'sha256', 2, 'content digest of raw evidence; identifies the capture'),
    ('packet_analysis_capture', 'storage_key', 2, 'location of raw evidence on the analysis volume')
ON CONFLICT (table_name, column_name) DO NOTHING;

GRANT SELECT, INSERT, UPDATE, DELETE ON packet_analysis_case TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON packet_analysis_capture TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON packet_analysis_run TO ui2_app;
REVOKE ALL ON packet_analysis_ledger FROM ui2_app;
GRANT SELECT, INSERT ON packet_analysis_ledger TO ui2_app;

-- 3. Job registration: packet_analysis_run targets a case (target_kind 'packet_analysis_case', target_ref = case_id).
-- The two existing shapes are kept verbatim (V14); the new one is admitted for this one capability only.
ALTER TABLE jobs DROP CONSTRAINT chk_jobs_target_shape;
ALTER TABLE jobs ADD CONSTRAINT chk_jobs_target_shape
    CHECK (
        (target_kind = 'device' AND target_device_id IS NOT NULL AND target_ref IS NULL)
        OR (target_kind = 'discovery_run' AND target_ref IS NOT NULL AND target_device_id IS NULL)
        OR (target_kind = 'packet_analysis_case' AND target_ref IS NOT NULL AND target_device_id IS NULL
            AND capability_id = 'packet_analysis_run')
    );

-- V151's mapping (V128 plus pan_syslog_measure), byte-for-byte, plus exactly one new branch.
CREATE OR REPLACE FUNCTION ui2_job_module(capability TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT CASE
 WHEN capability IN ('cp_policy_collect','pan_policy_collect') THEN 'policy'
 WHEN capability IN ('cp_gateway_backup','cp_spark_sftp_backup','cp_gaia_snapshot','pan_device_state_backup','cp_mds_export','https_vendor_backup','rdw_cc_config_backup','asa_config_backup','fgt_config_backup') THEN 'backup'
 WHEN capability IN ('device_confirm_check_point','device_confirm_palo_alto','device_confirm_https','device_confirm_cisco_asa','device_confirm_fortigate','cp_inventory_collect','pan_inventory_collect','https_inventory_collect','asa_inventory_collect','fgt_inventory_collect','cp_discovery_enumerate','pan_discovery_enumerate','rdw_discovery_enumerate','fmg_discovery_enumerate','bcmc_discovery_enumerate') THEN 'inventory'
 WHEN capability IN ('cp_failover_readiness','pan_failover_readiness','cp_cluster_failover','pan_cluster_failover') THEN 'failover'
 WHEN capability IN ('diagnostic_read','fmg_interface_detail','pan_syslog_measure') THEN 'diagnostics'
 WHEN capability IN ('cp_configuration_collect','pan_configuration_collect','fgt_configuration_collect','asa_configuration_collect','proxysg_configuration_collect') THEN 'configuration'
 WHEN capability = 'packet_analysis_run' THEN 'packet_analysis'
 ELSE NULL END
$$;

-- 4. Module row: the analysis worker owns packet_analysis_run and nothing else; no fallback to the general worker
-- (it has no engine), and no other row is touched.
DO $$ DECLARE module_check TEXT; BEGIN
 SELECT conname INTO STRICT module_check FROM pg_constraint
 WHERE conrelid = 'module_runtime_control'::regclass AND contype = 'c'
   AND pg_get_constraintdef(oid) LIKE '%''scheduler''%' AND pg_get_constraintdef(oid) NOT LIKE '%fallback_enabled%';
 EXECUTE format('ALTER TABLE module_runtime_control DROP CONSTRAINT %I', module_check);
END $$;
ALTER TABLE module_runtime_control ADD CONSTRAINT module_runtime_control_module_check
    CHECK (module IN ('policy','backup','inventory','failover','diagnostics','configuration','compliance','scheduler',
                      'general','packet_analysis'));
INSERT INTO module_runtime_control(module, effective_owner, fallback_enabled, last_reason)
VALUES ('packet_analysis', 'packet_analysis', FALSE, 'V154: dedicated analysis worker; owns packet_analysis_run only');
