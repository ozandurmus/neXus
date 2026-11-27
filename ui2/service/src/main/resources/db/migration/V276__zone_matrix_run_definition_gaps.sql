-- V276 -- Zone matrix, delivery step 4b-2: persist zone_definition_gaps on durable evaluation runs
-- (docs/design/ZONE_MATRIX_CONTRACT.md sections 3.3, 7.1, 8.1 and 8.2, FROZEN 2026-11-13).
--
-- Additive column on zone_matrix_run storing the JSON array of rules touching unassigned private
-- ranges (RFC 1918 / IPv6 ULA) computed by ZoneMatrixEvaluator during the run. NULL on pre-V276
-- rows and on RUNNING / FAILED rows; populated atomically during the RUNNING -> COMPLETE transition.
--
-- fn_zone_matrix_run_guard (V267) allows setting this column during RUNNING -> COMPLETE and continues
-- to forbid modifying any column once state is COMPLETE or FAILED. Table-level SELECT/INSERT/UPDATE
-- grants on zone_matrix_run (V267) apply to the new column automatically. No audit trigger is added
-- (zone_matrix_run is excluded by design per V267 and section 8.2).

ALTER TABLE zone_matrix_run
    ADD COLUMN zone_definition_gaps JSONB
        CHECK (zone_definition_gaps IS NULL OR jsonb_typeof(zone_definition_gaps) = 'array');
