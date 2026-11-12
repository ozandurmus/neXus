-- V207 -- credential failure alert and the nightly credential canary (PO decision 2026-11-11, "both";
-- docs/design/PO_DECISION_RECORD_2026_11_11_MDS_FORK_STORM_FIXES.md; MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md part D).
--
-- On 2026-11-11 the Check Point backup credential had expired: at the 02:00 scheduled fleet backup 56 gateways and the
-- MDS all failed SSH authentication at once, and nobody was told. Two fixes:
--   1. credential_failure_alert: one OPEN row per backup credential after the first authentication failure; later
--      failures update it (jobs, distinct devices); a successful use of the same credential (a completed backup or a
--      passing canary) closes it ('auth_succeeded'). While it is open the scheduled fleet backup skips the devices that
--      use that credential, and two distinct failed devices in one scheduled run cancel that night's remaining queued
--      scheduled backups for it ('credential_auth_failed_hold').
--   2. credential_canary: a read-only job, one per backup credential, run before the fleet slot. It connects with SSH
--      (authentication only) and disconnects; it sends no device command. It belongs to the backup module.
--
-- The credential reference is never stored: only credential_ref_digest, the first 12 hex characters of the SHA-256 of
-- the reference string. failed_device_ids / run_device_ids hold opaque device ids (no hostname, address or serial).
-- The partial unique index allows at most one open alert per credential. Alert delivery (syslog line, mail digest line)
-- reads changed_at: set when the alert opens and whenever failed_device_count changes. Rows are tiny and a closed row
-- is history, so nothing prunes them; no new notification route type is needed (the lines ride the backup_failure route).
SELECT set_config('app.actor_fingerprint', 'migration:V207_credential_failure_alert', true);
SELECT set_config('app.action_id', 'credential_failure_alert_schema_by_migration', true);

CREATE TABLE IF NOT EXISTS credential_failure_alert (
    alert_id             UUID        PRIMARY KEY,
    credential_ref_digest TEXT       NOT NULL CHECK (credential_ref_digest ~ '^[0-9a-f]{12}$'),
    vendor               TEXT        NOT NULL CHECK (vendor ~ '^[a-z_]{1,32}$'),
    first_failure_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_failure_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    failed_job_count     INTEGER     NOT NULL DEFAULT 1 CHECK (failed_job_count >= 1),
    failed_device_count  INTEGER     NOT NULL DEFAULT 1 CHECK (failed_device_count >= 1),
    failed_device_ids    TEXT[]      NOT NULL DEFAULT '{}',
    run_device_ids       TEXT[]      NOT NULL DEFAULT '{}',
    last_job_id          TEXT,
    scheduled_day        DATE,
    changed_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at            TIMESTAMPTZ,
    closed_reason        TEXT        CHECK (closed_reason IS NULL OR closed_reason ~ '^[a-z_]{1,64}$'),
    CONSTRAINT credential_failure_alert_closed_complete CHECK ((closed_at IS NULL) = (closed_reason IS NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_credential_failure_alert_open
    ON credential_failure_alert(credential_ref_digest) WHERE closed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_credential_failure_alert_changed ON credential_failure_alert(changed_at);

COMMENT ON TABLE credential_failure_alert IS 'One open alert per backup credential (by digest) after an authentication failure; closed by the next successful use.';
COMMENT ON COLUMN credential_failure_alert.credential_ref_digest IS 'First 12 hex of sha256(credential reference string); the reference itself is never stored.';
COMMENT ON COLUMN credential_failure_alert.run_device_ids IS 'Distinct devices that failed in the scheduled run named by scheduled_day; two of them hold the rest of that night.';
COMMENT ON COLUMN credential_failure_alert.changed_at IS 'Set when the alert opens and when failed_device_count changes; the syslog and mail deliveries read it.';

GRANT SELECT, INSERT, UPDATE ON credential_failure_alert TO ui2_app;

DROP TRIGGER IF EXISTS trg_audit_credential_failure_alert ON credential_failure_alert;
CREATE TRIGGER trg_audit_credential_failure_alert
    AFTER INSERT OR UPDATE OR DELETE ON credential_failure_alert
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('alert_id');

-- The canary job type belongs to the backup module (V188's mapping byte-for-byte plus this one capability); without a
-- module the claim statement never selects the job.
CREATE OR REPLACE FUNCTION ui2_job_module(capability TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT CASE
 WHEN capability IN ('cp_policy_collect','pan_policy_collect') THEN 'policy'
 WHEN capability IN ('cp_gateway_backup','cp_spark_sftp_backup','cp_gaia_snapshot','pan_device_state_backup','cp_mds_export','https_vendor_backup','rdw_cc_config_backup','asa_config_backup','fgt_config_backup','credential_canary') THEN 'backup'
 WHEN capability IN ('device_confirm_check_point','device_confirm_palo_alto','device_confirm_https','device_confirm_cisco_asa','device_confirm_fortigate','cp_inventory_collect','pan_inventory_collect','https_inventory_collect','asa_inventory_collect','fgt_inventory_collect','cp_discovery_enumerate','pan_discovery_enumerate','rdw_discovery_enumerate','fmg_discovery_enumerate','bcmc_discovery_enumerate') THEN 'inventory'
 WHEN capability IN ('cp_failover_readiness','pan_failover_readiness','cp_cluster_failover','pan_cluster_failover','pan_failover_config_sync') THEN 'failover'
 WHEN capability IN ('diagnostic_read','fmg_interface_detail','pan_syslog_measure','cp_system_log_collect','pan_system_log_collect','cp_spike_layout_review','path_validation') THEN 'diagnostics'
 WHEN capability IN ('cp_configuration_collect','pan_configuration_collect','fgt_configuration_collect','asa_configuration_collect','proxysg_configuration_collect') THEN 'configuration'
 WHEN capability = 'packet_analysis_run' THEN 'packet_analysis'
 ELSE NULL END
$$;
