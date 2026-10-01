-- PO-approved management-only policy reads; V115 belongs to the policy-viewer base lane.
SELECT set_config('app.actor_fingerprint', 'migration:V116_cp_policy_reads', true);
SELECT set_config('app.action_id', 'gate_registry_insert_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields, source_document_pointer)
VALUES ('cp_policy_packages', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'mgmt_cli -r true -d ''<DOMAIN>'' -f json show-packages limit 500 details-level full', 'read', 'SIGNED_OFF', 60, 'none', 'manual admin request; automatic at most once per domain per 6 hours', 'one interactive SSH session per job; serial', 'UNKNOWN', 'sensitive policy data; parse in memory', '["outcome", "packageCount", "ruleCount"]', 'docs/design/PO_DECISION_RECORD_2026_10_01_CP_POLICY_READS.md');
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields, source_document_pointer)
VALUES ('cp_policy_access_rulebase', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'mgmt_cli -r true -d ''<DOMAIN>'' -f json show-access-rulebase name ''<LAYER>'' limit 500 offset ''<N>'' details-level full use-object-dictionary true', 'read', 'SIGNED_OFF', 60, 'none', 'manual admin request; automatic at most once per domain per 6 hours', 'one interactive SSH session per job; serial', 'UNKNOWN', 'sensitive policy data; parse in memory', '["outcome", "packageCount", "ruleCount"]', 'docs/design/PO_DECISION_RECORD_2026_10_01_CP_POLICY_READS.md');
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields, source_document_pointer)
VALUES ('cp_policy_nat_rulebase', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'mgmt_cli -r true -d ''<DOMAIN>'' -f json show-nat-rulebase package ''<PKG>'' limit 500 offset ''<N>'' details-level standard use-object-dictionary true', 'read', 'SIGNED_OFF', 60, 'none', 'manual admin request; automatic at most once per domain per 6 hours', 'one interactive SSH session per job; serial', 'UNKNOWN', 'sensitive policy data; parse in memory', '["outcome", "packageCount", "ruleCount"]', 'docs/design/PO_DECISION_RECORD_2026_10_01_CP_POLICY_READS.md');

-- Only opaque references and control-plane timestamps; jobs remain the audited admission path.
CREATE TABLE policy_collection_request (
    job_id TEXT PRIMARY KEY REFERENCES jobs(job_id),
    source_id TEXT NOT NULL REFERENCES devices(device_id),
    domain_ref TEXT NOT NULL DEFAULT '',
    automatic BOOLEAN NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON policy_collection_request TO ui2_app;
CREATE TABLE policy_collection_domain (
    source_id TEXT NOT NULL REFERENCES devices(device_id),
    domain_ref TEXT NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source_id, domain_ref)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON policy_collection_domain TO ui2_app;
