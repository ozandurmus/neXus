-- V168 -- an administrator's manual device job is not held to the job windows (PO decision 2026-10-20,
-- docs/design/PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md).
--   1. jobs gains four nullable columns, written by ManualAdmissionRecord in the admission's own audited transaction
--      (so fn_audit_capture records them): window_bypass_role (security_admin | operation_admin) marks a job admitted
--      under the administrator's grant -- the claim statement may select it outside the windows;
--      submitted_in_business_hours, and -- inside business hours -- business_hours_ack_by/_at, who acknowledged the
--      "business hours" warning and when. Every existing row and every scheduled/automatic or operator/aiview job
--      keeps all four NULL: nothing about them changes.
--   2. Two checks keep the record coherent: the acknowledgement's actor and time come together, and a bypass job
--      submitted inside business hours always carries the acknowledgement.
--   3. A partial index serves the worker's "is any bypass job queued" read outside the windows.
-- No gate row, command, session or frequency changes here. Every statement is repeatable, so the dry run can run the
-- file twice inside one rolled-back transaction.
SELECT set_config('app.actor_fingerprint', 'migration:V168_admin_manual_window_bypass', true);

ALTER TABLE jobs ADD COLUMN IF NOT EXISTS window_bypass_role TEXT NULL;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS submitted_in_business_hours BOOLEAN NULL;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS business_hours_ack_by TEXT NULL;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS business_hours_ack_at TIMESTAMPTZ NULL;

ALTER TABLE jobs DROP CONSTRAINT IF EXISTS jobs_window_bypass_role_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_window_bypass_role_check
    CHECK (window_bypass_role IS NULL OR window_bypass_role IN ('security_admin', 'operation_admin'));

ALTER TABLE jobs DROP CONSTRAINT IF EXISTS jobs_business_hours_ack_pair_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_business_hours_ack_pair_check
    CHECK ((business_hours_ack_by IS NULL) = (business_hours_ack_at IS NULL));

ALTER TABLE jobs DROP CONSTRAINT IF EXISTS jobs_business_hours_ack_required_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_business_hours_ack_required_check
    CHECK (window_bypass_role IS NULL
        OR (submitted_in_business_hours IS NOT NULL
            AND (NOT submitted_in_business_hours OR business_hours_ack_at IS NOT NULL)));

DROP INDEX IF EXISTS jobs_window_bypass_requested;
CREATE INDEX jobs_window_bypass_requested ON jobs (capability_id)
    WHERE state = 'REQUESTED' AND window_bypass_role IS NOT NULL;

COMMENT ON COLUMN jobs.window_bypass_role IS
    'PO 2026-10-20: the admin role (security_admin | operation_admin) whose manual submission passed the job window; NULL = window-bound.';
COMMENT ON COLUMN jobs.submitted_in_business_hours IS
    'PO 2026-10-20: for a window-bypass job, whether it was submitted inside business hours (Europe/Istanbul).';
COMMENT ON COLUMN jobs.business_hours_ack_by IS
    'PO 2026-10-20: actor fingerprint that acknowledged the business-hours warning (inside business hours only).';
COMMENT ON COLUMN jobs.business_hours_ack_at IS
    'PO 2026-10-20: when the business-hours warning was acknowledged.';
