-- V277 -- Zone matrix evidence report ZM-01 through Reports R1 (docs/design/ZONE_MATRIX_CONTRACT.md 7.3 and 10.1
-- step 6, FROZEN 2026-11-13; ZM-28, ZM-31, ZM-32, ZM-38): widen report_run.template_id to admit 'ZM-01'.
--
-- Expand-safe (MigrationAdditiveFenceTest, V270 pattern): replaces the named template_id CHECK with one that adds
-- 'ZM-01' to the existing ids, added NOT VALID and validated immediately (report_run is small and every existing row
-- holds an admitted id). Older images write only their own ids and render unknown history ids cleanly.
-- No table or column is added, so audit triggers, AuditPresentationAllowlist and module role grants are unchanged.
SELECT set_config('app.actor_fingerprint', 'migration:V277_reports_zone_matrix_evidence', true);
SELECT set_config('app.action_id', 'reports_zone_matrix_evidence_schema_by_migration', true);

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
    CHECK (template_id IN ('RPT-01', 'RPT-02', 'RPT-03', 'RPT-05', 'RPT-17', 'ZM-01')) NOT VALID;
ALTER TABLE report_run VALIDATE CONSTRAINT chk_report_run_template_id;
