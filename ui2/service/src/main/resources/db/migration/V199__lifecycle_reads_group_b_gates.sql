-- V199 -- Lifecycle reads, group B: seven read-only gate rows for seven approved measure-first reads.
-- PO approval 2026-11-10 (chat), verbatim, in two answers:
--   "d2, onayla d3,onayla onayla"   (D2: the Palo Alto reads, first on one PAN-OS 11.1 firewall; D3: the FortiGate contract read first)
--   "Evet, dördü de"                (FortiGate autoupdate versions, FortiManager fmupdate dbcontract, ProxySG show licenses, Infoblox upgradestatus)
-- Authority for every literal and field below: docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md. Grade C items of
-- the proposal are not approved and are not seeded here.
-- All rows: CLASS_0 read, no retry, sent inside the session or client the inventory run already holds (no new credential,
-- connection or schedule). The worker sends them only behind UI2_LIFECYCLE_READS / UI2_LIFECYCLE_READ_DEVICES (off by default);
-- the Palo Alto rows only to a firewall below PAN-OS 12; only a masked shape is logged and nothing from these reads is
-- stored. Seed data only; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V199_lifecycle_reads_group_b_gates', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('pan_lifecycle_license_info', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<request><license><info/></license></request>',
     'read', 'SIGNED_OFF', 60, 'none', 'once per firewall per inventory run',
     'the one key-generation session per run',
     'an error reply or an unexpected shape: license check UNKNOWN',
     'device serial and authcode in the reply: parsed past, never stored or logged',
     '["outcome", "reply_status", "entry_count", "element_names"]'::jsonb,
     'docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md (PO chat approval 2026-11-10)'),
    ('pan_lifecycle_support_info', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<request><support><info/></support></request>',
     'read', 'SIGNED_OFF', 60, 'none', 'once per firewall per inventory run',
     'the one key-generation session per run',
     'an error reply or an unexpected shape: support check UNKNOWN',
     'account, contact and link fields: never stored or logged',
     '["outcome", "reply_status", "element_names"]'::jsonb,
     'docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md (PO chat approval 2026-11-10)'),
    ('fgt_lifecycle_update_info_contract', 'fortinet', 'fortigate', 'top_or_global', 'SSH_EXEC',
     'diagnose test update info contract',
     'read', 'SIGNED_OFF', 60, 'none', 'once per device per inventory run',
     'one session per device per run',
     'refused (administrator profile without diagnostics) or unparsed: contract check UNSUPPORTED or UNKNOWN',
     'account id, company, user id, serial if printed: parsed past, never stored or logged',
     '["outcome", "line_count", "masked_shape"]'::jsonb,
     'docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md (PO chat approval 2026-11-10)'),
    ('fgt_lifecycle_autoupdate_versions', 'fortinet', 'fortigate', 'top_or_global', 'SSH_EXEC',
     'diagnose autoupdate versions',
     'read', 'SIGNED_OFF', 60, 'none', 'once per device per inventory run',
     'one session per device per run',
     'refused (administrator profile without diagnostics) or unparsed: contract check UNSUPPORTED or UNKNOWN',
     'none expected',
     '["outcome", "line_count", "masked_shape"]'::jsonb,
     'docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md (PO chat approval 2026-11-10)'),
    ('fmg_lifecycle_fmupdate_dbcontract', 'fortinet', 'fortimanager', 'cli', 'SSH_EXEC',
     'diagnose fmupdate dbcontract',
     'read', 'SIGNED_OFF', 60, 'none', 'once per inventory',
     'one SSH session per inventory',
     'refused or unreadable: UNKNOWN, logged',
     'device serials, AccountID, Company, UserID, the raw contract data block: parsed past, never stored or logged',
     '["outcome", "line_count", "masked_shape"]'::jsonb,
     'docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md (PO chat approval 2026-11-10)'),
    ('bluecoat_mc_command_show_licenses', 'bluecoat', 'bluecoat_management_center', 'config_mode_via_mc', 'HTTPS',
     'PUT /api/devices/{uuid}/command "show licenses"',
     'read', 'SIGNED_OFF', 120, 'none', 'at most UI2_LIFECYCLE_MC_LICENSE_MAX ProxySGs per inventory run',
     'one client per run',
     'status other than success, or unparsed text: license check UNKNOWN',
     'serial number if printed: parsed past, never stored or logged',
     '["outcome", "http_status", "line_count", "masked_shape"]'::jsonb,
     'docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md (PO chat approval 2026-11-10)'),
    ('infoblox_upgradestatus_grid', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS',
     'GET /wapi/v<ver>/upgradestatus?type=GRID',
     'read', 'SIGNED_OFF', 60, 'none', 'once per inventory',
     'one client per run',
     'failed object: NIOS version stays UNKNOWN',
     'member names if present: never stored or logged',
     '["count", "field_names"]'::jsonb,
     'docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md (PO chat approval 2026-11-10)')
ON CONFLICT (gate_id) DO NOTHING;
