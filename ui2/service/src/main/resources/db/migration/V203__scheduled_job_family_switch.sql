-- V203 -- per-family on/off switch for scheduled jobs (PO 2026-11-10, end of development mode: "zamanlı işleri hepsini
-- açmanı istiyorum", and an on/off control for them in the UI). docs/design/PO_DECISION_RECORD_2026_11_10_SCHEDULED_JOB_SWITCHES.md.
--   1. scheduled_job_family: one row per scheduled job family, read by that family's scheduler at the start of every
--      tick. A family that is off submits nothing. The deployment switch UI2_SCHEDULING_ENABLED stays the hard master
--      off switch on top of these rows.
--   2. Audited by fn_audit_capture (actor, action, old and new values). The reason is operator free text: tier-2 redacted
--      in audit_redaction_policy (as failover_approval.reason); every other column is in AuditPresentationAllowlist.
--   3. All six families are seeded ON: the state the PO asked for on 2026-11-10.
--   4. An index for "last run" (the newest job a family's system actor submitted).
-- Every statement is repeatable, so the dry run can run the file twice inside one rolled-back transaction.
SELECT set_config('app.actor_fingerprint', 'migration:V203_scheduled_job_family_switch', true);
SELECT set_config('app.action_id', 'scheduled_job_family_seed_by_migration', true);

CREATE TABLE IF NOT EXISTS scheduled_job_family (
    family_key TEXT PRIMARY KEY CHECK (family_key IN ('discovery_refresh', 'nightly_inventory', 'configuration_refresh',
        'ha_readiness', 'scheduled_backup', 'system_log_collection')),
    enabled BOOLEAN NOT NULL DEFAULT true,
    reason TEXT NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 5 AND 500),
    changed_by TEXT,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE scheduled_job_family IS
    'Per-family on/off for scheduled jobs; each scheduler reads its row at the start of every tick (V203). UI2_SCHEDULING_ENABLED=false still stops all of them.';
COMMENT ON COLUMN scheduled_job_family.reason IS 'The reason the administrator gave for the last change (mandatory, 5-500 characters).';

-- Classified before the first row is written, so the seed's audit row already carries the digest, not the text.
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason)
SELECT 'scheduled_job_family', 'reason', 2, 'operator free text (scheduled job switch reason)'
WHERE NOT EXISTS (SELECT 1 FROM audit_redaction_policy WHERE table_name = 'scheduled_job_family' AND column_name = 'reason');

GRANT SELECT, INSERT, UPDATE ON scheduled_job_family TO ui2_app;

DROP TRIGGER IF EXISTS trg_audit_scheduled_job_family ON scheduled_job_family;
CREATE TRIGGER trg_audit_scheduled_job_family
    AFTER INSERT OR UPDATE OR DELETE ON scheduled_job_family
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('family_key');

-- Seeded after the trigger exists, so the audit log records where the initial state came from.
INSERT INTO scheduled_job_family (family_key, enabled, reason, changed_by) VALUES
    ('discovery_refresh', true, 'Seeded ON: PO 2026-11-10 ended development mode', 'migration:V203'),
    ('nightly_inventory', true, 'Seeded ON: PO 2026-11-10 ended development mode', 'migration:V203'),
    ('configuration_refresh', true, 'Seeded ON: PO 2026-11-10 ended development mode', 'migration:V203'),
    ('ha_readiness', true, 'Seeded ON: PO 2026-11-10 ended development mode', 'migration:V203'),
    ('scheduled_backup', true, 'Seeded ON: PO 2026-11-10 ended development mode', 'migration:V203'),
    ('system_log_collection', true, 'Seeded ON: PO 2026-11-10 ended development mode', 'migration:V203')
ON CONFLICT (family_key) DO NOTHING;

-- "Last run" per family: the newest job its system actor submitted (about 45 000 job rows on 2026-11-10).
CREATE INDEX IF NOT EXISTS idx_jobs_submitter_submitted_at ON jobs (submitted_by_actor_fingerprint, submitted_at DESC);
