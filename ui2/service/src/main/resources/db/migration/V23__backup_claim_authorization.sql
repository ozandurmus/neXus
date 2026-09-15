-- V23 -- BK-12/BW-4: the execution-time authorization join for queued
-- Check Point backup collection. Admission (BackupCollectService) already
-- confirms role:backup_admin (GateChain E4), the reason-length gate (BK-12)
-- and the pilot allowlist (BK-1) before a job ever reaches REQUESTED -- but
-- the worker claims and executes that job asynchronously, possibly long
-- after those facts were true. backup_job_authorization is the immutable
-- record of what admission actually verified, so the worker's own
-- claim-time re-check (BackupJobExecutor) has something to re-verify
-- against rather than trusting a stale admission decision.
--
-- Additive to V1-V22 (append-only; none of them is edited).
--
-- Migration compatibility: this table is populated only going forward, by
-- BackupCollectService, at the moment a backup job is admitted. Any
-- backup-capability job row that predates V23 (or, symmetrically, any
-- row a future defect admits without writing this evidence) has no
-- matching backup_job_authorization row -- BackupJobExecutor treats that
-- absence as a refusal, never as an inferred authorization, exactly like
-- a row this movement judges malformed (BK-12/BW-4: "old queued rows
-- without required evidence must refuse, not infer authorization"). No
-- backfill is attempted or needed: a pre-V23 backup job that is still
-- queued at claim time was queued before this movement's own frozen
-- contract existed, so it is correctly refused, not retrofitted.

CREATE TABLE backup_job_authorization (
    job_id             TEXT        PRIMARY KEY REFERENCES jobs(job_id),
    device_id           TEXT        NOT NULL REFERENCES devices(device_id),
    actor_fingerprint    TEXT        NOT NULL,
    reason                TEXT        NOT NULL CHECK (length(btrim(reason)) >= 8),
    recorded_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_audit_backup_job_authorization
    AFTER INSERT OR UPDATE OR DELETE ON backup_job_authorization
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('job_id');

GRANT SELECT, INSERT ON backup_job_authorization TO ui2_app;
