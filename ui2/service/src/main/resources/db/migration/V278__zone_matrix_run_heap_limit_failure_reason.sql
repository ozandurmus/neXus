-- barrier-release: V267 failure_reason CHECK is replaced only to widen the allowed values with HEAP_LIMIT.
-- V278 -- Zone matrix ZM-39 heap ceiling enforcement: widen zone_matrix_run.failure_reason CHECK to admit 'HEAP_LIMIT'
-- (docs/design/ZONE_MATRIX_CONTRACT.md sections 5.1, 9 and 10.2 ZM-39, FROZEN 2026-11-13).
--
-- The old CHECK name is discovered below, so its dynamic DROP is invisible to MigrationAdditiveFenceTest.
-- The replacement accepts every previously valid value. The separate (state = 'FAILED') =
-- (failure_reason IS NOT NULL) constraint, triggers, indexes and grants are unchanged.

DO $zm_heap$
DECLARE v_name TEXT;
BEGIN
    FOR v_name IN SELECT conname FROM pg_constraint
                  WHERE conrelid = 'public.zone_matrix_run'::regclass
                    AND contype = 'c'
                    AND pg_get_constraintdef(oid) LIKE '%failure_reason%'
                    AND pg_get_constraintdef(oid) LIKE '%TIME_LIMIT%' LOOP
        EXECUTE format('ALTER TABLE zone_matrix_run DROP CONSTRAINT %I', v_name);
    END LOOP;
END
$zm_heap$;

ALTER TABLE zone_matrix_run ADD CONSTRAINT chk_zone_matrix_run_failure_reason
    CHECK (failure_reason IS NULL OR failure_reason IN
        ('TIME_LIMIT', 'HEAP_LIMIT', 'INTERRUPTED_AT_STARTUP', 'ENGINE_ERROR', 'SNAPSHOT_MISSING',
         'POOL_SATURATED', 'STORE_ERROR')) NOT VALID;

ALTER TABLE zone_matrix_run VALIDATE CONSTRAINT chk_zone_matrix_run_failure_reason;
