-- V192 -- Check Point NAT rulebase read by package UID after VENDOR_NAME_NOT_UNIQUE.
-- PO approval 2026-11-05 evening (chat: "Abi hepsini sıraya koy ve arka arkaya yap";
-- docs/design/PO_DECISION_RECORD_2026_11_05_EVENING_BATCH.md) of this exact CLASS_0 literal:
--   mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PACKAGE_UID>' limit 500 offset '<N>' details-level standard use-object-dictionary true
--
-- Scope: issued only after the package's by-name NAT read fails with VENDOR_NAME_NOT_UNIQUE and the package UID is known
-- from show-packages; never for another failure code. Paging, timeout, retry and frequency are those of the by-name NAT
-- read (cp_policy_nat_rulebase) it replaces, in the same session. Content is mapped like a by-name read; a vendor error,
-- transport failure or unexpected shape leaves the NAT section UNKNOWN (VENDOR_NAME_NOT_UNIQUE); no other fallback.
-- Supersedes in use the V191 probe gate (cp_policy_nat_rulebase_uid_probe), whose row is left as is.
-- Seed data only; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V192_cp_nat_rulebase_by_uid_gate', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_policy_nat_rulebase_by_uid', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC',
     'mgmt_cli -r true -d ''<DOMAIN>'' -f json show-nat-rulebase package ''<PACKAGE_UID>'' limit 500 offset ''<N>'' details-level standard use-object-dictionary true',
     'read', 'SIGNED_OFF', 60, 'none',
     'only packages whose NAT read by name failed with VENDOR_NAME_NOT_UNIQUE; same frequency as the by-name NAT read (manual admin request; automatic at most once per domain per 6 hours)',
     'one interactive SSH session per job; same session as the failed by-name NAT read; serial',
     'vendor error, transport failure or unexpected shape -> NAT section stays UNKNOWN (VENDOR_NAME_NOT_UNIQUE); never any other fallback',
     'sensitive policy data; parse in memory',
     '["outcome", "packageCount", "ruleCount"]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_11_05_EVENING_BATCH.md (PO chat approval 2026-11-05 evening)')
ON CONFLICT (gate_id) DO NOTHING;
