-- V190 -- Reports R1 (docs/design/REPORTS_R1_CONTRACT.md, FROZEN 2026-11-05): one table, report_run, and the artefact
-- class 'report' for generated files in the existing encrypted artefact store.
--
-- The design's section 4.7 SQL is not authority (contract header): there is no report_schedule table and no
-- notification_routes change in R1; those are R2.
--
--   1. report_run: one row per generated file (PDF, XLSX or CSV). The on-screen preview is a read and is not stored;
--      'JSON' stays in the format vocabulary because the contract lists it. template_id is the closed R1 list.
--      parameters_json holds the date range and the framework (no identity); data_as_of_json holds per-plane evidence
--      timestamps and coverage counts (no identity). requested_by is the actor fingerprint, as everywhere else.
--   2. backup_artefact admits artefact_class 'report' (vendor 'nexus', device_id 'platform:reports'; V34 already dropped
--      the device foreign key). A report artefact always carries expires_at (30 days), and the worker's retention
--      janitor removes it once expired, like the other classes (removed ledger row, encrypted file deleted).
--   3. Audit (contract "Audit"): exactly one audit row per generate and per download.
--        generate -- trg_audit_report_run fires only when a run reaches SUCCEEDED or FAILED, so the row carries the
--                    template, format, projection, parameters and artefact id (QUEUED and RUNNING are not audited);
--        download -- audit_report_download(run_id) writes one audit_log row with the run as both before and after
--                    state (nothing changed; the action id says what happened). The service calls it before the
--                    first byte is streamed and refuses the download if it fails.
--      Both take the actor and action from the transaction's audit context and fail closed without it.
--
-- Dry-run in BEGIN/ROLLBACK on the live database before deploy is an engineer step (contract "Schema").
SELECT set_config('app.actor_fingerprint', 'migration:V190_reports_r1_report_run', true);
SELECT set_config('app.action_id', 'reports_r1_schema_by_migration', true);

CREATE TABLE IF NOT EXISTS report_run (
    run_id           UUID        PRIMARY KEY,
    template_id      TEXT        NOT NULL CHECK (template_id IN ('RPT-01', 'RPT-02', 'RPT-03', 'RPT-05')),
    state            TEXT        NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    projection_mode  TEXT        NOT NULL CHECK (projection_mode IN ('MASKED', 'RAW')),
    format           TEXT        NOT NULL CHECK (format IN ('JSON', 'PDF', 'XLSX', 'CSV')),
    parameters_json  JSONB       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(parameters_json) = 'object'),
    data_as_of_json  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    artefact_id      TEXT        REFERENCES backup_artefact(artefact_id),
    plaintext_bytes  BIGINT      CHECK (plaintext_bytes IS NULL OR plaintext_bytes BETWEEN 0 AND 26214400),
    error_code       TEXT        CHECK (error_code IS NULL OR error_code ~ '^[A-Z0-9_]{1,64}$'),
    requested_by     TEXT        NOT NULL,
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT report_run_succeeded_has_artefact CHECK (state <> 'SUCCEEDED' OR format = 'JSON' OR artefact_id IS NOT NULL),
    CONSTRAINT report_run_failed_has_code CHECK (state <> 'FAILED' OR error_code IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_report_run_requested_at ON report_run (requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_report_run_template ON report_run (template_id, requested_at DESC);

-- 2. The artefact class and its vendor (V160 pattern: drop whatever check names artefact_class, add the full list).
DO $$
DECLARE c text;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'backup_artefact'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%artefact_class%' LOOP
    EXECUTE format('ALTER TABLE backup_artefact DROP CONSTRAINT %I', c);
  END LOOP;
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'backup_artefact'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%vendor%' LOOP
    EXECUTE format('ALTER TABLE backup_artefact DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE backup_artefact ADD CONSTRAINT backup_artefact_artefact_class_check
    CHECK (artefact_class IN ('configuration', 'backup', 'system_log', 'report'));
ALTER TABLE backup_artefact ADD CONSTRAINT backup_artefact_vendor_check
    CHECK (vendor IN ('check_point', 'palo_alto', 'infoblox', 'radware', 'fortinet', 'cisco_asa', 'pulse_secure',
        'bluecoat', 'nexus'));
-- A report is neXus's own artefact and nothing else is: the class and the vendor go together, and a report expires.
ALTER TABLE backup_artefact ADD CONSTRAINT backup_artefact_report_shape_check
    CHECK ((artefact_class = 'report') = (vendor = 'nexus')
        AND (artefact_class <> 'report' OR (expires_at IS NOT NULL AND device_id = 'platform:reports')));
CREATE INDEX IF NOT EXISTS idx_backup_artefact_report_expiry
    ON backup_artefact (expires_at) WHERE artefact_class = 'report';

-- 3a. One audit row per generate: on the transition to a terminal state only.
CREATE TRIGGER trg_audit_report_run
    AFTER UPDATE OF state ON report_run
    FOR EACH ROW
    WHEN (NEW.state IN ('SUCCEEDED', 'FAILED') AND OLD.state IS DISTINCT FROM NEW.state)
    EXECUTE FUNCTION fn_audit_capture('run_id');

-- 3b. One audit row per download, written by the service before the first byte (fn_audit_capture's own rules).
CREATE OR REPLACE FUNCTION audit_report_download(p_run_id UUID)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_actor  TEXT := current_setting('app.actor_fingerprint', true);
    v_action TEXT := current_setting('app.action_id', true);
    v_row    JSONB;
BEGIN
    IF v_actor IS NULL OR v_actor = '' OR v_action IS NULL OR v_action = '' THEN
        RAISE EXCEPTION 'audit_context_missing on report_run.run_id: actor or action not set in this transaction';
    END IF;
    IF v_action <> 'report_download' THEN
        RAISE EXCEPTION 'invalid report audit action';
    END IF;
    SELECT fn_audit_redact('report_run', to_jsonb(r)) INTO v_row FROM report_run r WHERE r.run_id = p_run_id;
    IF v_row IS NULL THEN
        RAISE EXCEPTION 'report_run_not_found';
    END IF;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, before_state, after_state,
                          correlation_run_id)
    VALUES ('report_run', p_run_id::text, 'UPDATE', v_actor, v_action, v_row, v_row,
            current_setting('app.correlation_run_id', true));
END;
$$;
REVOKE ALL ON FUNCTION audit_report_download(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_report_download(UUID) TO ui2_app;

-- The service inserts a run and moves it to its terminal state; nothing deletes a run (an expired one keeps its row,
-- its artefact is removed by the retention janitor).
GRANT SELECT, INSERT, UPDATE ON report_run TO ui2_app;
