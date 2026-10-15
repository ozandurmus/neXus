-- V153 -- audit classification for the columns and tables added after V5.
--
-- The PostgreSQL integration suite had not run since V5, so later migrations
-- added columns to audited tables without classifying them (contract
-- UI2_0_B1_02A section 4: every column of an audited table is either redacted
-- here or audited in full by AuditPresentationAllowlist) and added three
-- operator-configuration tables without an audit trigger.

-- ---------------------------------------------------------------------
-- 1. fn_audit_redact digests the value's UTF-8 bytes.
-- V5 cast the value's text to bytea, which parses backslash escapes: a
-- redacted value containing a backslash either failed the audited write
-- ("invalid input syntax for type bytea") or was digested from altered
-- bytes. convert_to() yields exactly the bytes the cast produced for every
-- value without a backslash, so existing digests are unchanged. Needed
-- before section 2 redacts free-text columns.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_audit_redact(p_table TEXT, p_row JSONB)
    RETURNS JSONB AS $$
DECLARE
    v_col TEXT;
    v_out JSONB := p_row;
BEGIN
    IF p_row IS NULL THEN
        RETURN NULL;
    END IF;

    FOR v_col IN
        SELECT column_name FROM audit_redaction_policy WHERE table_name = p_table
    LOOP
        IF v_out ? v_col AND v_out -> v_col <> 'null'::jsonb THEN
            v_out := jsonb_set(v_out, ARRAY[v_col],
                to_jsonb('sha256:' || encode(sha256(convert_to(v_out ->> v_col, 'UTF8')), 'hex')));
        END IF;
    END LOOP;

    RETURN v_out;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

-- ---------------------------------------------------------------------
-- 2. Redaction policy for the later columns. Tier 1: secret material.
-- Tier 2: operational identity, location, device-derived content, free
-- text. For tables audited by a narrow capture function (credentials,
-- local_credentials, the endpoint trust tables, ssh_host_key_observation,
-- notification_*, policy_offline_analysis) these rows change nothing that
-- is stored; they make the audit screen withhold the column. A primary key
-- is never redacted (contract Amendment A-1: row_pk is written from the
-- unredacted row), so configuration_artefact.artefact_ref and
-- discovery_acknowledgement.discovery_match_key stay audited in full.
-- ---------------------------------------------------------------------
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('backup_artefact', 'hostname_fingerprint', 2, 'digest of a device hostname; low-entropy input'),
    ('backup_artefact', 'recovery_volume_path', 2, 'server-side artefact storage location'),
    ('backup_artefact', 'virtual_system_ref', 2, 'device virtual-system reference'),
    ('backup_artefact_deletion_request', 'reason', 2, 'operator free text'),
    ('backup_artefact_retrieval', 'destination_path', 2, 'operator retrieval destination location'),
    ('backup_artefact_retrieval', 'reason', 2, 'operator free text'),
    ('backup_endpoint_ineligibility', 'reason', 2, 'free text of unbounded shape'),
    ('configuration_notification', 'override_paths', 2, 'device-derived configuration element paths'),
    ('configuration_notification', 'summary', 2, 'device-derived configuration summary'),
    ('configuration_run_deviation_entry', 'context', 2, 'device virtual-context name'),
    ('credentials', 'display_name', 2, 'operator-chosen label of unbounded shape'),
    ('credentials', 'encrypted_passphrase', 1, 'credential ciphertext; never in the narrow capture'),
    ('credentials', 'encrypted_secret', 1, 'credential ciphertext; never in the narrow capture'),
    ('credentials', 'username', 2, 'device login principal'),
    ('device_configuration_index', 'context', 2, 'device virtual-context name'),
    ('device_configuration_override', 'context', 2, 'device virtual-context name'),
    ('device_configuration_override', 'element_path', 2, 'device-derived configuration element path'),
    ('device_configuration_override', 'panorama_source', 2, 'management-plane device-group/template name'),
    ('device_configuration_run', 'artefact_ref', 2, 'server-only artefact location'),
    ('device_configuration_run', 'sanitized_text', 2, 'device-derived configuration content'),
    ('device_interface', 'context', 2, 'device virtual-context name'),
    ('device_interface', 'mac_address', 2, 'device hardware address'),
    ('device_interface', 'name', 2, 'device interface name'),
    ('device_interface', 'parent', 2, 'device interface name'),
    ('device_interface', 'zone', 2, 'device security-zone name'),
    ('device_interface_address', 'address', 2, 'device network address'),
    ('device_inventory_ha', 'context', 2, 'device virtual-context name'),
    ('device_inventory_run', 'virtual_systems', 2, 'device virtual-system names'),
    ('device_onboarding', 'reason', 2, 'free text of unbounded shape'),
    ('device_route', 'context', 2, 'device virtual-context name'),
    ('device_route', 'destination', 2, 'device network address'),
    ('device_route', 'interface', 2, 'device interface name'),
    ('device_route', 'next_hop', 2, 'device network address'),
    ('device_route', 'route_table', 2, 'device routing-table name'),
    ('devices', 'cluster_member_ref', 2, 'cluster member identity pair'),
    ('devices', 'discovery_match_key', 2, 'vendor stable device identifier'),
    ('devices', 'ha_pair_claim', 2, 'claimed HA peer identity'),
    ('devices', 'ha_unit_token', 2, 'digest of a device serial or address; low-entropy input'),
    ('devices', 'identity_mismatch_presented_primary', 2, 'device identity (serial-class) value'),
    ('devices', 'identity_mismatch_presented_secondary', 2, 'device identity (serial-class) value'),
    ('devices', 'observed_hostname', 2, 'device-presented hostname'),
    ('devices', 'peer_follow_reason', 2, 'free text of unbounded shape'),
    ('devices', 'recorded_identity_primary', 2, 'device identity (serial-class) value'),
    ('devices', 'recorded_identity_secondary', 2, 'device identity (serial-class) value'),
    ('devices', 'virtual_system_ref', 2, 'device virtual-system reference'),
    ('discovery_acknowledgement', 'reason', 2, 'operator free text'),
    ('failover_approval', 'reason', 2, 'operator free text'),
    ('failover_run', 'message', 2, 'free text of unbounded shape'),
    ('https_endpoint_cert_trust', 'authorized_by', 2, 'authorizing actor; never in the narrow capture'),
    ('https_endpoint_cert_trust', 'fingerprint_sha256', 2, 'endpoint certificate identity'),
    ('https_endpoint_cert_trust', 'issuer_cn', 2, 'endpoint certificate identity'),
    ('https_endpoint_cert_trust', 'management_address', 2, 'management-path operational identity'),
    ('https_endpoint_cert_trust', 'subject_cn', 2, 'endpoint certificate identity'),
    ('jobs', 'diagnostic_masked_output', 2, 'device-derived diagnostic content'),
    ('local_credentials', 'local_identity_name', 2, 'local login principal; never in the narrow capture'),
    ('local_credentials', 'salt', 1, 'password salt; never in the narrow capture'),
    ('local_credentials', 'verifier', 1, 'password verifier; never in the narrow capture'),
    ('management_endpoint_pan_cert_trust', 'authorized_by', 2, 'authorizing actor; never in the narrow capture'),
    ('management_endpoint_pan_cert_trust', 'fingerprint_sha256', 2, 'endpoint certificate identity'),
    ('management_endpoint_pan_cert_trust', 'management_address', 2, 'management-path operational identity'),
    ('management_endpoint_ssh_trust', 'authorized_by', 2, 'authorizing actor; never in the narrow capture'),
    ('management_endpoint_ssh_trust', 'fingerprint_sha256', 2, 'host-key identity'),
    ('management_endpoint_ssh_trust', 'management_address', 2, 'management-path operational identity'),
    ('notification_routes', 'last_error', 2, 'free text of unbounded shape'),
    ('notification_routes', 'recipients', 2, 'recipient mail addresses'),
    ('notification_settings', 'smtp_credential_ref', 2, 'credential location'),
    ('notification_settings', 'smtp_from', 2, 'mail address'),
    ('notification_settings', 'smtp_host', 2, 'mail relay network address'),
    ('notification_settings', 'smtp_to', 2, 'mail addresses'),
    ('notification_settings', 'syslog_host', 2, 'collector network address'),
    ('panorama_assignment', 'device_groups', 2, 'management-plane device-group names'),
    ('panorama_assignment', 'device_serial', 2, 'device serial number'),
    ('panorama_assignment', 'template_stack', 2, 'management-plane template-stack name'),
    ('panorama_assignment', 'templates', 2, 'management-plane template names'),
    ('policy_offline_analysis', 'aggregates', 2, 'policy-derived content'),
    ('policy_offline_analysis', 'file_name', 2, 'operator-chosen file name'),
    ('rbac_roles', 'description', 2, 'operator free text'),
    ('ssh_host_key_observation', 'expected_fingerprint', 2, 'host-key identity'),
    ('ssh_host_key_observation', 'management_address', 2, 'management-path operational identity'),
    ('ssh_host_key_observation', 'presented_fingerprint', 2, 'host-key identity')
ON CONFLICT (table_name, column_name) DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. Custom RBAC roles (V27) are operator configuration: audit them like
-- role_bindings. Their only writer (RbacRoleController) sets no audit
-- context and has no action mapping, so the gate chain already refuses it
-- (ACTION_MAPPING_REQUIRED); should it be mapped, its writes fail closed
-- with audit_context_missing until they go through the audited boundary.
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_audit_rbac_roles
    AFTER INSERT OR UPDATE OR DELETE ON rbac_roles
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('id');
CREATE TRIGGER trg_audit_rbac_permissions
    AFTER INSERT OR UPDATE OR DELETE ON rbac_permissions
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('id');
CREATE TRIGGER trg_audit_rbac_role_permissions
    AFTER INSERT OR UPDATE OR DELETE ON rbac_role_permissions
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('role_id');
