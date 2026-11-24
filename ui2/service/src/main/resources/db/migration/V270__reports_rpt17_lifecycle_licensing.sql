-- V270 -- Reports RPT-17 Lifecycle and licensing (docs/design/REPORTS_RPT17_LIFECYCLE_ADDENDUM_2026_11_24.md;
-- PO decision 2026-11-24): widen report_run.template_id to admit 'RPT-17'.
--
-- Expand-safe (MigrationAdditiveFenceTest, V266 pattern): replaces V190's inline template_id CHECK with a named
-- constraint that adds 'RPT-17' to the existing four R1 ids, added NOT VALID and validated immediately (report_run is
-- small and every existing row holds an R1 id). Older images write only R1 ids and render unknown history ids cleanly.
-- No table or column is added, so audit triggers, AuditPresentationAllowlist and module role grants are unchanged.
SELECT set_config('app.actor_fingerprint', 'migration:V270_reports_rpt17_lifecycle_licensing', true);
SELECT set_config('app.action_id', 'reports_rpt17_schema_by_migration', true);

DO $tpl$
DECLARE v_name TEXT;
BEGIN
    FOR v_name IN SELECT conname FROM pg_constraint
                  WHERE conrelid = 'public.report_run'::regclass AND contype = 'c'
                    AND pg_get_constraintdef(oid) LIKE '%template_id%' LOOP
        EXECUTE format('ALTER TABLE report_run DROP CONSTRAINT %I', v_name);
    END LOOP;
END
$tpl$;

ALTER TABLE report_run ADD CONSTRAINT chk_report_run_template_id
    CHECK (template_id IN ('RPT-01', 'RPT-02', 'RPT-03', 'RPT-05', 'RPT-17')) NOT VALID;
ALTER TABLE report_run VALIDATE CONSTRAINT chk_report_run_template_id;
