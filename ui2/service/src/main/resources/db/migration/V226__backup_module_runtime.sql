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

-- When the current drain began. A drain that outlives a bounded time is a stuck cutover, not a rollout in progress
-- (2026-10-05 class of incident); the system status alert uses this to stop suppressing consumer alerts.
-- A trigger stamps it so every writer (module_deploy.py, the Java repository, operators) stays correct.
ALTER TABLE module_runtime_control ADD COLUMN IF NOT EXISTS drain_requested_at TIMESTAMPTZ;
CREATE OR REPLACE FUNCTION ui2_stamp_drain_requested_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT NEW.drain_requested THEN
        NEW.drain_requested_at := NULL;
    ELSIF TG_OP = 'INSERT' OR NOT OLD.drain_requested OR NEW.drain_generation IS DISTINCT FROM OLD.drain_generation
            OR OLD.drain_requested_at IS NULL THEN
        NEW.drain_requested_at := now();
    END IF;
    RETURN NEW;
END
$$;
DROP TRIGGER IF EXISTS ui2_stamp_drain_requested_at ON module_runtime_control;
CREATE TRIGGER ui2_stamp_drain_requested_at BEFORE INSERT OR UPDATE ON module_runtime_control
    FOR EACH ROW EXECUTE FUNCTION ui2_stamp_drain_requested_at();
UPDATE module_runtime_control SET drain_requested_at = now() WHERE drain_requested AND drain_requested_at IS NULL;
