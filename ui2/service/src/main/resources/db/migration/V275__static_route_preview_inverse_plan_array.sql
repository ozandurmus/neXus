-- V275 -- Reconcile static_route_preview.inverse_plan_json CHECK constraint with JSON array serialization
-- (PO Decision Record: Option A -- V275 + srp- end-to-end).
--
-- V252 created static_route_preview with CHECK (jsonb_typeof(inverse_plan_json) = 'object'), while
-- StaticRoutePreviewService.submitPreview() and StaticRoutePreviewJobExecutor.execute() serialize
-- List<CompiledPreviewStep> as a JSON array ([...]). This migration:
--   1. Drops V252's inline 'object' CHECK constraint on static_route_preview.inverse_plan_json.
--   2. Converts any existing placeholder '{}'::jsonb rows to '[]'::jsonb.
--   3. Adds and validates a named CHECK constraint enforcing jsonb_typeof(inverse_plan_json) = 'array'.
--
-- Expand-safe (MigrationAdditiveFenceTest, V266/V270/V274 pattern) and repeatable inside one rolled-back transaction.
SELECT set_config('app.actor_fingerprint', 'migration:V275_static_route_preview_inverse_plan_array', true);
SELECT set_config('app.action_id', 'static_route_preview_schema_by_migration', true);

DO $inv$
DECLARE v_name TEXT;
BEGIN
    FOR v_name IN SELECT conname FROM pg_constraint
                  WHERE conrelid = 'public.static_route_preview'::regclass
                    AND contype = 'c'
                    AND pg_get_constraintdef(oid) LIKE '%inverse_plan_json%'
                    AND pg_get_constraintdef(oid) LIKE '%''object''%' LOOP
        EXECUTE format('ALTER TABLE static_route_preview DROP CONSTRAINT %I', v_name);
    END LOOP;
END
$inv$;

UPDATE static_route_preview
SET inverse_plan_json = '[]'::jsonb
WHERE inverse_plan_json = '{}'::jsonb;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.static_route_preview'::regclass
          AND conname = 'chk_static_route_preview_inverse_plan_array'
    ) THEN
        ALTER TABLE static_route_preview
            ADD CONSTRAINT chk_static_route_preview_inverse_plan_array
            CHECK (jsonb_typeof(inverse_plan_json) = 'array') NOT VALID;
    END IF;
END
$$;

ALTER TABLE static_route_preview
    VALIDATE CONSTRAINT chk_static_route_preview_inverse_plan_array;
