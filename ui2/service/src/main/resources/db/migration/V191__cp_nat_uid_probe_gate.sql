-- V191 -- Check Point NAT rulebase one-off probe by package UID on VENDOR_NAME_NOT_UNIQUE.
-- PO approval 2026-11-05 (chat: "NAT için komut ok deneyebilirsin";
-- docs/design/PO_DECISION_RECORD_2026_11_05_CP_NAT_UID_PROBE.md, RATIFIED):
--   mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PACKAGE_UID>' limit 1 offset 0 details-level standard use-object-dictionary true
--
-- Scope: issued only after a package's by-name NAT read fails with VENDOR_NAME_NOT_UNIQUE and its UID is known from
-- show-packages; at most once per package per collection run in the same interactive SSH session; never retried or polled.
-- Raw-evidence law: rule and object content is discarded in memory; the outcome is recorded as an enum only
-- (UID_ACCEPTED, UID_REJECTED, NOT_EVALUABLE) and the package's NAT section stays UNKNOWN.
-- Seed data only; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V191_cp_nat_uid_probe_gate', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_policy_nat_rulebase_uid_probe', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC',
     'mgmt_cli -r true -d ''<DOMAIN>'' -f json show-nat-rulebase package ''<PACKAGE_UID>'' limit 1 offset 0 details-level standard use-object-dictionary true',
     'read', 'SIGNED_OFF', 60, 'none',
     'only packages whose NAT read by name failed with VENDOR_NAME_NOT_UNIQUE; once per package per collection run; never polled',
     'one interactive SSH session per job; same session as the failed NAT read; serial; no retry',
     'vendor error -> UID_REJECTED; transport error or unexpected shape -> NOT_EVALUABLE; NAT section stays UNKNOWN (VENDOR_NAME_NOT_UNIQUE)',
     'rule content discarded in memory (raw-evidence law); enum outcome only (UID_ACCEPTED, UID_REJECTED, NOT_EVALUABLE)',
     '["outcome"]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_11_05_CP_NAT_UID_PROBE.md (RATIFIED; PO chat approval 2026-11-05: "NAT için komut ok deneyebilirsin")')
ON CONFLICT (gate_id) DO NOTHING;
