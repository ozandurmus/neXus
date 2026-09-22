-- V52 -- server-side cluster member DIFF summary (OVERVIEW_EXCEPTION_SCREEN_CONTRACT §5.1, FROZEN 2026-09-23).
-- The Configuration screen computes the member comparison in the browser; the Overview needs the count on
-- the server. A service task recomputes a cluster when its members' latest sanitized texts change (at most
-- every 5 minutes) with the same rules as the browser, and stores only section names and counts -- never a
-- value (raw-evidence law). Rows accumulate per recomputation; the Overview reads the latest per cluster.
CREATE TABLE cluster_member_diff (
    cluster_ref         TEXT        NOT NULL,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    member_run_ids      TEXT[]      NOT NULL,
    comparable          BOOLEAN     NOT NULL,
    diff_section_count  INTEGER     NOT NULL DEFAULT 0,
    diff_setting_count  INTEGER     NOT NULL DEFAULT 0,
    diff_sections       TEXT[]      NOT NULL DEFAULT '{}',
    PRIMARY KEY (cluster_ref, computed_at)
);
CREATE INDEX idx_cluster_member_diff_latest ON cluster_member_diff (cluster_ref, computed_at DESC);

CREATE INDEX IF NOT EXISTS idx_jobs_finished_terminal ON jobs (finished_at DESC) WHERE state IN ('COMPLETED', 'FAILED');
CREATE INDEX IF NOT EXISTS idx_device_configuration_run_primary ON device_configuration_run (device_id, collected_at DESC) WHERE is_primary;

GRANT SELECT, INSERT, DELETE ON cluster_member_diff TO ui2_app;
