ALTER TABLE notification_routes DROP CONSTRAINT notification_routes_type_check;
ALTER TABLE notification_routes ADD CONSTRAINT notification_routes_type_check
    CHECK (type IN ('admin_event', 'login_security', 'backup_failure', 'job_failure',
        'config_change', 'compliance_regression', 'device_health', 'security_scan'));

INSERT INTO notification_routes(type, enabled, watermark_audit, watermark_time, updated_by)
VALUES ('security_scan', false, 0, now(), 'security-scan-migration');
