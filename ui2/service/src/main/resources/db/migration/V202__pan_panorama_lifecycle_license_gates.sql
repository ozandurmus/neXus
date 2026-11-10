-- V202 -- Palo Alto license and support reads for Panorama: two read-only gate rows.
-- PO approval 2026-11-10 (PO session, relayed). The PO was asked:
--   "PAN-OS 12 firewall'larda ve Panorama'da da lisans okuması açılsın mı?"
-- and answered:
--   "Evet, aç"
-- The two requests are the literals of the firewall rows pan_lifecycle_license_info / pan_lifecycle_support_info (V199), sent
-- as type=op to the Panorama itself: <request><license><info/></license></request> and
-- <request><support><info/></support></request>. A firewall on PAN-OS 12 or later uses the existing firewall rows; only the
-- worker's version guard is widened for it (no new row). Authority: docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md,
-- amendment C-2026-11-10. CLASS_0 read, no retry, sent with the key the inventory run already holds, only behind
-- UI2_LIFECYCLE_READS / UI2_LIFECYCLE_READ_DEVICES and UI2_LIFECYCLE_PAN_EXTENDED_DEVICES (off by default), measured on one
-- device first. Serial, authcode, account and contact fields are never stored or logged. Seed data only; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V202_pan_panorama_lifecycle_license_gates', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('pan_panorama_lifecycle_license_info', 'palo_alto', 'panorama', 'not_applicable', 'PAN_XML_API',
     '<request><license><info/></license></request>',
     'read', 'SIGNED_OFF', 60, 'none', 'once per inventory',
     'the one key-generation session per run',
     'an error reply or an unexpected shape: license check UNKNOWN',
     'device serial and authcode in the reply: parsed past, never stored or logged',
     '["outcome", "reply_status", "entry_count", "element_names"]'::jsonb,
     'docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md amendment C-2026-11-10 (PO chat approval 2026-11-10)'),
    ('pan_panorama_lifecycle_support_info', 'palo_alto', 'panorama', 'not_applicable', 'PAN_XML_API',
     '<request><support><info/></support></request>',
     'read', 'SIGNED_OFF', 60, 'none', 'once per inventory',
     'the one key-generation session per run',
     'an error reply or an unexpected shape: support check UNKNOWN',
     'account, contact and link fields: never stored or logged',
     '["outcome", "reply_status", "element_names"]'::jsonb,
     'docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md amendment C-2026-11-10 (PO chat approval 2026-11-10)')
ON CONFLICT (gate_id) DO NOTHING;
