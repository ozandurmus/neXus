-- Preserve the applied V128 checksum. Bootstrap/recovery never enables an absent owner.
SELECT set_config('app.actor_fingerprint','system:migration',true);
SELECT set_config('app.action_id','module_ownership_recovery',true);
SELECT pg_advisory_xact_lock(294611);
ALTER TABLE module_runtime_control ALTER COLUMN effective_owner SET DEFAULT 'general';
ALTER TABLE module_runtime_control ALTER COLUMN fallback_enabled SET DEFAULT TRUE;
-- V128's ownership check excludes fallback on the general row; replace only that check.
DO $$ DECLARE ownership_check TEXT; BEGIN
 SELECT conname INTO STRICT ownership_check FROM pg_constraint
 WHERE conrelid='module_runtime_control'::regclass AND contype='c'
 AND pg_get_constraintdef(oid) LIKE '%fallback_enabled%';
 EXECUTE format('ALTER TABLE module_runtime_control DROP CONSTRAINT %I',ownership_check);
END $$;
ALTER TABLE module_runtime_control ADD CONSTRAINT module_runtime_ownership_check
 CHECK ((fallback_enabled AND effective_owner='general')
     OR (NOT fallback_enabled AND effective_owner=module));
UPDATE module_runtime_control m
 SET effective_owner='general',fallback_enabled=TRUE,generation=generation+1,
     last_reason='owner heartbeat absent at migration',
     drain_requested=FALSE,drain_ack_at=NULL,drain_ack_generation=NULL
 WHERE (effective_owner<>'general' OR NOT fallback_enabled)
 AND NOT EXISTS (SELECT 1 FROM module_runtime_control owner
     WHERE owner.module=m.effective_owner AND owner.owner_instance IS NOT NULL
     AND owner.owner_heartbeat_at>now()-interval '60 seconds');
-- Uncertain active work retains its heartbeat identity and lease records.

UPDATE module_runtime_control m SET owner_instance=NULL,owner_heartbeat_at=NULL
 WHERE (owner_instance IS NOT NULL OR owner_heartbeat_at IS NOT NULL)
 AND (owner_heartbeat_at IS NULL OR owner_heartbeat_at<=now()-interval '60 seconds')
 AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.state IN ('CLAIMED','EXECUTING')
     AND split_part(j.lease_worker_id,'-',1)=m.module)
 AND NOT EXISTS (SELECT 1 FROM endpoint_admission e WHERE e.owner_role=m.module
     AND e.state IN ('LEASED','QUARANTINED'))
 AND NOT EXISTS (SELECT 1 FROM runtime_task_lease t WHERE t.owner_role=m.module
     AND t.state IN ('LEASED','QUARANTINED'));
