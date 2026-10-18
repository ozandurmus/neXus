-- V164 -- configuration archives are kept 30 days (PO decision 2026-10-18).
--
-- Until now the retention pruner (V44) acted on artefact_class = 'backup' only; configuration archives were never
-- pruned (954 rows were older than 7 days on 2026-10-18). The pruner now removes a configuration archive once it is
-- older than backup_policy.configuration_retention_days, keeping each device's latest configuration archive and
-- recording the removal in artefact_retention_ledger like every other prune. The days live in the same policy row as
-- the backup days; the audited PUT /api/v2/backups/policies may change them (optional field).
SELECT set_config('app.actor_fingerprint', 'migration:V164_configuration_archive_retention', true);
SELECT set_config('app.action_id', 'backup_policy_configuration_retention_by_migration', true);

ALTER TABLE backup_policy
    ADD COLUMN IF NOT EXISTS configuration_retention_days INTEGER NOT NULL DEFAULT 30;

ALTER TABLE backup_policy
    ADD CONSTRAINT backup_policy_configuration_retention_days_range
        CHECK (configuration_retention_days BETWEEN 1 AND 3650);
