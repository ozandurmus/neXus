-- V244 -- backup observations in the device software version history.
--
-- backup_artefact (nightly backups, many rows per device) is a denser observation source than discovery runs, so
-- it narrows the upgrade window (previous_last_seen_at, first_seen_at]. V243 is one day old, so its rows are
-- rebuilt from the UNION of every observation instead of patched:
--   discovery   (same device mapping and per-run pick as V243, time = run time)
--   backup      (backup_artefact rows with a non-blank software_version, time = created_at)
--   current     (devices.observed_software_version, time = now())
-- Consecutive identical versions collapse into one stretch (gaps-and-islands over the merged, time-ordered
-- observations); a stretch's source is the source of its first observation. The hotfix level is attached afterwards
-- by the same seed call V243 used. New backups feed the history live (JooqBackupArtefactManifestRepository.insert).

-- 1. Allow source 'backup' (the V243 constraint is unnamed, so find it by its definition).
DO $chk$
DECLARE
    v_name TEXT;
BEGIN
    FOR v_name IN SELECT conname FROM pg_constraint
                  WHERE conrelid = 'public.device_software_version_history'::regclass AND contype = 'c'
                    AND pg_get_constraintdef(oid) LIKE '%discovery_backfill%'
    LOOP
        EXECUTE format('ALTER TABLE public.device_software_version_history DROP CONSTRAINT %I', v_name);
    END LOOP;
END
$chk$;
ALTER TABLE device_software_version_history
    ADD CONSTRAINT chk_swhist_source CHECK (source IN ('inventory', 'discovery_backfill', 'platform_facts', 'backup'));

-- 2. Rebuild.
DELETE FROM device_software_version_history;

INSERT INTO device_software_version_history(device_id, vendor, software_version, hotfix_level, first_seen_at,
        last_seen_at, previous_software_version, previous_hotfix_level, previous_last_seen_at, source)
WITH disc AS (
    SELECT DISTINCT ON (d.device_id, c.run_id)
           d.device_id, d.vendor_hint AS vendor, c.software_version AS ver,
           coalesce(r.finished_at, r.started_at, r.created_at) AS t, 'discovery_backfill'::text AS src,
           0 AS rnk, c.run_id AS tie
    FROM devices d
    JOIN discovery_candidate c
      ON ((c.vendor || '|' || coalesce(c.owning_domain, '') || '|' || c.stable_identifier) = d.discovery_match_key
          OR (c.vendor || '|' || c.stable_identifier) = d.discovery_match_key
          OR c.stable_identifier = d.recorded_identity_primary)
    JOIN discovery_run r ON r.run_id = c.run_id
    WHERE c.software_version IS NOT NULL AND btrim(c.software_version) <> ''
    ORDER BY d.device_id, c.run_id, (c.parent_candidate_id IS NULL) DESC, c.candidate_id
), obs AS (
    SELECT device_id, vendor, ver, t, src, rnk, tie FROM disc
    UNION ALL
    SELECT d.device_id, d.vendor_hint, b.software_version, b.created_at, 'backup', 1, b.artefact_id
    FROM backup_artefact b JOIN devices d ON d.device_id = b.device_id
    WHERE b.software_version IS NOT NULL AND btrim(b.software_version) <> ''
    UNION ALL
    SELECT d.device_id, d.vendor_hint, d.observed_software_version, now(), 'inventory', 2, ''
    FROM devices d
    WHERE d.observed_software_version IS NOT NULL AND btrim(d.observed_software_version) <> ''
), marked AS (
    SELECT o.*, CASE WHEN o.ver IS DISTINCT FROM lag(o.ver) OVER w THEN 1 ELSE 0 END AS brk
    FROM obs o
    WINDOW w AS (PARTITION BY o.device_id ORDER BY o.t, o.rnk, o.tie)
), grp AS (
    SELECT m.*, sum(m.brk) OVER (PARTITION BY m.device_id ORDER BY m.t, m.rnk, m.tie ROWS UNBOUNDED PRECEDING) AS g
    FROM marked m
), stretch AS (
    SELECT device_id, max(vendor) AS vendor, ver, min(t) AS first_seen, max(t) AS last_seen,
           (array_agg(src ORDER BY t, rnk, tie))[1] AS src
    FROM grp GROUP BY device_id, ver, g
), chained AS (
    SELECT s.*, lag(ver) OVER w AS prev_ver, lag(last_seen) OVER w AS prev_last
    FROM stretch s
    WINDOW w AS (PARTITION BY s.device_id ORDER BY s.first_seen, s.last_seen)
)
SELECT device_id, vendor, ver, NULL, first_seen, last_seen, prev_ver, NULL,
       CASE WHEN prev_last <= first_seen THEN prev_last END, src
FROM chained;

-- 3. Hotfix level of the latest stretch, exactly as V243's seed did.
SELECT ui2_record_software_version(d.device_id, d.observed_software_version, pf.hotfix_level, 'inventory')
FROM devices d
LEFT JOIN device_platform_facts pf ON pf.device_id = d.device_id
WHERE d.observed_software_version IS NOT NULL;

-- Grants are unchanged (same table). Re-assert the module roles that insert backup_artefact rows (the live feed
-- calls ui2_record_software_version from that transaction); roles that do not exist are skipped.
DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE'),
        ('ui2_backup', 'SELECT, INSERT, UPDATE'),
        ('ui2_worker_general', 'SELECT, INSERT, UPDATE')
    ) AS g(role_name, privs)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.device_software_version_history TO %I', v_rec.privs, v_rec.role_name);
        END IF;
    END LOOP;
END
$grants$;
