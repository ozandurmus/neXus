ALTER TABLE failover_run ALTER COLUMN approval_id DROP NOT NULL;
ALTER TABLE failover_run ADD COLUMN run_kind TEXT NOT NULL DEFAULT 'FAILOVER'
    CHECK (run_kind IN ('FAILOVER', 'READINESS'));
ALTER TABLE failover_run ADD CONSTRAINT failover_run_approval_kind_check
    CHECK ((run_kind = 'READINESS' AND approval_id IS NULL) OR (run_kind = 'FAILOVER' AND approval_id IS NOT NULL));
