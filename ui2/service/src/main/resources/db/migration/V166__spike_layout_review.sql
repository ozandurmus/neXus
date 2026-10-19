-- V166 -- the one-off Spike Detective layout review (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §8.3; PO approval
-- 2026-10-19; docs/design/SPIKE_DETECTIVE_LAYOUT_REVIEW_2026_10_19.md). The cp_spike_layout_review job issues the
-- already approved and seeded V160 reads C4 (cp_syslog_collect_ls_spike) and one C5 (cp_syslog_collect_ls_spike_dir)
-- on one Check Point gateway and stores one summary row (counts, sizes, name classes, an age class; never a name, a
-- path or a line) in system_log_measurement. No gate row, command, session or frequency changes here.
--   1. ui2_job_module: cp_spike_layout_review belongs to the diagnostics module. V160's mapping byte-for-byte plus
--      this one capability in the diagnostics branch; without a module the claim statement never selects the job and
--      the measurement write's lease fence (ui2_job_owner_valid) never admits it.
--   2. system_log_measurement: the Check Point source list of V159 plus 'cp_spike_layout_review'. The summary checks of
--      V151 (typed status, no raw-body keys) are unchanged.
-- Every statement is repeatable (drop-then-create), so the dry run can run the file twice inside one rolled-back
-- transaction.
SELECT set_config('app.actor_fingerprint', 'migration:V166_spike_layout_review', true);

CREATE OR REPLACE FUNCTION ui2_job_module(capability TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT CASE
 WHEN capability IN ('cp_policy_collect','pan_policy_collect') THEN 'policy'
 WHEN capability IN ('cp_gateway_backup','cp_spark_sftp_backup','cp_gaia_snapshot','pan_device_state_backup','cp_mds_export','https_vendor_backup','rdw_cc_config_backup','asa_config_backup','fgt_config_backup') THEN 'backup'
 WHEN capability IN ('device_confirm_check_point','device_confirm_palo_alto','device_confirm_https','device_confirm_cisco_asa','device_confirm_fortigate','cp_inventory_collect','pan_inventory_collect','https_inventory_collect','asa_inventory_collect','fgt_inventory_collect','cp_discovery_enumerate','pan_discovery_enumerate','rdw_discovery_enumerate','fmg_discovery_enumerate','bcmc_discovery_enumerate') THEN 'inventory'
 WHEN capability IN ('cp_failover_readiness','pan_failover_readiness','cp_cluster_failover','pan_cluster_failover') THEN 'failover'
 WHEN capability IN ('diagnostic_read','fmg_interface_detail','pan_syslog_measure','cp_system_log_collect','pan_system_log_collect','cp_spike_layout_review') THEN 'diagnostics'
 WHEN capability IN ('cp_configuration_collect','pan_configuration_collect','fgt_configuration_collect','asa_configuration_collect','proxysg_configuration_collect') THEN 'configuration'
 WHEN capability = 'packet_analysis_run' THEN 'packet_analysis'
 ELSE NULL END
$$;

ALTER TABLE system_log_measurement DROP CONSTRAINT IF EXISTS system_log_measurement_vendor_source_check;
ALTER TABLE system_log_measurement ADD CONSTRAINT system_log_measurement_vendor_source_check CHECK (
    (vendor = 'palo_alto' AND source IN ('pan_system', 'pan_config'))
    OR (vendor = 'check_point' AND source IN (
        'cp_syslog_measure_stat', 'cp_syslog_measure_stat_ext',
        'cp_syslog_measure_ls_sa', 'cp_syslog_measure_ls_spike', 'cp_syslog_measure_ls_hcp', 'cp_syslog_measure_ls_crash',
        'cp_syslog_measure_tail_messages', 'cp_syslog_measure_tail_fwd', 'cp_syslog_measure_tail_cpd',
        'cp_syslog_measure_tail_cpwd', 'cp_syslog_measure_tail_fwk', 'cp_syslog_measure_tail_spike',
        'cp_syslog_measure_tail_spike_csv', 'cp_syslog_measure_head_sar', 'cp_syslog_measure_hcp_listing',
        'cp_spike_layout_review')));
