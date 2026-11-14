-- API latency work 2026-11-14: the device summary read (JooqDeviceRepository.DEVICE_SUMMARY_SELECT, behind /devices,
-- Overview, Compliance, Change feed, failover summary, Lifecycle, diagnostics targets, System storage) took
-- 514-547 ms for 136 devices on the live DB. Its per-device lookups had no usable index:
--   * discovery candidate match: three OR'd equality arms, each a seq scan of discovery_candidate (~288 ms);
--   * parent candidate: candidate_id (primary key) OR stable_identifier (~20 ms);
--   * virtual systems: a display-name prefix match, now also expressed as a "C"-collation range (~138 ms);
--   * latest job per device: ~364 jobs read and sorted per device (~62 ms).
-- Each index below matches one of those predicates exactly, so the planner can use it per arm (BitmapOr).
-- Additive only; no data changes. Plain CREATE INDEX (small tables: 15.9 k candidates, 49.7 k jobs) so the
-- migration stays transactional and can be dry-run in BEGIN/ROLLBACK.
CREATE INDEX IF NOT EXISTS idx_discovery_candidate_match_key_domain
    ON discovery_candidate ((vendor || '|' || coalesce(owning_domain, '') || '|' || stable_identifier));
CREATE INDEX IF NOT EXISTS idx_discovery_candidate_match_key
    ON discovery_candidate ((vendor || '|' || stable_identifier));
CREATE INDEX IF NOT EXISTS idx_discovery_candidate_stable_identifier
    ON discovery_candidate (stable_identifier);
CREATE INDEX IF NOT EXISTS idx_discovery_candidate_display_name_c
    ON discovery_candidate ((display_name COLLATE "C"));
CREATE INDEX IF NOT EXISTS idx_jobs_target_device_submitted
    ON jobs (target_device_id, submitted_at DESC);
-- Failover summary (/api/v2/cp-failover/summary, 0.73-1.1 s): the latest run per unit was found by reading every
-- run of the unit (about 154 per unit, 9 k rows), and the latest finished readiness run per unit by a sort of all of
-- them. These two match JooqCpFailoverRepository.summaryStatuses / readinessStatuses.
CREATE INDEX IF NOT EXISTS idx_failover_run_unit_scheduled
    ON failover_run (vendor, cluster_ref, scheduled_for DESC);
CREATE INDEX IF NOT EXISTS idx_failover_run_readiness_finished
    ON failover_run (vendor, cluster_ref, (coalesce(vs_id, '')), finished_at DESC)
    WHERE run_kind = 'READINESS' AND state IN ('DONE', 'STOPPED');
-- Expression indexes carry their own statistics, gathered only by ANALYZE; without them the planner guesses the
-- selectivity of the new expressions until the next autoanalyze. The three tables are small (seconds at most).
ANALYZE discovery_candidate;
ANALYZE jobs;
ANALYZE failover_run;
