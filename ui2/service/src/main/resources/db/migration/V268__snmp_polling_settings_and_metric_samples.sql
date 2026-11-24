-- V268 -- SNMP device metrics, wave 1: polling settings and metric samples
-- (docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md, FROZEN 2026-11-24, sections 5, 6 and 11).
--
-- 1. snmp_polling_settings: one row (option B, PO decision 2): the fleet switch (OFF by default), the interval
--    (300 s by default), the fleet default credential, the scope mode, and the retention of raw and hourly rows.
-- 2. snmp_polling_override: one row per vendor or per device. A vendor row puts the vendor in scope and may name its own
--    credential; a device row selects (DEVICES mode) or excludes (in_scope = false) one device and may name its own
--    credential. The credential store itself gains no polling fields.
-- 3. snmp_poll_state: per device poll bookkeeping (misses, backoff, last reason, last sysUpTime for reboot detection).
-- 4. device_metric_sample: one row per poll. A value that did not answer is NULL, never 0. No device name, address or
--    raw answer is stored.
-- 5. device_metric_hourly: hourly p95 and maximum, written by the worker for complete hours.
--
-- Credentials are referenced by credential_references.credential_reference_id, the id the worker resolver takes. A
-- referenced credential cannot be deleted (FK RESTRICT; CredentialAdministration also refuses it as CredentialInUse).
-- Settings and overrides are audited (fn_audit_capture). Samples, hourly rows and poll state are high-volume telemetry
-- with no identity or secret in them and carry no audit trigger (as zone_matrix_finding, V267).
-- Dry-run in BEGIN/ROLLBACK on the live database before deploy.
SELECT set_config('app.actor_fingerprint', 'migration:V268_snmp_polling_settings_and_metric_samples', true);
SELECT set_config('app.action_id', 'snmp_polling_settings_by_migration', true);
-- The new foreign keys take a short SHARE ROW EXCLUSIVE lock on devices and credential_references; wait at most 10 s for
-- it rather than queue behind a long transaction (the migration then fails and is retried, nothing half-applied).
SELECT set_config('lock_timeout', '10s', true);

CREATE TABLE IF NOT EXISTS snmp_polling_settings (
    settings_id TEXT PRIMARY KEY CHECK (settings_id = 'default'),
    enabled BOOLEAN NOT NULL DEFAULT false,
    interval_s INTEGER NOT NULL DEFAULT 300 CHECK (interval_s BETWEEN 60 AND 3600),
    default_credential_reference_id TEXT REFERENCES credential_references(credential_reference_id) ON DELETE RESTRICT,
    scope_mode TEXT NOT NULL DEFAULT 'VENDORS' CHECK (scope_mode IN ('VENDORS', 'DEVICES')),
    raw_retention_days INTEGER NOT NULL DEFAULT 35 CHECK (raw_retention_days BETWEEN 1 AND 3650),
    hourly_retention_days INTEGER NOT NULL DEFAULT 400 CHECK (hourly_retention_days BETWEEN 1 AND 3650),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);
COMMENT ON TABLE snmp_polling_settings IS
    'SNMP polling (Administration > SNMP polling). enabled is the fleet switch, OFF until an admin turns it on.';

CREATE TABLE IF NOT EXISTS snmp_polling_override (
    override_key TEXT PRIMARY KEY,
    scope_kind TEXT NOT NULL CHECK (scope_kind IN ('VENDOR', 'DEVICE')),
    vendor TEXT CHECK (vendor IN ('check_point', 'palo_alto')),
    device_id TEXT REFERENCES devices(device_id) ON DELETE CASCADE,
    in_scope BOOLEAN NOT NULL,
    credential_reference_id TEXT REFERENCES credential_references(credential_reference_id) ON DELETE RESTRICT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT,
    CONSTRAINT snmp_polling_override_shape CHECK (
        (scope_kind = 'VENDOR' AND vendor IS NOT NULL AND device_id IS NULL AND override_key = 'vendor:' || vendor)
        OR (scope_kind = 'DEVICE' AND device_id IS NOT NULL AND vendor IS NULL AND override_key = 'device:' || device_id)),
    CONSTRAINT snmp_polling_override_vendor_once UNIQUE (vendor),
    CONSTRAINT snmp_polling_override_device_once UNIQUE (device_id)
);
COMMENT ON TABLE snmp_polling_override IS
    'Per vendor or per device scope and credential. Pilot vendors only (check_point, palo_alto; PO decision 4).';

CREATE TABLE IF NOT EXISTS snmp_poll_state (
    device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
    consecutive_misses INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_misses >= 0),
    last_outcome TEXT CHECK (last_outcome IN ('OK', 'PARTIAL', 'SNMP_NO_ANSWER', 'SNMP_AUTH_REFUSED',
        'SNMP_NO_CREDENTIAL', 'SNMP_CREDENTIAL_UNSUITABLE', 'SNMP_NO_ADDRESS', 'SNMP_GATE_MISSING')),
    last_poll_at TIMESTAMPTZ,
    last_answer_at TIMESTAMPTZ,
    next_poll_at TIMESTAMPTZ,
    last_sys_uptime_cs BIGINT CHECK (last_sys_uptime_cs >= 0),
    last_reboot_detected_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS device_metric_sample (
    device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    sampled_at TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL DEFAULT 'SNMP' CHECK (source IN ('SNMP')),
    outcome TEXT NOT NULL CHECK (outcome IN ('OK', 'PARTIAL', 'SNMP_NO_ANSWER', 'SNMP_AUTH_REFUSED')),
    sys_uptime_cs BIGINT CHECK (sys_uptime_cs >= 0),
    rebooted BOOLEAN NOT NULL DEFAULT false,
    cpu_pct NUMERIC(5, 2) CHECK (cpu_pct BETWEEN 0 AND 100),
    cpu_dataplane_pct NUMERIC(5, 2) CHECK (cpu_dataplane_pct BETWEEN 0 AND 100),
    mem_pct NUMERIC(5, 2) CHECK (mem_pct BETWEEN 0 AND 100),
    sessions BIGINT CHECK (sessions >= 0),
    PRIMARY KEY (device_id, sampled_at),
    CONSTRAINT device_metric_sample_no_answer_is_null CHECK (outcome IN ('OK', 'PARTIAL')
        OR (sys_uptime_cs IS NULL AND cpu_pct IS NULL AND cpu_dataplane_pct IS NULL AND mem_pct IS NULL
            AND sessions IS NULL))
);
CREATE INDEX IF NOT EXISTS device_metric_sample_time ON device_metric_sample(sampled_at);
COMMENT ON TABLE device_metric_sample IS
    'One row per SNMP poll. NULL = no answer (UNKNOWN), never 0. Raw retention: snmp_polling_settings.raw_retention_days.';
COMMENT ON COLUMN device_metric_sample.cpu_pct IS
    'Check Point procUsage; Palo Alto hrProcessorLoad of the lowest row index (management plane).';
COMMENT ON COLUMN device_metric_sample.cpu_dataplane_pct IS
    'Palo Alto only: the highest hrProcessorLoad of the other rows (data plane). NULL for Check Point.';

CREATE TABLE IF NOT EXISTS device_metric_hourly (
    device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    hour_start TIMESTAMPTZ NOT NULL,
    samples INTEGER NOT NULL CHECK (samples >= 0),
    answered INTEGER NOT NULL CHECK (answered >= 0),
    cpu_p95 NUMERIC(5, 2),
    cpu_max NUMERIC(5, 2),
    cpu_dataplane_p95 NUMERIC(5, 2),
    cpu_dataplane_max NUMERIC(5, 2),
    mem_p95 NUMERIC(5, 2),
    mem_max NUMERIC(5, 2),
    sessions_p95 BIGINT,
    sessions_max BIGINT,
    rebooted BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (device_id, hour_start)
);
CREATE INDEX IF NOT EXISTS device_metric_hourly_time ON device_metric_hourly(hour_start);
COMMENT ON TABLE device_metric_hourly IS
    'Hourly p95 and maximum of device_metric_sample. Retention: snmp_polling_settings.hourly_retention_days.';

-- Grants: the service edits settings and overrides and reads the rest; the worker writes samples, rollups and state.
GRANT SELECT, INSERT, UPDATE, DELETE ON snmp_polling_settings TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON snmp_polling_override TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON snmp_poll_state TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON device_metric_sample TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON device_metric_hourly TO ui2_app;

DO $grants$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ui2_service') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.snmp_polling_settings TO ui2_service';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.snmp_polling_override TO ui2_service';
        EXECUTE 'GRANT SELECT ON TABLE public.snmp_poll_state TO ui2_service';
        EXECUTE 'GRANT SELECT ON TABLE public.device_metric_sample TO ui2_service';
        EXECUTE 'GRANT SELECT ON TABLE public.device_metric_hourly TO ui2_service';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ui2_worker_general') THEN
        EXECUTE 'GRANT SELECT ON TABLE public.snmp_polling_settings TO ui2_worker_general';
        EXECUTE 'GRANT SELECT ON TABLE public.snmp_polling_override TO ui2_worker_general';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.snmp_poll_state TO ui2_worker_general';
        EXECUTE 'GRANT SELECT, INSERT, DELETE ON TABLE public.device_metric_sample TO ui2_worker_general';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.device_metric_hourly TO ui2_worker_general';
    END IF;
END
$grants$;

DROP TRIGGER IF EXISTS trg_audit_snmp_polling_settings ON snmp_polling_settings;
CREATE TRIGGER trg_audit_snmp_polling_settings
    AFTER INSERT OR UPDATE OR DELETE ON snmp_polling_settings
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('settings_id');
DROP TRIGGER IF EXISTS trg_audit_snmp_polling_override ON snmp_polling_override;
CREATE TRIGGER trg_audit_snmp_polling_override
    AFTER INSERT OR UPDATE OR DELETE ON snmp_polling_override
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('override_key');

-- Seeded after the triggers exist, so the audit log records where the defaults came from. The fleet switch stays OFF;
-- both pilot vendors are in scope once an admin turns it on.
INSERT INTO snmp_polling_settings(settings_id, updated_by) VALUES ('default', 'snmp-polling-migration')
ON CONFLICT (settings_id) DO NOTHING;
INSERT INTO snmp_polling_override(override_key, scope_kind, vendor, in_scope, updated_by) VALUES
    ('vendor:check_point', 'VENDOR', 'check_point', true, 'snmp-polling-migration'),
    ('vendor:palo_alto', 'VENDOR', 'palo_alto', true, 'snmp-polling-migration')
ON CONFLICT (override_key) DO NOTHING;
