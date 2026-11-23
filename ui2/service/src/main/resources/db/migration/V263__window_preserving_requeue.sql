-- Window-preserving requeue (PO 2026-11-23, deploy-freeze Phase 1, part 1).
--
-- A job that the claim statement admitted inside its scheduling window and that a deploy drain or endpoint-admission
-- contention then returned to REQUESTED (EndpointAdmissionRepository.defer) stays claimable after that window closes,
-- for a bounded grace: resume_eligible_until = least(greatest(admitted_window_end, defer time) + 30 minutes,
-- admitted_window_end + 2 hours). A job that was never claimed carries neither value and keeps today's window rule.
--
-- Sequencing: V262 (artefact chunk GC) must be applied before this migration; Flyway runs without outOfOrder.
--
-- ADDITIVE ONLY: two new nullable columns and one partial index. Nothing is dropped, renamed, retyped or made NOT NULL,
-- and no existing function is replaced. Binaries without this change never read or write either column.
--   admitted_window_end   -- stamped by every claim; kept only by a claim made through an active grace
--   resume_eligible_until -- stamped by a defer; read by the claim statement and the outside-window pre-check

ALTER TABLE jobs ADD COLUMN IF NOT EXISTS admitted_window_end TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS resume_eligible_until TIMESTAMPTZ;

-- The outside-window pre-check (JooqJobLeaseDao.BYPASS_QUEUED_SQL) reads only queued rows still inside their grace.
CREATE INDEX IF NOT EXISTS jobs_resume_eligible_requested
    ON jobs (resume_eligible_until)
    WHERE state = 'REQUESTED' AND resume_eligible_until IS NOT NULL;
