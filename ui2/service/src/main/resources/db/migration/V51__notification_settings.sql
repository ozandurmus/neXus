-- V51 -- Remote logging (syslog) and SMTP relay notifications (Product Owner,
-- 2026-09-22: "Remote Logging and Notification screen; the SMTP relay side is
-- valuable here; later some errors and states must trigger it -- integrate
-- now if it can be").
--
-- One settings row. Two triggers in the first release, each off until the
-- administrator switches it on: forward audit events to syslog; send a
-- syslog message and a mail when a job fails. The watermarks record how far
-- each forwarder has gone, so a restart neither drops nor repeats a message.
-- No secret is stored here: an SMTP relay that needs authentication names a
-- credential-store reference (not used by the first release, which speaks to
-- an unauthenticated internal relay).
CREATE TABLE notification_settings (
    settings_id               TEXT        PRIMARY KEY CHECK (settings_id = 'default'),
    syslog_enabled            BOOLEAN     NOT NULL DEFAULT false,
    syslog_host               TEXT,
    syslog_port               INTEGER     NOT NULL DEFAULT 514 CHECK (syslog_port BETWEEN 1 AND 65535),
    syslog_protocol           TEXT        NOT NULL DEFAULT 'udp' CHECK (syslog_protocol IN ('udp', 'tcp')),
    syslog_facility           INTEGER     NOT NULL DEFAULT 16 CHECK (syslog_facility BETWEEN 0 AND 23),
    smtp_enabled              BOOLEAN     NOT NULL DEFAULT false,
    smtp_host                 TEXT,
    smtp_port                 INTEGER     NOT NULL DEFAULT 25 CHECK (smtp_port BETWEEN 1 AND 65535),
    smtp_starttls             BOOLEAN     NOT NULL DEFAULT true,
    smtp_from                 TEXT,
    smtp_to                   TEXT,
    smtp_credential_ref       TEXT,
    forward_audit_to_syslog   BOOLEAN     NOT NULL DEFAULT false,
    notify_job_failure        BOOLEAN     NOT NULL DEFAULT false,
    audit_watermark           BIGINT      NOT NULL DEFAULT 0,
    job_failure_watermark     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                TEXT
);

INSERT INTO notification_settings (settings_id, audit_watermark)
SELECT 'default', coalesce(max(audit_id), 0) FROM audit_log;

GRANT SELECT, UPDATE ON notification_settings TO ui2_app;
