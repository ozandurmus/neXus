-- Per-module database roles, step 0 (M1) + post-V242 grant refresh
-- (PO 2026-11-16: DB reach protection = per-module DB users with least privilege first;
--  docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md §2.2, §2.5.1, §4.2 Step 0, §6.2 F1-F3).
--
-- ADDITIVE & REVERSIBLE. This migration prepares the database schema so that per-module runtime roles can execute
-- real row mutations with row triggers enabled (session_replication_role = origin) without hitting 42501
-- (insufficient_privilege) inside cross-domain trigger or lease helper functions:
--   1. Converts all 27 SECURITY INVOKER trigger functions and 8 cross-domain runtime/lease helper functions to
--      SECURITY DEFINER SET search_path = pg_catalog, public (owned by ui2_migrate) and revokes PUBLIC execute.
--   2. Pins search_path = pg_catalog, public on all existing SECURITY DEFINER functions in schema public.
--   3. Installs Step 0 helper functions:
--        - ui2_current_role_module() RETURNS TEXT
--        - ui2_schema_version_check(expected_version TEXT) RETURNS BOOLEAN
--        - ui2_capability_owner_module(p_capability TEXT) RETURNS TEXT
--        - ui2_next_task_lease_epoch() RETURNS BIGINT
--   4. Installs Step 0 read-only SECURITY DEFINER projection views (owned by ui2_migrate):
--        - v_latest_effective_running_config
--        - v_compliance_device_evidence
--        - v_active_pseudonym_lookup
--   5. Updates ui2_apply_module_role_grants(p_role_prefix TEXT DEFAULT 'ui2_') to include the V243-V252 tables,
--      runtime_instance_heartbeat worker writes, narrow column-level UPDATE grants on devices (Finding F2),
--      SELECT on the three projection views, and EXECUTE on the Step 0 functions, then runs it once.
--
-- No role is created or altered, no password or secret appears here, and ui2_app keeps every privilege it holds.

-- ---------------------------------------------------------------------
-- 1. All 27 trigger functions -> SECURITY DEFINER, search_path = pg_catalog, public
-- ---------------------------------------------------------------------

DO $triggers$
DECLARE
    v_sig TEXT;
BEGIN
    FOREACH v_sig IN ARRAY ARRAY[
        'ui2_failover_job_failure()',
        'ui2_failover_readiness_job_terminal()',
        'ui2_failover_resume_job_end()',
        'ui2_pan_config_sync_job_end()',
        'ui2_fence_claim()',
        'ui2_failover_membership_approval_invalidate()',
        'ui2_failover_discovery_approval_invalidate()',
        'ui2_record_instance_heartbeat()',
        'fn_capture_policy_rule_history()',
        'ui2_failover_run_guard()',
        'ui2_failover_dispatch_admission()',
        'ui2_failover_dispatch_guard()',
        'ui2_failover_incident_guard()',
        'ui2_failover_approval_guard()',
        'ui2_failover_run_approval_guard()',
        'ui2_failover_run_resume_fence()',
        'ui2_failover_resume_guard()',
        'ui2_pan_config_sync_guard()',
        'ui2_stamp_drain_requested_at()',
        'failover_history_immutable()',
        'failover_ledger_append()',
        'fn_expire_directory_authz_after_session_end()',
        'fn_topology_override_orphan_mark()',
        'fn_zone_matrix_append_only()',
        'fn_zone_matrix_version_guard()',
        'fn_guard_cutover_assessment_flow()',
        'fn_guard_script_version_append_only()'
    ] LOOP
        EXECUTE format('ALTER FUNCTION public.%s SECURITY DEFINER SET search_path = pg_catalog, public', v_sig);
        EXECUTE format('REVOKE ALL ON FUNCTION public.%s FROM PUBLIC', v_sig);
    END LOOP;
END
$triggers$;

-- Pin search_path = pg_catalog, public on all existing SECURITY DEFINER audit/RPC functions as well.
DO $existing_secdef$
DECLARE
    v_sig TEXT;
BEGIN
    FOREACH v_sig IN ARRAY ARRAY[
        'fn_audit_capture()',
        'fn_audit_redact(text, jsonb)',
        'fn_audit_redaction_is_applied(text, jsonb)',
        'fn_audit_capture_local_credentials()',
        'fn_audit_capture_credentials()',
        'fn_audit_management_endpoint_ssh_trust()',
        'fn_audit_management_endpoint_pan_cert_trust()',
        'audit_machine_session_event(text, text)',
        'fn_audit_notification_config()',
        'fn_audit_https_endpoint_cert_trust()',
        'ui2_audit_quarantine_release()',
        'fn_audit_policy_offline_analysis()',
        'fn_audit_ssh_host_key_policy()',
        'fn_audit_sanitize_vault_entry()',
        'audit_report_download(uuid)',
        'fn_audit_capture_ldap_settings()',
        'fn_audit_capture_zone_matrix_revision()',
        'fn_audit_capture_zone_matrix_access()',
        'fn_audit_cutover_segment()',
        'fn_audit_cutover_assessment()',
        'audit_support_bundle_download(uuid)',
        'fn_audit_capture_saml_settings()'
    ] LOOP
        EXECUTE format('ALTER FUNCTION public.%s SECURITY DEFINER SET search_path = pg_catalog, public', v_sig);
        EXECUTE format('REVOKE ALL ON FUNCTION public.%s FROM PUBLIC', v_sig);
    END LOOP;
END
$existing_secdef$;

-- ---------------------------------------------------------------------
-- 2. Cross-domain runtime/lease helpers -> SECURITY DEFINER
-- ---------------------------------------------------------------------

DO $helpers$
DECLARE
    v_sig TEXT;
BEGIN
    FOREACH v_sig IN ARRAY ARRAY[
        'ui2_quarantine_owner_gone(text)',
        'ui2_release_orphan_quarantines()',
        'ui2_failover_lease_settled(text)',
        'ui2_release_settled_failover_leases(text)',
        'ui2_config_sync_lease_settled(text)',
        'ui2_release_settled_config_sync_leases()',
        'ui2_release_failover_incident(text, text, text, text, text)',
        'ui2_job_owner_valid(text, bigint)',
        'ui2_record_software_version(text, text, text, text)'
    ] LOOP
        EXECUTE format('ALTER FUNCTION public.%s SECURITY DEFINER SET search_path = pg_catalog, public', v_sig);
        EXECUTE format('REVOKE ALL ON FUNCTION public.%s FROM PUBLIC', v_sig);
        EXECUTE format('GRANT EXECUTE ON FUNCTION public.%s TO ui2_app', v_sig);
    END LOOP;
END
$helpers$;

-- ---------------------------------------------------------------------
-- 3. Step 0 helper functions
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION ui2_current_role_module()
RETURNS TEXT
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $fn$
    SELECT CASE current_user
        WHEN 'ui2_policy'          THEN 'policy'
        WHEN 'ui2_packet_analysis' THEN 'packet_analysis'
        WHEN 'ui2_backup'          THEN 'backup'
        WHEN 'ui2_inventory'       THEN 'inventory'
        WHEN 'ui2_diagnostics'     THEN 'diagnostics'
        WHEN 'ui2_failover'        THEN 'failover'
        WHEN 'ui2_configuration'   THEN 'configuration'
        WHEN 'ui2_compliance'      THEN 'compliance'
        WHEN 'ui2_scheduler'       THEN 'scheduler'
        WHEN 'ui2_service'         THEN 'service'
        WHEN 'ui2_worker_general'  THEN 'general'
        ELSE 'none'
    END;
$fn$;

REVOKE ALL ON FUNCTION ui2_current_role_module() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ui2_current_role_module() TO ui2_app;

CREATE OR REPLACE FUNCTION ui2_schema_version_check(expected_version TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $fn$
    SELECT CASE
        WHEN expected_version IS NULL OR btrim(expected_version) = '' THEN FALSE
        WHEN NOT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname = 'flyway_schema_history'
        ) THEN FALSE
        ELSE EXISTS (
            SELECT 1
            FROM public.flyway_schema_history
            WHERE success = TRUE
              AND version = ltrim(btrim(expected_version), 'Vv')
        ) AND NOT EXISTS (
            SELECT 1
            FROM public.flyway_schema_history
            WHERE success = FALSE
        )
    END;
$fn$;

REVOKE ALL ON FUNCTION ui2_schema_version_check(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ui2_schema_version_check(TEXT) TO ui2_app;

CREATE OR REPLACE FUNCTION ui2_capability_owner_module(p_capability TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
STRICT
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $fn$
    SELECT COALESCE(
        public.ui2_job_module(p_capability),
        CASE
            WHEN p_capability IN ('cp_support_bundle', 'pan_support_bundle', 'asa_support_bundle', 'fgt_support_bundle')
            THEN 'diagnostics'
            ELSE NULL
        END
    );
$fn$;

REVOKE ALL ON FUNCTION ui2_capability_owner_module(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ui2_capability_owner_module(TEXT) TO ui2_app;

CREATE OR REPLACE FUNCTION ui2_next_task_lease_epoch()
RETURNS BIGINT
LANGUAGE sql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $fn$
    SELECT nextval('public.endpoint_permit_epoch');
$fn$;

REVOKE ALL ON FUNCTION ui2_next_task_lease_epoch() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ui2_next_task_lease_epoch() TO ui2_app;

-- ---------------------------------------------------------------------
-- 4. Step 0 read-only SECURITY DEFINER projection views (owned by ui2_migrate)
-- ---------------------------------------------------------------------

CREATE OR REPLACE VIEW v_latest_effective_running_config AS
SELECT DISTINCT ON (r.device_id)
    r.device_id,
    r.run_id,
    r.collected_at,
    r.artefact_ref,
    a.artefact_id,
    c.plaintext_sha256 AS sha256_hex,
    c.compression,
    a.wrapped_data_key
FROM public.device_configuration_run r
JOIN public.configuration_artefact c
  ON c.artefact_ref = r.artefact_ref
JOIN public.backup_artefact a
  ON a.recovery_volume_path = r.artefact_ref
 AND a.device_id = r.device_id
WHERE r.read_kind = 'effective_running'
  AND NOT EXISTS (
      SELECT 1
      FROM public.artefact_retention_ledger l
      WHERE l.artefact_id = a.artefact_id
        AND l.event = 'removed'
  )
ORDER BY r.device_id, r.collected_at DESC;

GRANT SELECT ON v_latest_effective_running_config TO ui2_app;

CREATE OR REPLACE VIEW v_compliance_device_evidence AS
SELECT
    d.device_id,
    d.role AS device_role,
    d.vendor_hint,
    d.enrollment_state,
    d.disabled,
    d.observed_hostname,
    d.observed_model,
    d.observed_software_version,
    d.observed_ha_role,
    d.cluster_member_ref,
    pf.hotfix_level,
    pf.platform_family,
    pf.content_versions,
    pf.uptime_text,
    inv.run_id AS latest_inventory_run_id,
    inv.collected_at AS latest_inventory_collected_at,
    cfg.run_id AS latest_configuration_run_id,
    cfg.collected_at AS latest_configuration_collected_at
FROM public.devices d
LEFT JOIN public.device_platform_facts pf
  ON pf.device_id = d.device_id
LEFT JOIN LATERAL (
    SELECT ir.run_id, ir.collected_at
    FROM public.device_inventory_run ir
    WHERE ir.device_id = d.device_id
    ORDER BY ir.collected_at DESC
    LIMIT 1
) inv ON TRUE
LEFT JOIN LATERAL (
    SELECT cr.run_id, cr.collected_at
    FROM public.device_configuration_run cr
    WHERE cr.device_id = d.device_id
      AND cr.is_primary = TRUE
    ORDER BY cr.collected_at DESC
    LIMIT 1
) cfg ON TRUE;

GRANT SELECT ON v_compliance_device_evidence TO ui2_app;

CREATE OR REPLACE VIEW v_active_pseudonym_lookup AS
SELECT
    kind,
    raw_key,
    pseudonym,
    claimed_at
FROM public.pseudonym_registry;

GRANT SELECT ON v_active_pseudonym_lookup TO ui2_app;

-- ---------------------------------------------------------------------
-- 5. Updated idempotent module grant function (V242 + V243..V252 + Step 0)
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION ui2_apply_module_role_grants(p_role_prefix TEXT DEFAULT 'ui2_')
RETURNS INTEGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
DECLARE
    v_suffixes CONSTANT TEXT[] := ARRAY['service', 'policy', 'packet_analysis', 'backup', 'inventory',
        'diagnostics', 'failover', 'configuration', 'compliance', 'scheduler', 'worker_general'];
    v_suffix  TEXT;
    v_role    TEXT;
    v_present TEXT[] := ARRAY[]::TEXT[];
    v_missing INTEGER := 0;
    v_rec     RECORD;
    v_privs   TEXT;
BEGIN
    FOREACH v_suffix IN ARRAY v_suffixes LOOP
        v_role := p_role_prefix || v_suffix;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role) THEN
            v_present := array_append(v_present, v_suffix);
            EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', v_role);
            BEGIN
                EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), v_role);
            EXCEPTION WHEN insufficient_privilege THEN
                RAISE NOTICE 'CONNECT on database % was not granted to % (not the database owner); grant it as the owner',
                    current_database(), v_role;
            END;
        ELSE
            v_missing := v_missing + 1;
            RAISE NOTICE 'module database role % does not exist; its grants are skipped until it exists (then run SELECT ui2_apply_module_role_grants())', v_role;
        END IF;
    END LOOP;

    -- Tables and views: (relation, role suffix, privilege letters).
    FOR v_rec IN
        SELECT g.table_name, g.role_suffix, g.privs
        FROM (VALUES
        ('actor_authz_state', 'service', 'SIUD'),
        ('advisory', 'service', 'SIUD'),
        ('advisory', 'compliance', 'S'),
        ('advisory_feed_run', 'service', 'SIUD'),
        ('advisory_feed_run', 'scheduler', 'SIU'),
        ('advisory_feed_run', 'worker_general', 'SIU'),
        ('advisory_product_rule', 'service', 'S'),
        ('advisory_product_rule', 'compliance', 'S'),
        ('artefact_retention_ledger', 'service', 'SI'),
        ('artefact_retention_ledger', 'backup', 'SI'),
        ('artefact_retention_ledger', 'worker_general', 'SI'),
        ('artefact_store_quota', 'service', 'SIUD'),
        ('artefact_store_quota', 'backup', 'S'),
        ('artefact_store_quota', 'diagnostics', 'S'),
        ('artefact_store_quota', 'configuration', 'S'),
        ('artefact_store_quota', 'worker_general', 'S'),
        ('artefact_store_refusal', 'service', 'SI'),
        ('artefact_store_refusal', 'backup', 'I'),
        ('artefact_store_refusal', 'diagnostics', 'I'),
        ('artefact_store_refusal', 'configuration', 'I'),
        ('artefact_store_refusal', 'worker_general', 'I'),
        ('asa_config_relation', 'service', 'SIUD'),
        ('asa_config_relation', 'backup', 'SIU'),
        ('asa_config_relation', 'worker_general', 'SIU'),
        ('audit_log', 'service', 'S'),
        ('audit_log', 'scheduler', 'S'),
        ('audit_log', 'worker_general', 'S'),
        ('authz_decisions', 'service', 'SI'),
        ('backup_artefact', 'service', 'SUD'),
        ('backup_artefact', 'backup', 'SIUD'),
        ('backup_artefact', 'configuration', 'S'),
        ('backup_artefact', 'compliance', 'S'),
        ('backup_artefact', 'scheduler', 'S'),
        ('backup_artefact', 'worker_general', 'SIUD'),
        ('backup_artefact_content_listing', 'service', 'SD'),
        ('backup_artefact_content_listing', 'backup', 'SIUD'),
        ('backup_artefact_content_listing', 'worker_general', 'SIUD'),
        ('backup_artefact_deletion_request', 'service', 'SIUD'),
        ('backup_artefact_deletion_request', 'backup', 'SU'),
        ('backup_artefact_deletion_request', 'worker_general', 'SU'),
        ('backup_artefact_entry', 'service', 'SD'),
        ('backup_artefact_entry', 'backup', 'SIUD'),
        ('backup_artefact_entry', 'configuration', 'S'),
        ('backup_artefact_entry', 'worker_general', 'SIUD'),
        ('backup_artefact_retrieval', 'service', 'SI'),
        ('backup_baseline', 'service', 'SIUD'),
        ('backup_baseline', 'backup', 'SIU'),
        ('backup_baseline', 'worker_general', 'SIU'),
        ('backup_deviation_record', 'service', 'SU'),
        ('backup_deviation_record', 'backup', 'SIU'),
        ('backup_deviation_record', 'worker_general', 'SIU'),
        ('backup_endpoint_ineligibility', 'service', 'SD'),
        ('backup_endpoint_ineligibility', 'backup', 'SID'),
        ('backup_endpoint_ineligibility', 'scheduler', 'S'),
        ('backup_endpoint_ineligibility', 'worker_general', 'SID'),
        ('backup_policy', 'service', 'SU'),
        ('backup_policy', 'backup', 'S'),
        ('backup_policy', 'scheduler', 'SU'),
        ('backup_policy', 'worker_general', 'SU'),
        ('checkpoint_jumbo_page_load', 'service', 'SIUD'),
        ('checkpoint_jumbo_statement', 'service', 'SIUD'),
        ('cluster_member_diff', 'service', 'SID'),
        ('cluster_member_diff', 'configuration', 'SID'),
        ('cluster_member_diff', 'compliance', 'S'),
        ('cluster_member_diff', 'worker_general', 'SID'),
        ('compliance_evaluation', 'service', 'SIUD'),
        ('compliance_evaluation', 'compliance', 'SIUD'),
        ('configuration_artefact', 'service', 'SD'),
        ('configuration_artefact', 'diagnostics', 'S'),
        ('configuration_artefact', 'failover', 'S'),
        ('configuration_artefact', 'configuration', 'SIU'),
        ('configuration_artefact', 'compliance', 'S'),
        ('configuration_artefact', 'worker_general', 'SIU'),
        ('configuration_notification', 'service', 'SUD'),
        ('configuration_notification', 'configuration', 'SIU'),
        ('configuration_notification', 'scheduler', 'SU'),
        ('configuration_notification', 'worker_general', 'SIU'),
        ('configuration_run_deviation_entry', 'service', 'SIUD'),
        ('configuration_run_deviation_entry', 'configuration', 'SIUD'),
        ('configuration_run_deviation_entry', 'compliance', 'S'),
        ('configuration_run_deviation_entry', 'worker_general', 'SIUD'),
        ('configuration_run_deviation_summary', 'service', 'SIUD'),
        ('configuration_run_deviation_summary', 'configuration', 'SIUD'),
        ('configuration_run_deviation_summary', 'compliance', 'S'),
        ('configuration_run_deviation_summary', 'worker_general', 'SIUD'),
        ('cp_device_archive', 'service', 'S'),
        ('cp_device_archive', 'backup', 'SIU'),
        ('cp_device_archive', 'configuration', 'S'),
        ('cp_device_archive', 'worker_general', 'SIU'),
        ('cp_domain_server_placement', 'service', 'SD'),
        ('cp_domain_server_placement', 'policy', 'SIUD'),
        ('cp_domain_server_placement', 'inventory', 'SIUD'),
        ('cp_domain_server_placement', 'worker_general', 'SIUD'),
        ('cp_inventory_projection', 'service', 'SD'),
        ('cp_inventory_projection', 'inventory', 'SIUD'),
        ('cp_inventory_projection', 'worker_general', 'SIUD'),
        ('cp_policy_domain_run', 'service', 'SD'),
        ('cp_policy_domain_run', 'policy', 'SIU'),
        ('cp_policy_domain_run', 'worker_general', 'SIU'),
        ('cp_policy_json_chunk', 'service', 'SD'),
        ('cp_policy_json_chunk', 'policy', 'SIUD'),
        ('cp_policy_json_chunk', 'worker_general', 'SIUD'),
        ('cp_policy_object_inventory', 'service', 'SD'),
        ('cp_policy_object_inventory', 'policy', 'SIUD'),
        ('cp_policy_object_inventory', 'worker_general', 'SIUD'),
        ('credential_failure_alert', 'service', 'SU'),
        ('credential_failure_alert', 'policy', 'SIU'),
        ('credential_failure_alert', 'backup', 'SIU'),
        ('credential_failure_alert', 'inventory', 'SIU'),
        ('credential_failure_alert', 'diagnostics', 'SIU'),
        ('credential_failure_alert', 'failover', 'SIU'),
        ('credential_failure_alert', 'configuration', 'SIU'),
        ('credential_failure_alert', 'scheduler', 'S'),
        ('credential_failure_alert', 'worker_general', 'SIU'),
        ('credential_references', 'service', 'SIUD'),
        ('credential_references', 'policy', 'S'),
        ('credential_references', 'backup', 'S'),
        ('credential_references', 'inventory', 'S'),
        ('credential_references', 'diagnostics', 'S'),
        ('credential_references', 'failover', 'S'),
        ('credential_references', 'configuration', 'S'),
        ('credential_references', 'worker_general', 'S'),
        ('credentials', 'service', 'SIUD'),
        ('credentials', 'policy', 'S'),
        ('credentials', 'backup', 'S'),
        ('credentials', 'inventory', 'S'),
        ('credentials', 'diagnostics', 'S'),
        ('credentials', 'failover', 'S'),
        ('credentials', 'configuration', 'S'),
        ('credentials', 'worker_general', 'S'),
        ('cutover_assessment', 'service', 'SIUD'),
        ('cutover_assessment_flow', 'service', 'SIUD'),
        ('cutover_segment', 'service', 'SIUD'),
        ('device_configuration_index', 'service', 'SD'),
        ('device_configuration_index', 'configuration', 'SIUD'),
        ('device_configuration_index', 'compliance', 'S'),
        ('device_configuration_index', 'worker_general', 'SIUD'),
        ('device_configuration_override', 'service', 'SIUD'),
        ('device_configuration_override', 'configuration', 'S'),
        ('device_configuration_override', 'compliance', 'S'),
        ('device_configuration_override', 'worker_general', 'S'),
        ('device_configuration_run', 'service', 'SD'),
        ('device_configuration_run', 'diagnostics', 'S'),
        ('device_configuration_run', 'failover', 'S'),
        ('device_configuration_run', 'configuration', 'SIU'),
        ('device_configuration_run', 'compliance', 'S'),
        ('device_configuration_run', 'scheduler', 'S'),
        ('device_configuration_run', 'worker_general', 'SIU'),
        ('device_crash_alert', 'service', 'SUD'),
        ('device_crash_alert', 'inventory', 'SIU'),
        ('device_crash_alert', 'diagnostics', 'SIU'),
        ('device_crash_alert', 'scheduler', 'S'),
        ('device_crash_alert', 'worker_general', 'SIU'),
        ('device_crash_entry', 'service', 'SD'),
        ('device_crash_entry', 'inventory', 'SIU'),
        ('device_crash_entry', 'diagnostics', 'SIU'),
        ('device_crash_entry', 'worker_general', 'SIU'),
        ('device_identity_check', 'service', 'SIUD'),
        ('device_identity_check', 'policy', 'SI'),
        ('device_identity_check', 'backup', 'SI'),
        ('device_identity_check', 'inventory', 'SIU'),
        ('device_identity_check', 'diagnostics', 'SI'),
        ('device_identity_check', 'failover', 'SI'),
        ('device_identity_check', 'configuration', 'SI'),
        ('device_identity_check', 'worker_general', 'SIU'),
        ('device_install_event', 'service', 'SIUD'),
        ('device_install_event', 'backup', 'SIU'),
        ('device_install_event', 'inventory', 'SIU'),
        ('device_install_event', 'configuration', 'SIU'),
        ('device_install_event', 'worker_general', 'SIU'),
        ('device_interface', 'service', 'SD'),
        ('device_interface', 'inventory', 'SIUD'),
        ('device_interface', 'diagnostics', 'SIU'),
        ('device_interface', 'worker_general', 'SIUD'),
        ('device_interface_address', 'service', 'SD'),
        ('device_interface_address', 'inventory', 'SIUD'),
        ('device_interface_address', 'diagnostics', 'SIU'),
        ('device_interface_address', 'worker_general', 'SIUD'),
        ('device_inventory_ha', 'service', 'SD'),
        ('device_inventory_ha', 'inventory', 'SIUD'),
        ('device_inventory_ha', 'failover', 'S'),
        ('device_inventory_ha', 'compliance', 'S'),
        ('device_inventory_ha', 'worker_general', 'SIUD'),
        ('device_inventory_run', 'service', 'SD'),
        ('device_inventory_run', 'inventory', 'SIU'),
        ('device_inventory_run', 'failover', 'S'),
        ('device_inventory_run', 'compliance', 'S'),
        ('device_inventory_run', 'scheduler', 'S'),
        ('device_inventory_run', 'worker_general', 'SIU'),
        ('device_license_item', 'service', 'SIUD'),
        ('device_license_item', 'inventory', 'SIUD'),
        ('device_license_item', 'compliance', 'S'),
        ('device_license_item', 'worker_general', 'SIUD'),
        ('device_lifecycle_read', 'service', 'SIUD'),
        ('device_lifecycle_read', 'inventory', 'SIU'),
        ('device_lifecycle_read', 'compliance', 'S'),
        ('device_lifecycle_read', 'worker_general', 'SIU'),
        ('device_live_patch_item', 'service', 'SIUD'),
        ('device_live_patch_item', 'inventory', 'SIUD'),
        ('device_live_patch_item', 'compliance', 'S'),
        ('device_live_patch_item', 'worker_general', 'SIUD'),
        ('device_onboarding', 'service', 'SIUD'),
        ('device_onboarding', 'inventory', 'SIU'),
        ('device_onboarding', 'worker_general', 'SIU'),
        ('device_platform_facts', 'service', 'SIUD'),
        ('device_platform_facts', 'policy', 'S'),
        ('device_platform_facts', 'backup', 'SIU'),
        ('device_platform_facts', 'inventory', 'SIU'),
        ('device_platform_facts', 'diagnostics', 'S'),
        ('device_platform_facts', 'failover', 'S'),
        ('device_platform_facts', 'configuration', 'SIU'),
        ('device_platform_facts', 'compliance', 'S'),
        ('device_platform_facts', 'worker_general', 'SIU'),
        ('device_policy_install', 'service', 'SD'),
        ('device_policy_install', 'policy', 'S'),
        ('device_policy_install', 'inventory', 'SIUD'),
        ('device_policy_install', 'compliance', 'S'),
        ('device_policy_install', 'worker_general', 'SIUD'),
        ('device_route', 'service', 'SD'),
        ('device_route', 'inventory', 'SIUD'),
        ('device_route', 'diagnostics', 'S'),
        ('device_route', 'worker_general', 'SIUD'),
        ('device_secret_reference', 'service', 'SIUD'),
        ('device_secret_reference', 'policy', 'S'),
        ('device_secret_reference', 'backup', 'S'),
        ('device_secret_reference', 'inventory', 'S'),
        ('device_secret_reference', 'diagnostics', 'S'),
        ('device_secret_reference', 'failover', 'S'),
        ('device_secret_reference', 'configuration', 'S'),
        ('device_secret_reference', 'worker_general', 'S'),
        ('device_software_version_history', 'service', 'SIUD'),
        ('device_software_version_history', 'backup', 'SIU'),
        ('device_software_version_history', 'inventory', 'SIU'),
        ('device_software_version_history', 'configuration', 'SIU'),
        ('device_software_version_history', 'worker_general', 'SIU'),
        ('devices', 'service', 'SIUD'),
        ('devices', 'policy', 'S'),
        ('devices', 'backup', 'S'),
        ('devices', 'inventory', 'SU'),
        ('devices', 'diagnostics', 'S'),
        ('devices', 'failover', 'S'),
        ('devices', 'configuration', 'S'),
        ('devices', 'compliance', 'S'),
        ('devices', 'scheduler', 'S'),
        ('devices', 'worker_general', 'SU'),
        ('discovery_acknowledgement', 'service', 'SIUD'),
        ('discovery_candidate', 'service', 'SIUD'),
        ('discovery_candidate', 'inventory', 'SIU'),
        ('discovery_candidate', 'failover', 'S'),
        ('discovery_candidate', 'worker_general', 'SIU'),
        ('discovery_run', 'service', 'SIUD'),
        ('discovery_run', 'inventory', 'SIU'),
        ('discovery_run', 'scheduler', 'SI'),
        ('discovery_run', 'worker_general', 'SIU'),
        ('endpoint_admission', 'service', 'SIUD'),
        ('endpoint_admission', 'policy', 'SIUD'),
        ('endpoint_admission', 'backup', 'SIUD'),
        ('endpoint_admission', 'inventory', 'SIUD'),
        ('endpoint_admission', 'diagnostics', 'SIUD'),
        ('endpoint_admission', 'failover', 'SIUD'),
        ('endpoint_admission', 'configuration', 'SIUD'),
        ('endpoint_admission', 'worker_general', 'SIUD'),
        ('endpoints', 'service', 'SIUD'),
        ('endpoints', 'policy', 'S'),
        ('endpoints', 'backup', 'S'),
        ('endpoints', 'inventory', 'SU'),
        ('endpoints', 'diagnostics', 'S'),
        ('endpoints', 'failover', 'S'),
        ('endpoints', 'configuration', 'S'),
        ('endpoints', 'compliance', 'S'),
        ('endpoints', 'scheduler', 'S'),
        ('endpoints', 'worker_general', 'SU'),
        ('failover_approval', 'service', 'SIUD'),
        ('failover_approval', 'failover', 'SU'),
        ('failover_approval', 'scheduler', 'S'),
        ('failover_approval', 'worker_general', 'SU'),
        ('failover_check_result', 'service', 'SD'),
        ('failover_check_result', 'failover', 'SIU'),
        ('failover_check_result', 'worker_general', 'SIU'),
        ('failover_dispatch_intent', 'service', 'SD'),
        ('failover_dispatch_intent', 'failover', 'SIU'),
        ('failover_dispatch_intent', 'worker_general', 'SIU'),
        ('failover_grant_consumption', 'service', 'SI'),
        ('failover_grant_consumption', 'failover', 'SI'),
        ('failover_grant_consumption', 'scheduler', 'SI'),
        ('failover_grant_consumption', 'worker_general', 'SI'),
        ('failover_quarantine', 'service', 'SIUD'),
        ('failover_quarantine', 'failover', 'SIU'),
        ('failover_quarantine', 'scheduler', 'S'),
        ('failover_quarantine', 'worker_general', 'SIU'),
        ('failover_resume', 'service', 'SIU'),
        ('failover_resume', 'failover', 'SU'),
        ('failover_resume', 'worker_general', 'SU'),
        ('failover_run', 'service', 'SIUD'),
        ('failover_run', 'failover', 'SU'),
        ('failover_run', 'scheduler', 'SIU'),
        ('failover_run', 'worker_general', 'SIU'),
        ('failover_schedule_ledger', 'service', 'SI'),
        ('failover_schedule_ledger', 'failover', 'SI'),
        ('failover_schedule_ledger', 'scheduler', 'SI'),
        ('failover_schedule_ledger', 'worker_general', 'SI'),
        ('failover_schedules', 'service', 'SIUD'),
        ('failover_schedules', 'failover', 'SU'),
        ('failover_schedules', 'scheduler', 'SU'),
        ('failover_schedules', 'worker_general', 'SU'),
        ('gate_registry', 'service', 'SIUD'),
        ('gate_registry', 'policy', 'S'),
        ('gate_registry', 'packet_analysis', 'S'),
        ('gate_registry', 'backup', 'S'),
        ('gate_registry', 'inventory', 'S'),
        ('gate_registry', 'diagnostics', 'S'),
        ('gate_registry', 'failover', 'S'),
        ('gate_registry', 'configuration', 'S'),
        ('gate_registry', 'compliance', 'S'),
        ('gate_registry', 'scheduler', 'S'),
        ('gate_registry', 'worker_general', 'S'),
        ('grid_member', 'service', 'SD'),
        ('grid_member', 'inventory', 'SIUD'),
        ('grid_member', 'worker_general', 'SIUD'),
        ('ha_pair_identity', 'service', 'S'),
        ('ha_pair_identity', 'inventory', 'SIU'),
        ('ha_pair_identity', 'failover', 'S'),
        ('ha_pair_identity', 'configuration', 'S'),
        ('ha_pair_identity', 'worker_general', 'SIU'),
        ('https_endpoint_cert_trust', 'service', 'SIUD'),
        ('https_endpoint_cert_trust', 'policy', 'SIU'),
        ('https_endpoint_cert_trust', 'backup', 'SIU'),
        ('https_endpoint_cert_trust', 'inventory', 'SIU'),
        ('https_endpoint_cert_trust', 'diagnostics', 'SIU'),
        ('https_endpoint_cert_trust', 'failover', 'SIU'),
        ('https_endpoint_cert_trust', 'configuration', 'SIU'),
        ('https_endpoint_cert_trust', 'worker_general', 'SIU'),
        ('infoblox_grid_summary', 'service', 'SD'),
        ('infoblox_grid_summary', 'inventory', 'SIUD'),
        ('infoblox_grid_summary', 'worker_general', 'SIUD'),
        ('job_reconciliation', 'service', 'S'),
        ('job_reconciliation', 'scheduler', 'SI'),
        ('job_reconciliation', 'worker_general', 'SI'),
        ('job_step_attempt', 'service', 'S'),
        ('job_step_attempt', 'policy', 'SIU'),
        ('job_step_attempt', 'packet_analysis', 'SIU'),
        ('job_step_attempt', 'backup', 'SIU'),
        ('job_step_attempt', 'inventory', 'SIU'),
        ('job_step_attempt', 'diagnostics', 'SIU'),
        ('job_step_attempt', 'failover', 'SIU'),
        ('job_step_attempt', 'configuration', 'SIU'),
        ('job_step_attempt', 'scheduler', 'SU'),
        ('job_step_attempt', 'worker_general', 'SIU'),
        ('job_steps', 'service', 'SIUD'),
        ('job_steps', 'policy', 'SIU'),
        ('job_steps', 'packet_analysis', 'SIU'),
        ('job_steps', 'backup', 'SIU'),
        ('job_steps', 'inventory', 'SIU'),
        ('job_steps', 'diagnostics', 'SIU'),
        ('job_steps', 'failover', 'SIU'),
        ('job_steps', 'configuration', 'SIU'),
        ('job_steps', 'scheduler', 'SU'),
        ('job_steps', 'worker_general', 'SIU'),
        ('jobs', 'service', 'SIUD'),
        ('jobs', 'policy', 'SU'),
        ('jobs', 'packet_analysis', 'SU'),
        ('jobs', 'backup', 'SU'),
        ('jobs', 'inventory', 'SIU'),
        ('jobs', 'diagnostics', 'SU'),
        ('jobs', 'failover', 'SU'),
        ('jobs', 'configuration', 'SU'),
        ('jobs', 'scheduler', 'SIU'),
        ('jobs', 'worker_general', 'SIU'),
        ('known_exploited_cve', 'service', 'SIUD'),
        ('known_exploited_cve', 'compliance', 'S'),
        ('ldap_settings', 'service', 'SIU'),
        ('lifecycle_catalog', 'service', 'SIU'),
        ('lifecycle_catalog', 'compliance', 'S'),
        ('lifecycle_catalog_upload', 'service', 'SIUD'),
        ('lifecycle_feed_run', 'service', 'SIUD'),
        ('lifecycle_vendor_entry', 'service', 'SIUD'),
        ('local_auth_mode_settings', 'service', 'SIU'),
        ('local_break_glass_event', 'service', 'SI'),
        ('local_credentials', 'service', 'SIUD'),
        ('local_password_history', 'service', 'SID'),
        ('local_password_policy_settings', 'service', 'SIUD'),
        ('management_endpoint_pan_cert_trust', 'service', 'SIU'),
        ('management_endpoint_pan_cert_trust', 'policy', 'SIU'),
        ('management_endpoint_pan_cert_trust', 'backup', 'SIU'),
        ('management_endpoint_pan_cert_trust', 'inventory', 'SIU'),
        ('management_endpoint_pan_cert_trust', 'diagnostics', 'SIU'),
        ('management_endpoint_pan_cert_trust', 'failover', 'SIU'),
        ('management_endpoint_pan_cert_trust', 'configuration', 'SIU'),
        ('management_endpoint_pan_cert_trust', 'worker_general', 'SIU'),
        ('management_endpoint_ssh_trust', 'service', 'SIU'),
        ('management_endpoint_ssh_trust', 'policy', 'SIU'),
        ('management_endpoint_ssh_trust', 'backup', 'SIU'),
        ('management_endpoint_ssh_trust', 'inventory', 'SIU'),
        ('management_endpoint_ssh_trust', 'diagnostics', 'SIU'),
        ('management_endpoint_ssh_trust', 'failover', 'SIU'),
        ('management_endpoint_ssh_trust', 'configuration', 'SIU'),
        ('management_endpoint_ssh_trust', 'worker_general', 'SIU'),
        ('module_runtime_control', 'service', 'S'),
        ('module_runtime_control', 'policy', 'S'),
        ('module_runtime_control', 'packet_analysis', 'S'),
        ('module_runtime_control', 'backup', 'S'),
        ('module_runtime_control', 'inventory', 'S'),
        ('module_runtime_control', 'diagnostics', 'S'),
        ('module_runtime_control', 'failover', 'S'),
        ('module_runtime_control', 'configuration', 'S'),
        ('module_runtime_control', 'compliance', 'S'),
        ('module_runtime_control', 'scheduler', 'S'),
        ('module_runtime_control', 'worker_general', 'S'),
        ('notification_routes', 'service', 'SIUD'),
        ('notification_routes', 'scheduler', 'S'),
        ('notification_routes', 'worker_general', 'S'),
        ('notification_settings', 'service', 'SU'),
        ('notification_settings', 'scheduler', 'S'),
        ('notification_settings', 'worker_general', 'S'),
        ('notification_state', 'service', 'SIUD'),
        ('notification_state', 'scheduler', 'SIU'),
        ('notification_state', 'worker_general', 'SIU'),
        ('packet_analysis_capture', 'service', 'SIUD'),
        ('packet_analysis_capture', 'packet_analysis', 'SUD'),
        ('packet_analysis_case', 'service', 'SIUD'),
        ('packet_analysis_case', 'packet_analysis', 'SUD'),
        ('packet_analysis_host_telemetry', 'service', 'SIUD'),
        ('packet_analysis_host_telemetry', 'packet_analysis', 'SUD'),
        ('packet_analysis_ledger', 'service', 'SI'),
        ('packet_analysis_ledger', 'packet_analysis', 'SI'),
        ('packet_analysis_run', 'service', 'SIUD'),
        ('packet_analysis_run', 'packet_analysis', 'SUD'),
        ('pan_config_sync', 'service', 'SIU'),
        ('pan_config_sync', 'failover', 'SU'),
        ('pan_config_sync', 'worker_general', 'SU'),
        ('pan_vsys_zone_set', 'service', 'SIUD'),
        ('pan_vsys_zone_set', 'inventory', 'SIUD'),
        ('pan_vsys_zone_set', 'worker_general', 'SIUD'),
        ('panorama_assignment', 'service', 'SD'),
        ('panorama_assignment', 'inventory', 'SIUD'),
        ('panorama_assignment', 'configuration', 'SIUD'),
        ('panorama_assignment', 'worker_general', 'SIUD'),
        ('path_validation', 'service', 'SI'),
        ('path_validation', 'diagnostics', 'SI'),
        ('path_validation', 'worker_general', 'SI'),
        ('path_validation_hop', 'service', 'S'),
        ('path_validation_hop', 'diagnostics', 'SI'),
        ('path_validation_hop', 'worker_general', 'SI'),
        ('policy_change_actor_link', 'service', 'SD'),
        ('policy_change_actor_link', 'policy', 'SIU'),
        ('policy_change_actor_link', 'worker_general', 'SIU'),
        ('policy_change_cursor', 'service', 'SD'),
        ('policy_change_cursor', 'policy', 'SIU'),
        ('policy_change_cursor', 'worker_general', 'SIU'),
        ('policy_change_evidence', 'service', 'SD'),
        ('policy_change_evidence', 'policy', 'SIU'),
        ('policy_change_evidence', 'worker_general', 'SIU'),
        ('policy_collection_domain', 'service', 'SIUD'),
        ('policy_collection_domain', 'policy', 'SIU'),
        ('policy_collection_domain', 'scheduler', 'S'),
        ('policy_collection_domain', 'worker_general', 'SIU'),
        ('policy_collection_request', 'service', 'SIUD'),
        ('policy_collection_request', 'policy', 'SU'),
        ('policy_collection_request', 'scheduler', 'SIU'),
        ('policy_collection_request', 'worker_general', 'SIU'),
        ('policy_offline_analysis', 'service', 'SIUD'),
        ('policy_revision', 'service', 'SD'),
        ('policy_revision', 'policy', 'SIU'),
        ('policy_revision', 'worker_general', 'SIU'),
        ('policy_rule_history', 'service', 'SD'),
        ('policy_rule_history', 'policy', 'S'),
        ('policy_rule_history', 'compliance', 'S'),
        ('policy_rule_history', 'worker_general', 'S'),
        ('policy_rule_history_baseline', 'service', 'SD'),
        ('policy_rule_history_baseline', 'policy', 'S'),
        ('policy_rule_history_baseline', 'compliance', 'S'),
        ('policy_rule_history_baseline', 'worker_general', 'S'),
        ('policy_snapshot', 'service', 'SD'),
        ('policy_snapshot', 'policy', 'SIU'),
        ('policy_snapshot', 'compliance', 'S'),
        ('policy_snapshot', 'worker_general', 'SIU'),
        ('policy_target_binding', 'service', 'SD'),
        ('policy_target_binding', 'policy', 'SIUD'),
        ('policy_target_binding', 'compliance', 'S'),
        ('policy_target_binding', 'worker_general', 'SIUD'),
        ('policy_unit_checkpoint', 'service', 'SD'),
        ('policy_unit_checkpoint', 'policy', 'SIUD'),
        ('policy_unit_checkpoint', 'worker_general', 'SIUD'),
        ('provenance_records', 'service', 'SD'),
        ('provenance_records', 'policy', 'SI'),
        ('provenance_records', 'packet_analysis', 'SI'),
        ('provenance_records', 'backup', 'SI'),
        ('provenance_records', 'inventory', 'SI'),
        ('provenance_records', 'diagnostics', 'SI'),
        ('provenance_records', 'failover', 'SI'),
        ('provenance_records', 'configuration', 'SI'),
        ('provenance_records', 'worker_general', 'SI'),
        ('pseudonym_registry', 'service', 'SI'),
        ('pseudonym_registry', 'packet_analysis', 'SI'),
        ('rbac_permissions', 'service', 'S'),
        ('rbac_role_permissions', 'service', 'SIUD'),
        ('rbac_roles', 'service', 'SIUD'),
        ('report_run', 'service', 'SIU'),
        ('role_bindings', 'service', 'SIUD'),
        ('root_identity', 'service', 'SI'),
        ('runtime_instance_heartbeat', 'service', 'S'),
        ('runtime_instance_heartbeat', 'policy', 'SIU'),
        ('runtime_instance_heartbeat', 'packet_analysis', 'SIU'),
        ('runtime_instance_heartbeat', 'backup', 'SIU'),
        ('runtime_instance_heartbeat', 'inventory', 'SIU'),
        ('runtime_instance_heartbeat', 'diagnostics', 'SIU'),
        ('runtime_instance_heartbeat', 'failover', 'SIU'),
        ('runtime_instance_heartbeat', 'configuration', 'SIU'),
        ('runtime_instance_heartbeat', 'compliance', 'SIU'),
        ('runtime_instance_heartbeat', 'scheduler', 'SIU'),
        ('runtime_instance_heartbeat', 'worker_general', 'SIU'),
        ('runtime_task_lease', 'service', 'SIUD'),
        ('runtime_task_lease', 'policy', 'SIUD'),
        ('runtime_task_lease', 'packet_analysis', 'SIUD'),
        ('runtime_task_lease', 'backup', 'SIUD'),
        ('runtime_task_lease', 'inventory', 'SIUD'),
        ('runtime_task_lease', 'diagnostics', 'SIUD'),
        ('runtime_task_lease', 'failover', 'SIUD'),
        ('runtime_task_lease', 'configuration', 'SIUD'),
        ('runtime_task_lease', 'compliance', 'SIUD'),
        ('runtime_task_lease', 'scheduler', 'SIUD'),
        ('runtime_task_lease', 'worker_general', 'SIUD'),
        ('saml_settings', 'service', 'SIU'),
        ('sanitize_job', 'service', 'SIU'),
        ('sanitize_ledger', 'service', 'SI'),
        ('sanitize_revert_job', 'service', 'SIU'),
        ('sanitize_vault_entry', 'service', 'SI'),
        ('scheduled_job_family', 'service', 'SU'),
        ('scheduled_job_family', 'scheduler', 'SIU'),
        ('scheduled_job_family', 'worker_general', 'SIU'),
        ('script', 'service', 'SIU'),
        ('script', 'scheduler', 'S'),
        ('script', 'worker_general', 'S'),
        ('script_version', 'service', 'SI'),
        ('script_version', 'scheduler', 'S'),
        ('script_version', 'worker_general', 'S'),
        ('secrets_metadata', 'service', 'SIUD'),
        ('secrets_metadata', 'policy', 'S'),
        ('secrets_metadata', 'backup', 'S'),
        ('secrets_metadata', 'inventory', 'S'),
        ('secrets_metadata', 'diagnostics', 'S'),
        ('secrets_metadata', 'failover', 'S'),
        ('secrets_metadata', 'configuration', 'S'),
        ('secrets_metadata', 'worker_general', 'S'),
        ('sessions', 'service', 'SIUD'),
        ('ssh_host_key_observation', 'service', 'SIUD'),
        ('ssh_host_key_observation', 'policy', 'SIU'),
        ('ssh_host_key_observation', 'backup', 'SIU'),
        ('ssh_host_key_observation', 'inventory', 'SIU'),
        ('ssh_host_key_observation', 'diagnostics', 'SIU'),
        ('ssh_host_key_observation', 'failover', 'SIU'),
        ('ssh_host_key_observation', 'configuration', 'SIU'),
        ('ssh_host_key_observation', 'worker_general', 'SIU'),
        ('ssh_trust_settings', 'service', 'SU'),
        ('ssh_trust_settings', 'policy', 'S'),
        ('ssh_trust_settings', 'backup', 'S'),
        ('ssh_trust_settings', 'inventory', 'S'),
        ('ssh_trust_settings', 'diagnostics', 'S'),
        ('ssh_trust_settings', 'failover', 'S'),
        ('ssh_trust_settings', 'configuration', 'S'),
        ('ssh_trust_settings', 'worker_general', 'S'),
        ('static_route_preview', 'service', 'SIU'),
        ('static_route_preview', 'diagnostics', 'SIU'),
        ('static_route_preview', 'worker_general', 'SIU'),
        ('support_bundle_item', 'service', 'SIU'),
        ('support_bundle_item', 'worker_general', 'SIU'),
        ('support_bundle_request', 'service', 'SIU'),
        ('support_bundle_request', 'worker_general', 'SIU'),
        ('support_bundle_upload', 'service', 'SIU'),
        ('support_bundle_upload_host_key', 'service', 'SIU'),
        ('system_log_chunk', 'service', 'SD'),
        ('system_log_chunk', 'diagnostics', 'SIUD'),
        ('system_log_chunk', 'worker_general', 'SIUD'),
        ('system_log_cursor', 'service', 'SD'),
        ('system_log_cursor', 'diagnostics', 'SIUD'),
        ('system_log_cursor', 'worker_general', 'SIUD'),
        ('system_log_file', 'service', 'SD'),
        ('system_log_file', 'diagnostics', 'SIUD'),
        ('system_log_file', 'worker_general', 'SIUD'),
        ('system_log_gap', 'service', 'SD'),
        ('system_log_gap', 'diagnostics', 'SIUD'),
        ('system_log_gap', 'worker_general', 'SIUD'),
        ('system_log_measurement', 'service', 'SD'),
        ('system_log_measurement', 'diagnostics', 'SIU'),
        ('system_log_measurement', 'worker_general', 'SIU'),
        ('system_log_run', 'service', 'SIUD'),
        ('system_log_run', 'diagnostics', 'SIUD'),
        ('system_log_run', 'scheduler', 'SI'),
        ('system_log_run', 'worker_general', 'SIUD'),
        ('system_log_settings', 'service', 'SIUD'),
        ('system_log_settings', 'diagnostics', 'S'),
        ('system_log_settings', 'scheduler', 'S'),
        ('system_log_settings', 'worker_general', 'S'),
        ('system_log_spike_directory', 'service', 'SD'),
        ('system_log_spike_directory', 'diagnostics', 'SIUD'),
        ('system_log_spike_directory', 'worker_general', 'SIUD'),
        ('topology_conflict', 'service', 'SID'),
        ('topology_link_override', 'service', 'SIU'),
        ('topology_snapshot', 'service', 'SID'),
        ('zone_matrix_access_log', 'service', 'SI'),
        ('zone_matrix_reference_confirmation', 'service', 'SI'),
        ('zone_matrix_revision', 'service', 'SI'),
        ('zone_matrix_version', 'service', 'SIU'),
        -- Step 0 read-only SECURITY DEFINER projection views
        ('v_latest_effective_running_config', 'service', 'S'),
        ('v_latest_effective_running_config', 'inventory', 'S'),
        ('v_latest_effective_running_config', 'diagnostics', 'S'),
        ('v_latest_effective_running_config', 'failover', 'S'),
        ('v_latest_effective_running_config', 'configuration', 'S'),
        ('v_latest_effective_running_config', 'compliance', 'S'),
        ('v_latest_effective_running_config', 'worker_general', 'S'),
        ('v_compliance_device_evidence', 'service', 'S'),
        ('v_compliance_device_evidence', 'compliance', 'S'),
        ('v_active_pseudonym_lookup', 'service', 'S'),
        ('v_active_pseudonym_lookup', 'packet_analysis', 'S')
        ) AS g(table_name, role_suffix, privs)
    LOOP
        CONTINUE WHEN NOT (v_rec.role_suffix = ANY (v_present));
        v_privs := concat_ws(', ',
            CASE WHEN v_rec.privs LIKE '%S%' THEN 'SELECT' END,
            CASE WHEN v_rec.privs LIKE '%I%' THEN 'INSERT' END,
            CASE WHEN v_rec.privs LIKE '%U%' THEN 'UPDATE' END,
            CASE WHEN v_rec.privs LIKE '%D%' THEN 'DELETE' END);
        EXECUTE format('GRANT %s ON TABLE public.%I TO %I', v_privs, v_rec.table_name,
            p_role_prefix || v_rec.role_suffix);
    END LOOP;

    -- Column-level UPDATE:
    -- 1. module_runtime_control: each worker updates its own heartbeat / drain / pool metrics.
    -- 2. devices: SSH-contacting workers update ssh_identity_unverified (SshHostKeyPolicyStore, design §6.2 F2),
    --    and configuration updates observed facts and HA pair claim columns without table-level UPDATE on devices.
    FOR v_rec IN
        SELECT g.role_suffix, g.table_name, g.column_list
        FROM (VALUES
        ('policy', 'module_runtime_control', 'admission_grants,admission_wait_ms,drain_ack_at,drain_ack_generation,drain_generation,drain_requested,owner_heartbeat_at,owner_instance,pool_active,pool_idle,pool_pending,pool_timeouts,pool_wait_nanos'),
        ('packet_analysis', 'module_runtime_control', 'admission_grants,admission_wait_ms,drain_ack_at,drain_ack_generation,drain_generation,drain_requested,owner_heartbeat_at,owner_instance,pool_active,pool_idle,pool_pending,pool_timeouts,pool_wait_nanos'),
        ('backup', 'module_runtime_control', 'admission_grants,admission_wait_ms,drain_ack_at,drain_ack_generation,drain_generation,drain_requested,owner_heartbeat_at,owner_instance,pool_active,pool_idle,pool_pending,pool_timeouts,pool_wait_nanos'),
        ('inventory', 'module_runtime_control', 'admission_grants,admission_wait_ms,drain_ack_at,drain_ack_generation,drain_generation,drain_requested,owner_heartbeat_at,owner_instance,pool_active,pool_idle,pool_pending,pool_timeouts,pool_wait_nanos'),
        ('diagnostics', 'module_runtime_control', 'admission_grants,admission_wait_ms,drain_ack_at,drain_ack_generation,drain_generation,drain_requested,owner_heartbeat_at,owner_instance,pool_active,pool_idle,pool_pending,pool_timeouts,pool_wait_nanos'),
        ('failover', 'module_runtime_control', 'admission_grants,admission_wait_ms,drain_ack_at,drain_ack_generation,drain_generation,drain_requested,owner_heartbeat_at,owner_instance,pool_active,pool_idle,pool_pending,pool_timeouts,pool_wait_nanos'),
        ('configuration', 'module_runtime_control', 'admission_grants,admission_wait_ms,drain_ack_at,drain_ack_generation,drain_generation,drain_requested,owner_heartbeat_at,owner_instance,pool_active,pool_idle,pool_pending,pool_timeouts,pool_wait_nanos'),
        ('compliance', 'module_runtime_control', 'admission_grants,admission_wait_ms,drain_ack_at,drain_ack_generation,drain_generation,drain_requested,owner_heartbeat_at,owner_instance,pool_active,pool_idle,pool_pending,pool_timeouts,pool_wait_nanos'),
        ('scheduler', 'module_runtime_control', 'admission_grants,admission_wait_ms,drain_ack_at,drain_ack_generation,drain_generation,drain_requested,owner_heartbeat_at,owner_instance,pool_active,pool_idle,pool_pending,pool_timeouts,pool_wait_nanos'),
        ('worker_general', 'module_runtime_control', 'admission_grants,admission_wait_ms,drain_ack_at,drain_ack_generation,drain_generation,drain_requested,owner_heartbeat_at,owner_instance,pool_active,pool_idle,pool_pending,pool_timeouts,pool_wait_nanos'),
        ('policy', 'devices', 'ssh_identity_unverified'),
        ('backup', 'devices', 'ssh_identity_unverified'),
        ('diagnostics', 'devices', 'ssh_identity_unverified'),
        ('failover', 'devices', 'ssh_identity_unverified'),
        ('configuration', 'devices', 'ssh_identity_unverified,observed_hostname,observed_model,observed_software_version,observed_ha_role,ha_pair_claim,ha_unit_token,cluster_member_ref')
        ) AS g(role_suffix, table_name, column_list)
    LOOP
        CONTINUE WHEN NOT (v_rec.role_suffix = ANY (v_present));
        EXECUTE format('GRANT UPDATE (%s) ON TABLE public.%I TO %I',
            (SELECT string_agg(format('%I', c), ', ') FROM unnest(string_to_array(v_rec.column_list, ',')) AS c),
            v_rec.table_name, p_role_prefix || v_rec.role_suffix);
    END LOOP;

    -- Sequences (only those a non-identity INSERT or nextval() needs; identity columns need no grant).
    FOR v_rec IN
        SELECT g.role_suffix, g.sequence_name
        FROM (VALUES
        ('service', 'endpoint_permit_epoch'),
        ('policy', 'endpoint_permit_epoch'),
        ('backup', 'endpoint_permit_epoch'),
        ('inventory', 'endpoint_permit_epoch'),
        ('diagnostics', 'endpoint_permit_epoch'),
        ('failover', 'endpoint_permit_epoch'),
        ('configuration', 'endpoint_permit_epoch'),
        ('worker_general', 'endpoint_permit_epoch'),
        ('service', 'failover_schedule_ledger_id_seq'),
        ('failover', 'failover_schedule_ledger_id_seq'),
        ('scheduler', 'failover_schedule_ledger_id_seq'),
        ('worker_general', 'failover_schedule_ledger_id_seq'),
        ('service', 'local_break_glass_event_event_id_seq'),
        ('service', 'local_password_history_history_id_seq')
        ) AS g(role_suffix, sequence_name)
    LOOP
        CONTINUE WHEN NOT (v_rec.role_suffix = ANY (v_present));
        EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE public.%I TO %I', v_rec.sequence_name,
            p_role_prefix || v_rec.role_suffix);
    END LOOP;

    -- Functions the module paths call directly (all others stay at their existing, unchanged privileges).
    FOR v_rec IN
        SELECT g.role_suffix, g.function_signature
        FROM (VALUES
        ('service', 'audit_machine_session_event(text, text)'),
        ('service', 'audit_report_download(uuid)'),
        ('service', 'audit_support_bundle_download(uuid)'),
        ('service', 'ui2_failover_lease_settled(text)'),
        ('service', 'ui2_release_settled_failover_leases(text)'),
        ('service', 'ui2_release_failover_incident(text, text, text, text, text)'),
        ('service', 'ui2_config_sync_lease_settled(text)'),
        ('service', 'ui2_release_settled_config_sync_leases()'),
        ('failover', 'ui2_failover_lease_settled(text)'),
        ('failover', 'ui2_release_settled_failover_leases(text)'),
        ('failover', 'ui2_release_failover_incident(text, text, text, text, text)'),
        ('failover', 'ui2_config_sync_lease_settled(text)'),
        ('failover', 'ui2_release_settled_config_sync_leases()'),
        ('worker_general', 'ui2_failover_lease_settled(text)'),
        ('worker_general', 'ui2_release_settled_failover_leases(text)'),
        ('worker_general', 'ui2_release_failover_incident(text, text, text, text, text)'),
        ('worker_general', 'ui2_config_sync_lease_settled(text)'),
        ('worker_general', 'ui2_release_settled_config_sync_leases()'),
        ('service', 'ui2_release_orphan_quarantines()'),
        ('service', 'ui2_quarantine_owner_gone(text)'),
        ('policy', 'ui2_release_orphan_quarantines()'),
        ('policy', 'ui2_quarantine_owner_gone(text)'),
        ('packet_analysis', 'ui2_release_orphan_quarantines()'),
        ('packet_analysis', 'ui2_quarantine_owner_gone(text)'),
        ('backup', 'ui2_release_orphan_quarantines()'),
        ('backup', 'ui2_quarantine_owner_gone(text)'),
        ('inventory', 'ui2_release_orphan_quarantines()'),
        ('inventory', 'ui2_quarantine_owner_gone(text)'),
        ('diagnostics', 'ui2_release_orphan_quarantines()'),
        ('diagnostics', 'ui2_quarantine_owner_gone(text)'),
        ('failover', 'ui2_release_orphan_quarantines()'),
        ('failover', 'ui2_quarantine_owner_gone(text)'),
        ('configuration', 'ui2_release_orphan_quarantines()'),
        ('configuration', 'ui2_quarantine_owner_gone(text)'),
        ('compliance', 'ui2_release_orphan_quarantines()'),
        ('compliance', 'ui2_quarantine_owner_gone(text)'),
        ('scheduler', 'ui2_release_orphan_quarantines()'),
        ('scheduler', 'ui2_quarantine_owner_gone(text)'),
        ('worker_general', 'ui2_release_orphan_quarantines()'),
        ('worker_general', 'ui2_quarantine_owner_gone(text)'),
        ('service', 'ui2_record_software_version(text, text, text, text)'),
        ('backup', 'ui2_record_software_version(text, text, text, text)'),
        ('inventory', 'ui2_record_software_version(text, text, text, text)'),
        ('configuration', 'ui2_record_software_version(text, text, text, text)'),
        ('worker_general', 'ui2_record_software_version(text, text, text, text)')
        ) AS g(role_suffix, function_signature)
    LOOP
        CONTINUE WHEN NOT (v_rec.role_suffix = ANY (v_present));
        EXECUTE format('GRANT EXECUTE ON FUNCTION public.%s TO %I', v_rec.function_signature,
            p_role_prefix || v_rec.role_suffix);
    END LOOP;

    -- Step 0 helper functions granted to every present module role.
    FOREACH v_suffix IN ARRAY v_present LOOP
        v_role := p_role_prefix || v_suffix;
        EXECUTE format('GRANT EXECUTE ON FUNCTION public.ui2_current_role_module() TO %I', v_role);
        EXECUTE format('GRANT EXECUTE ON FUNCTION public.ui2_schema_version_check(text) TO %I', v_role);
        EXECUTE format('GRANT EXECUTE ON FUNCTION public.ui2_capability_owner_module(text) TO %I', v_role);
        EXECUTE format('GRANT EXECUTE ON FUNCTION public.ui2_next_task_lease_epoch() TO %I', v_role);
        EXECUTE format('GRANT EXECUTE ON FUNCTION public.ui2_job_owner_valid(text, bigint) TO %I', v_role);
    END LOOP;

    RETURN v_missing;
END
$fn$;

REVOKE ALL ON FUNCTION ui2_apply_module_role_grants(TEXT) FROM PUBLIC;

COMMENT ON FUNCTION ui2_apply_module_role_grants(TEXT) IS
    'Idempotently grants the per-module least-privilege matrix (design DATABASE_ROLES steps 0 & 1) to the NOLOGIN module '
    'roles that exist; never revokes; returns the number of module roles not found. Run by ui2_migrate only.';

SELECT ui2_apply_module_role_grants();

-- ---------------------------------------------------------------------
-- Catch-up for functions added by migrations that landed between this design and its ship (2026-11-20):
-- V255/V256 (VSX chassis sequence guard), V258 (Script Execution audit function), V260 (actor authority).
-- Every trigger function in public becomes SECURITY DEFINER with a pinned search_path and no PUBLIC execute;
-- every SECURITY DEFINER function gets the pinned search_path. The Script Execution broker role keeps the
-- one lease helper its heartbeat calls.
-- ---------------------------------------------------------------------
DO $catchup$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT p.oid::regprocedure::text AS sig, p.prosecdef,
               (p.prorettype = 'trigger'::regtype) AS is_trigger,
               COALESCE(array_to_string(p.proconfig, ','), '') AS cfg
          FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'public' AND p.prokind = 'f'
    LOOP
        IF r.is_trigger AND NOT r.prosecdef THEN
            EXECUTE format('ALTER FUNCTION %s SECURITY DEFINER SET search_path = pg_catalog, public', r.sig);
            EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', r.sig);
        ELSIF r.prosecdef AND position('search_path=pg_catalog, public' IN r.cfg) = 0 THEN
            EXECUTE format('ALTER FUNCTION %s SET search_path = pg_catalog, public', r.sig);
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ui2_script_runner') THEN
        GRANT EXECUTE ON FUNCTION public.ui2_job_owner_valid(text, bigint) TO ui2_script_runner;
    END IF;
END
$catchup$;
