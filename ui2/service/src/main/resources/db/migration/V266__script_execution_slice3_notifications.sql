-- V266: Script Execution slice 3 -- per-run syslog / SMTP notifications (docs/design/SCRIPT_EXECUTION_CONTRACT.md
-- sections 3.5 and 3.7 slice 3; docs/design/PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md).
--
-- 1. The per-script "on failure only" option widens the closed script.notify CHECK with two values,
--    'smtp_mail_on_failure' and 'both_mail_on_failure' (contract 3.5 ties the option to the mail only; the syslog line
--    is sent on every run end). Chosen over a new boolean column because no column is added to an audited table:
--    every unredacted column of a trg_audit_* table must also be listed in AuditPresentationAllowlist
--    (AuditPresentationAllowlistMatchesLiveSchemaTest), and this slice changes no allowlist. The API keeps the
--    four-value channel plus a separate boolean; the repository maps the pair onto the stored value.
-- 2. script_run_notification: the run-end notification outcome, one append-only row per finished run, written by
--    NotificationForwarder after its send attempt -- one state per channel (SENT / FAILED / SKIPPED / NOT_REQUESTED),
--    a short closed reason code for a FAILED or SKIPPED channel (never a message body, an address or a host) and the
--    time. A separate table, not script_run columns: recording a notification never updates the run row, so it can
--    never change the run's state (contract 3.5 "never fails the run"), and it adds no audited column (see 1). Like
--    notification_state (V100) it is delivery bookkeeping written by the system, declared not mutation-bearing in
--    AuditCoverageCompletenessTest; an append-only guard refuses UPDATE and DELETE.
-- 3. notification_state: the scan floor and watermark start now, so runs finished before this migration are not
--    notified retroactively.
-- No new notification_routes type: the mail goes to the global SMTP recipients (Settings > Notifications); a route type
-- would require every Settings client to send an eleventh route (NotificationSettings.problems()), and whether a script
-- mails at all is already the script record's own choice.
--
-- Audit redaction: no column is added to an audited table and nothing is added to audit_redaction_policy; the new
-- table carries only closed enums, closed codes, the opaque run id and a timestamp.
SELECT set_config('app.actor_fingerprint', 'migration:V266_script_execution_slice3_notifications', true);
SELECT set_config('app.action_id', 'script_execution_slice3_migration', true);

-- 1. script.notify: the V251 inline CHECK (named by PostgreSQL) is replaced by a named one with two more values. The
--    new CHECK only widens the closed set, so an older image (which writes the four V251 values) keeps working; it is
--    added NOT VALID (MigrationAdditiveFenceTest) and validated in the same migration (script is a small table and
--    every existing row holds a V251 value).
DO $notify$
DECLARE v_name TEXT;
BEGIN
    FOR v_name IN SELECT conname FROM pg_constraint
                  WHERE conrelid = 'public.script'::regclass AND contype = 'c'
                    AND pg_get_constraintdef(oid) LIKE '%notify%' LOOP
        EXECUTE format('ALTER TABLE script DROP CONSTRAINT %I', v_name);
    END LOOP;
END
$notify$;
ALTER TABLE script ADD CONSTRAINT chk_script_notify
    CHECK (notify IN ('syslog', 'smtp', 'both', 'none', 'smtp_mail_on_failure', 'both_mail_on_failure')) NOT VALID;
ALTER TABLE script VALIDATE CONSTRAINT chk_script_notify;

-- 2. The outcome record.
CREATE TABLE script_run_notification (
    job_id       TEXT        PRIMARY KEY REFERENCES script_run(job_id) ON DELETE RESTRICT,
    syslog       TEXT        NOT NULL CHECK (syslog IN ('SENT', 'FAILED', 'SKIPPED', 'NOT_REQUESTED')),
    syslog_code  TEXT        CHECK (syslog_code IS NULL OR syslog_code IN ('syslog_not_configured', 'send_failed')),
    smtp         TEXT        NOT NULL CHECK (smtp IN ('SENT', 'FAILED', 'SKIPPED', 'NOT_REQUESTED')),
    smtp_code    TEXT        CHECK (smtp_code IS NULL
                                    OR smtp_code IN ('smtp_not_configured', 'no_recipients', 'failure_only', 'send_failed')),
    notified_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A code explains a FAILED or SKIPPED channel, and only those.
    CONSTRAINT chk_script_run_notification_code_shape
        CHECK ((syslog IN ('FAILED', 'SKIPPED')) = (syslog_code IS NOT NULL)
               AND (smtp IN ('FAILED', 'SKIPPED')) = (smtp_code IS NOT NULL))
);

CREATE OR REPLACE FUNCTION fn_guard_script_run_notification_append_only() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
    RAISE EXCEPTION 'script_run_notification_append_only: a recorded notification outcome is immutable';
END;
$$;
REVOKE ALL ON FUNCTION fn_guard_script_run_notification_append_only() FROM PUBLIC;

CREATE TRIGGER trg_guard_script_run_notification_append_only
    BEFORE UPDATE OR DELETE ON script_run_notification
    FOR EACH ROW EXECUTE FUNCTION fn_guard_script_run_notification_append_only();

-- The forwarder's scan: finished runs, oldest first (an index only; no column is added to script_run).
CREATE INDEX IF NOT EXISTS idx_script_run_finished
    ON script_run (finished_at, job_id)
    WHERE finished_at IS NOT NULL;

-- 3. Scan floor and watermark.
INSERT INTO notification_state(key, value) VALUES
    ('script_run_notify_since', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')),
    ('script_run_notify_watermark', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
ON CONFLICT (key) DO NOTHING;

-- Grants, the V242 / V261 module-role pattern: the service's notification forwarder writes the outcome once and every
-- reader of the run ledger (service, scheduler) reads it. Nothing updates or deletes a row.
GRANT SELECT, INSERT ON script_run_notification TO ui2_app;

DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT', 'script_run_notification'),
        ('ui2_scheduler', 'SELECT', 'script_run_notification')
    ) AS g(role_name, privs, tbl_name)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.%I TO %I', v_rec.privs, v_rec.tbl_name, v_rec.role_name);
        END IF;
    END LOOP;
END
$grants$;
