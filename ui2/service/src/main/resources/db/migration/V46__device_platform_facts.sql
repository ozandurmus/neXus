-- V46 -- platform identity facts (backlog platform_identity_facts_on_configuration,
-- Product Owner P1 2026-09-22: "serial number and version information on the
-- Configuration screen; whatever industry leaders collect").
--
-- One row per device, overwritten on every successful inventory read: the
-- facts an audit asks for first -- serial number, hotfix level, content /
-- signature versions, uptime -- next to the model and software version the
-- devices row already carries. Palo Alto fills serial, content versions and
-- uptime from the already-gated `show system info` (a parse-scope extension,
-- AGENTS.md "Network action taxonomy"); Check Point fills serial, hotfix and
-- uptime only once its own gate rows are signed off
-- (docs/design/PLATFORM_IDENTITY_FACTS_CONTRACT.md §3). A column the read did
-- not provide stays NULL and the screen says UNKNOWN.
--
-- No audit trigger: written by the product's own collection, never by an
-- operator; the inventory job row is the audited record.
CREATE TABLE device_platform_facts (
    device_id          TEXT        PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
    serial_number      TEXT,
    hotfix_level       TEXT,
    platform_family    TEXT,
    content_versions   JSONB,
    uptime_text        TEXT,
    source_read        TEXT        NOT NULL,
    observed_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON device_platform_facts TO ui2_app;
