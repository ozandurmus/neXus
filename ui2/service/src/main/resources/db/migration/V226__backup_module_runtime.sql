-- Backup module runtime split (MODULE_PER_POD_PLAN.md step 2).
-- Keeps backup on general fallback until module_deploy.py verifies a live ui2-backup heartbeat and PO authorization.
SELECT set_config('app.actor_fingerprint', 'migration:V226_backup_module_runtime', true);
SELECT set_config('app.action_id', 'backup_module_runtime_migration', true);

ALTER TABLE endpoint_admission DROP CONSTRAINT IF EXISTS endpoint_admission_owner_role_check;
ALTER TABLE endpoint_admission ADD CONSTRAINT endpoint_admission_owner_role_check
    CHECK (owner_role IN ('policy', 'backup', 'general', 'service'));

INSERT INTO module_runtime_control (module, effective_owner, fallback_enabled)
VALUES ('backup', 'general', TRUE)
ON CONFLICT (module) DO NOTHING;
