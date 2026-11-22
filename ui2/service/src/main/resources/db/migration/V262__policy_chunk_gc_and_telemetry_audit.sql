-- PO approval 2026-11-23: two database cleanups. ADDITIVE: one nullable column, two new triggers, two old triggers
-- disabled (kept in place, not dropped). An older image keeps working on this schema unchanged.
--
-- Do not wait behind a long reader for the brief table locks below; a failed migration is retried by the next deploy.
SET LOCAL lock_timeout = '30s';

-- ---------------------------------------------------------------------
-- 1. Policy chunk garbage collection marker (PolicyChunkGarbageCollector)
-- ---------------------------------------------------------------------
-- cp_policy_json_chunk has no write time, and a generation is written before its manifest row is published
-- (PolicyChunkStore). The collector stamps the lowest remaining chunk of a generation that no manifest row
-- (cp_policy_object_inventory, cp_policy_domain_run, policy_unit_checkpoint) names, clears the stamp when a manifest
-- names it again, and deletes a generation only when the stamp is older than the safety age AND, in the same
-- DELETE statement, no manifest row names it. A nullable column without a default changes only the catalog
-- (no table rewrite).
ALTER TABLE cp_policy_json_chunk ADD COLUMN IF NOT EXISTS unreferenced_since TIMESTAMPTZ;
COMMENT ON COLUMN cp_policy_json_chunk.unreferenced_since IS
    'Set on the lowest remaining chunk of a generation when the garbage collector first saw no manifest row naming it; '
    'cleared when one names it again. Null on every other chunk.';

-- ---------------------------------------------------------------------
-- 2. Audit telemetry: stop auditing worker liveness writes that change nothing else
-- ---------------------------------------------------------------------
-- The capture function stays fn_audit_capture (same before/after rows, same redaction, same fail-closed context
-- check); only the trigger gains a WHEN condition. A row write is skipped only when BOTH hold:
--   (a) the transaction's action is one of the worker's liveness actions, and
--   (b) every column outside the named telemetry columns is unchanged.
-- Every other write, including any write without an audit context (fn_audit_capture then fails closed as before),
-- any owner, generation, drain request/generation/ack-generation, fallback, effective-owner or quarantine change,
-- is audited exactly as before.

-- module_runtime_control (V128): ModuleRuntimeRepository.heartbeat ('module_heartbeat') and .poolStats
-- ('module_pool_metrics'). Telemetry columns: owner_heartbeat_at, drain_ack_at (re-stamped on every heartbeat while
-- a drain is requested), pool_active, pool_idle, pool_pending, pool_timeouts, pool_wait_nanos.
-- Not telemetry (still audited): owner_instance (ownership take-over/release), drain_ack_generation (the first
-- acknowledgement of a drain), admission_grants/admission_wait_ms (one per endpoint admission grant, the only
-- audit trace of it).
CREATE TRIGGER module_runtime_control_audit_governance AFTER UPDATE ON module_runtime_control
 FOR EACH ROW
 WHEN (NOT (coalesce(current_setting('app.action_id', true), '') IN ('module_heartbeat', 'module_pool_metrics')
            AND (to_jsonb(OLD) - ARRAY['owner_heartbeat_at', 'drain_ack_at', 'pool_active', 'pool_idle',
                                       'pool_pending', 'pool_timeouts', 'pool_wait_nanos'])
              = (to_jsonb(NEW) - ARRAY['owner_heartbeat_at', 'drain_ack_at', 'pool_active', 'pool_idle',
                                       'pool_pending', 'pool_timeouts', 'pool_wait_nanos'])))
 EXECUTE FUNCTION fn_audit_capture('module');
ALTER TABLE module_runtime_control DISABLE TRIGGER module_runtime_control_audit;

-- jobs (V1 trg_audit_jobs): JooqJobLeaseDao.heartbeat ('job_heartbeat') extends lease_expires_at and stamps
-- last_heartbeat_at, nothing else. Claims, state transitions, lease expiry forced by another path, cancellation,
-- deferral and every INSERT/DELETE stay audited. A WHEN condition that reads OLD and NEW needs an UPDATE-only
-- trigger, so INSERT/DELETE get their own trigger with the same capture.
CREATE TRIGGER trg_audit_jobs_insert_delete AFTER INSERT OR DELETE ON jobs
 FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('job_id');
CREATE TRIGGER trg_audit_jobs_update AFTER UPDATE ON jobs
 FOR EACH ROW
 WHEN (NOT (coalesce(current_setting('app.action_id', true), '') = 'job_heartbeat'
            AND (to_jsonb(OLD) - ARRAY['lease_expires_at', 'last_heartbeat_at'])
              = (to_jsonb(NEW) - ARRAY['lease_expires_at', 'last_heartbeat_at'])))
 EXECUTE FUNCTION fn_audit_capture('job_id');
ALTER TABLE jobs DISABLE TRIGGER trg_audit_jobs;
