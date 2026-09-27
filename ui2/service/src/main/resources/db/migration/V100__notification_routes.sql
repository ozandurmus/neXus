CREATE TABLE notification_routes (
    type TEXT PRIMARY KEY CHECK (type IN ('admin_event', 'login_security', 'backup_failure', 'job_failure',
        'config_change', 'compliance_regression', 'device_health')),
    enabled BOOLEAN NOT NULL DEFAULT false,
    recipients TEXT,
    watermark_audit BIGINT,
    watermark_time TIMESTAMPTZ,
    last_sent_at TIMESTAMPTZ,
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);

INSERT INTO notification_routes(type, enabled, watermark_audit, watermark_time)
SELECT type, type IN ('backup_failure', 'job_failure') AND s.notify_job_failure,
    (SELECT coalesce(max(audit_id), 0) FROM audit_log), now()
FROM notification_settings s
CROSS JOIN (VALUES ('admin_event'), ('login_security'), ('backup_failure'), ('job_failure'),
    ('config_change'), ('compliance_regression'), ('device_health')) AS types(type)
WHERE s.settings_id = 'default';

CREATE TABLE notification_state (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION fn_audit_notification_config() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    actor TEXT := CASE WHEN TG_OP = 'DELETE' THEN current_setting('app.actor_fingerprint', true)
        ELSE NEW.updated_by END;
    row_key TEXT := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD)->>'type'
        WHEN TG_TABLE_NAME = 'notification_routes' THEN to_jsonb(NEW)->>'type'
        ELSE to_jsonb(NEW)->>'settings_id' END;
BEGIN
    IF actor IS NULL OR actor = '' THEN
        RAISE EXCEPTION 'notification configuration update requires an actor';
    END IF;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id)
    VALUES (TG_TABLE_NAME, row_key,
        TG_OP, actor, CASE WHEN TG_TABLE_NAME = 'notification_routes' THEN 'notification_route_update'
            ELSE 'notification_settings_update' END);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_notification_settings_config
AFTER UPDATE OF syslog_enabled, syslog_host, syslog_port, syslog_protocol, syslog_facility,
    smtp_enabled, smtp_host, smtp_port, smtp_starttls, smtp_from, smtp_to,
    forward_audit_to_syslog, notify_job_failure ON notification_settings
FOR EACH ROW EXECUTE FUNCTION fn_audit_notification_config();

CREATE TRIGGER trg_audit_notification_routes_config
AFTER UPDATE OF enabled, recipients ON notification_routes
FOR EACH ROW EXECUTE FUNCTION fn_audit_notification_config();

CREATE TRIGGER trg_audit_notification_routes_row
AFTER INSERT OR DELETE ON notification_routes
FOR EACH ROW EXECUTE FUNCTION fn_audit_notification_config();

GRANT SELECT, INSERT, UPDATE, DELETE ON notification_routes TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON notification_state TO ui2_app;
