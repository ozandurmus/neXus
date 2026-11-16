-- V243 -- device software version history (backlog device_software_version_history, PO 2026-11-16: "when was this
-- device upgraded?" answered from what neXus already observes, no new device command).
--
-- One row per STRETCH: a run of consecutive observations of the same (software_version, hotfix_level) on one device.
-- previous_* names the stretch before it; previous_last_seen_at is the last time the previous version was still seen,
-- so the upgrade happened in the window (previous_last_seen_at, first_seen_at]. UNKNOWN is honest: a device with no
-- earlier observation has no previous_* and no window.
--
-- Written by ui2_record_software_version(), called in the SAME transaction as the write of
-- devices.observed_software_version (JooqDeviceRepository) and of device_platform_facts.hotfix_level
-- (JooqDevicePlatformFactsRepository), so the history cannot diverge from the observation.
--
-- No audit trigger: the table holds product versions only (no address, name, serial), written by the product's own
-- collection; the inventory job row is the audited record.

CREATE TABLE device_software_version_history (
    history_id                 BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id                  TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    vendor                     TEXT,
    software_version           TEXT        NOT NULL CHECK (length(software_version) BETWEEN 1 AND 200),
    hotfix_level               TEXT,
    first_seen_at              TIMESTAMPTZ NOT NULL,
    last_seen_at               TIMESTAMPTZ NOT NULL,
    previous_software_version  TEXT,
    previous_hotfix_level      TEXT,
    previous_last_seen_at      TIMESTAMPTZ,
    source                     TEXT        NOT NULL CHECK (source IN ('inventory', 'discovery_backfill', 'platform_facts')),
    CONSTRAINT chk_swhist_seen_order CHECK (last_seen_at >= first_seen_at),
    CONSTRAINT chk_swhist_window_order CHECK (previous_last_seen_at IS NULL OR previous_last_seen_at <= first_seen_at)
);

CREATE INDEX idx_swhist_device_first_seen ON device_software_version_history (device_id, first_seen_at);
CREATE INDEX idx_swhist_first_seen ON device_software_version_history (first_seen_at);

-- Records one observation of a device's software version (and, when known, hotfix / Jumbo level).
--   same version, hotfix unchanged or not read  -> the open stretch's last_seen_at moves to now();
--   same version, stretch had no hotfix yet      -> the hotfix is filled in (not a change);
--   anything else (new version, or new hotfix)   -> the open stretch closes and a new stretch opens, carrying
--                                                   previous_* and the window start.
-- A NULL version records nothing (an unread version is not a version change). An advisory lock per device keeps two
-- writers from opening two stretches.
CREATE OR REPLACE FUNCTION ui2_record_software_version(p_device_id TEXT, p_version TEXT, p_hotfix TEXT, p_source TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $fn$
DECLARE
    cur device_software_version_history%ROWTYPE;
BEGIN
    IF p_version IS NULL OR btrim(p_version) = '' THEN
        RETURN;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('swhist:' || p_device_id, 0));
    SELECT * INTO cur FROM device_software_version_history
        WHERE device_id = p_device_id ORDER BY first_seen_at DESC, history_id DESC LIMIT 1;
    IF FOUND AND cur.software_version = p_version
            AND (p_hotfix IS NULL OR cur.hotfix_level IS NULL OR cur.hotfix_level = p_hotfix) THEN
        UPDATE device_software_version_history
            SET last_seen_at = greatest(last_seen_at, now()),
                hotfix_level = coalesce(hotfix_level, p_hotfix)
            WHERE history_id = cur.history_id;
        RETURN;
    END IF;
    INSERT INTO device_software_version_history(device_id, vendor, software_version, hotfix_level, first_seen_at,
            last_seen_at, previous_software_version, previous_hotfix_level, previous_last_seen_at, source)
        SELECT p_device_id, d.vendor_hint, p_version, p_hotfix, greatest(now(), coalesce(cur.last_seen_at, now())),
               greatest(now(), coalesce(cur.last_seen_at, now())),
               cur.software_version, cur.hotfix_level, cur.last_seen_at, p_source
        FROM devices d WHERE d.device_id = p_device_id;
END
$fn$;

-- Backfill 1: discovery history. A device maps to its discovery candidates exactly as DEVICE_SUMMARY_SELECT maps it
-- (match key with or without owning domain, or the recorded identity); per run the candidate that is not a child is
-- preferred. Consecutive runs with the same version collapse into one stretch (gaps-and-islands over the run time).
INSERT INTO device_software_version_history(device_id, vendor, software_version, hotfix_level, first_seen_at,
        last_seen_at, previous_software_version, previous_hotfix_level, previous_last_seen_at, source)
WITH obs AS (
    SELECT DISTINCT ON (d.device_id, c.run_id)
           d.device_id, d.vendor_hint AS vendor, c.run_id, c.software_version AS ver,
           coalesce(r.finished_at, r.started_at, r.created_at) AS t
    FROM devices d
    JOIN discovery_candidate c
      ON ((c.vendor || '|' || coalesce(c.owning_domain, '') || '|' || c.stable_identifier) = d.discovery_match_key
          OR (c.vendor || '|' || c.stable_identifier) = d.discovery_match_key
          OR c.stable_identifier = d.recorded_identity_primary)
    JOIN discovery_run r ON r.run_id = c.run_id
    WHERE c.software_version IS NOT NULL AND btrim(c.software_version) <> ''
    ORDER BY d.device_id, c.run_id, (c.parent_candidate_id IS NULL) DESC, c.candidate_id
), marked AS (
    SELECT o.*, CASE WHEN o.ver IS DISTINCT FROM lag(o.ver) OVER w THEN 1 ELSE 0 END AS brk
    FROM obs o
    WINDOW w AS (PARTITION BY o.device_id ORDER BY o.t, o.run_id)
), grp AS (
    SELECT m.*, sum(m.brk) OVER (PARTITION BY m.device_id ORDER BY m.t, m.run_id ROWS UNBOUNDED PRECEDING) AS g
    FROM marked m
), stretch AS (
    SELECT device_id, max(vendor) AS vendor, ver, min(t) AS first_seen, max(t) AS last_seen
    FROM grp GROUP BY device_id, ver, g
), chained AS (
    SELECT s.*, lag(ver) OVER w AS prev_ver, lag(last_seen) OVER w AS prev_last
    FROM stretch s
    WINDOW w AS (PARTITION BY s.device_id ORDER BY s.first_seen, s.last_seen)
)
SELECT device_id, vendor, ver, NULL, first_seen, last_seen, prev_ver, NULL,
       CASE WHEN prev_last <= first_seen THEN prev_last END, 'discovery_backfill'
FROM chained;

-- Backfill 2 (seed): the current observation of every device, with the latest platform-facts hotfix level. Devices
-- with no history get their first row; a device whose backfilled last stretch equals the current version is only
-- bumped; one that differs gets a new stretch with the window start taken from the backfill.
SELECT ui2_record_software_version(d.device_id, d.observed_software_version, pf.hotfix_level, 'inventory')
FROM devices d
LEFT JOIN device_platform_facts pf ON pf.device_id = d.device_id
WHERE d.observed_software_version IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON device_software_version_history TO ui2_app;

-- Module roles (V242 matrix style): the writers of devices.observed_software_version and device_platform_facts.
-- Roles are created outside the migrations; a role that does not exist is skipped.
DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE'),
        ('ui2_backup', 'SELECT, INSERT, UPDATE'),
        ('ui2_inventory', 'SELECT, INSERT, UPDATE'),
        ('ui2_configuration', 'SELECT, INSERT, UPDATE'),
        ('ui2_worker_general', 'SELECT, INSERT, UPDATE')
    ) AS g(role_name, privs)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.device_software_version_history TO %I', v_rec.privs, v_rec.role_name);
        END IF;
    END LOOP;
END
$grants$;
