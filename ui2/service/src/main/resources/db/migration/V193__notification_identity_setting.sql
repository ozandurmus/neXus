-- V193 -- Notification identity setting (PO decision 2026-11-05 evening batch, item 4; audit
-- docs/design/NOTIFICATION_IDENTITY_EXPOSURE_2026_11_05.md).
--
-- 'masked' (default): outbound e-mail digests and syslog lines carry the same pseudonyms the AIView masked
-- projection shows (FW-TANGO-04 style); free text is masked too and a raw device id is never printed.
-- 'raw': the pre-V193 behaviour; switching to it is a security_admin action (the PUT route is security_admin-only)
-- and is audited as notification_settings_update by the trigger below.
-- Existing rows become 'masked' through the column default.
ALTER TABLE notification_settings
    ADD COLUMN notification_identity TEXT NOT NULL DEFAULT 'masked'
        CHECK (notification_identity IN ('masked', 'raw'));

-- The V100 audit trigger lists the columns it watches; the new column joins that list.
DROP TRIGGER trg_audit_notification_settings_config ON notification_settings;
CREATE TRIGGER trg_audit_notification_settings_config
AFTER UPDATE OF syslog_enabled, syslog_host, syslog_port, syslog_protocol, syslog_facility,
    smtp_enabled, smtp_host, smtp_port, smtp_starttls, smtp_from, smtp_to,
    forward_audit_to_syslog, notify_job_failure, notification_identity ON notification_settings
FOR EACH ROW EXECUTE FUNCTION fn_audit_notification_config();
