-- V189 -- direct alerts for backup size deviations (PO request 2026-11-04).
--
-- One Check Point gateway's backup grew to about 28 GB because its local firewall logs went into it; the size anomaly
-- check of V180 recorded what it could see against the device's own history, but nothing told anyone. A measurement of
-- the estate (counts only) showed two more shapes the own-history rule cannot see: devices that have always been large
-- (never 5x their own past, but 100x the fleet), and Check Point backups that carry firewall log files at all.
--
-- 1. Notification type backup_size_anomaly, one digest line per alert row, three kinds of row in
--    backup_deviation_record (the digest's watermark is detected_at):
--      size_anomaly   own-history growth or shrink (V180, unchanged);
--      fleet_large    a standard backup at least 10x its vendor's fleet median and at least 1 GiB (the Backups screen's
--                     "Large" rule, so the screen and the alert agree);
--      firewall_logs  a Check Point standard backup whose content listing holds at least 100 MiB under
--                     opt/CPsuite-*/fw1/log/ (sizes and a percentage only, never a file name).
--    fleet_large and firewall_logs rows are raised once per artefact (partial unique indexes below). They pin nothing,
--    nothing resolves them, and no retention query reads them (all of them name size_anomaly), so retention is unchanged.
-- 2. The route is seeded ENABLED, unlike V113 and V160 which seeded their types disabled: the Product Owner asked for
--    direct alerts, and a disabled route would be silent. Recipients stay empty, so the digest goes to the global
--    recipients like every other type; with SMTP unconfigured the router sends nothing, as for the other types. The
--    watermark starts at "now", so no historical row is sent.
SELECT set_config('app.actor_fingerprint', 'migration:V189_backup_size_anomaly_notification', true);
SELECT set_config('app.action_id', 'backup_size_anomaly_notification_by_migration', true);

ALTER TABLE backup_deviation_record
    DROP CONSTRAINT backup_deviation_record_deviation_class_check;
ALTER TABLE backup_deviation_record
    ADD CONSTRAINT backup_deviation_record_deviation_class_check
        CHECK (deviation_class IN ('major', 'minor', 'unchanged', 'first_run', 'size_anomaly', 'fleet_large',
            'firewall_logs'));

CREATE UNIQUE INDEX uq_backup_deviation_fleet_large_artefact
    ON backup_deviation_record(artefact_id) WHERE deviation_class = 'fleet_large';
CREATE UNIQUE INDEX uq_backup_deviation_firewall_logs_artefact
    ON backup_deviation_record(artefact_id) WHERE deviation_class = 'firewall_logs';
-- The digest reads the three alert classes by time.
CREATE INDEX idx_backup_deviation_size_alerts_detected
    ON backup_deviation_record(detected_at)
    WHERE deviation_class IN ('size_anomaly', 'fleet_large', 'firewall_logs');

ALTER TABLE notification_routes DROP CONSTRAINT IF EXISTS notification_routes_type_check;
ALTER TABLE notification_routes ADD CONSTRAINT notification_routes_type_check
    CHECK (type IN ('admin_event', 'login_security', 'backup_failure', 'job_failure',
        'config_change', 'compliance_regression', 'device_health', 'security_scan', 'device_crash_file',
        'backup_size_anomaly'));
INSERT INTO notification_routes(type, enabled, watermark_audit, watermark_time, updated_by)
VALUES ('backup_size_anomaly', true, 0, now(), 'backup-size-alert-migration')
ON CONFLICT (type) DO NOTHING;
