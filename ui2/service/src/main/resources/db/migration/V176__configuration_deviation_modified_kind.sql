-- V176 -- configuration_run_deviation_entry.kind gains MODIFIED (2026-10-23, Backups "Active major deviations").
--
-- A count-only deviation entry cannot see an edit that keeps a section's count (an interface address change, a
-- next-hop change). For Check Point the worker now compares the previous run's sanitized set lines with the new
-- run's, section by section, and records MODIFIED for a section whose count is unchanged but whose lines differ.
-- Counts only, never a value (V22 AC-6 unchanged). Design: docs/design/BACKUP_MAJOR_DEVIATIONS_2026_10_23.md.
SELECT set_config('app.actor_fingerprint', 'migration:V176_configuration_deviation_modified_kind', true);
SELECT set_config('app.action_id', 'configuration_deviation_kind_widening_by_migration', true);

ALTER TABLE configuration_run_deviation_entry
    DROP CONSTRAINT configuration_run_deviation_entry_kind_check;
ALTER TABLE configuration_run_deviation_entry
    ADD CONSTRAINT configuration_run_deviation_entry_kind_check
        CHECK (kind IN ('ADDED', 'REMOVED', 'RECOUNTED', 'MODIFIED'));

-- The Backups deviation read selects a week of primary runs fleet-wide.
CREATE INDEX IF NOT EXISTS idx_device_configuration_run_primary_collected
    ON device_configuration_run(collected_at) WHERE is_primary;
