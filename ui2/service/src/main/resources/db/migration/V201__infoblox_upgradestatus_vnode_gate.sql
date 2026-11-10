-- V201 -- Infoblox member-level NIOS version read: one read-only gate row, measured on one grid first.
-- PO approval 2026-11-10 (PO session). The PO was asked:
--   "Infoblox'ta grid düzeyindeki kayıt sürüm bilgisini vermedi. Sürümü üye düzeyindeki kayıttan (upgradestatus type=VNODE) okumayı deneyelim mi?"
-- and answered:
--   "Evet, VNODE dene"
-- The request sent is GET /wapi/v<ver>/upgradestatus?type=VNODE&_return_fields=current_version : the member-level entries of
-- the same WAPI object, one return field. CLASS_0 read, no retry, sent inside the client the inventory run already holds,
-- only behind UI2_LIFECYCLE_READS / UI2_LIFECYCLE_READ_DEVICES (off by default), measured on the one grid first. Only a masked
-- shape is logged; the distinct version strings are the only thing stored. Seed data only; re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V201_infoblox_upgradestatus_vnode_gate', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('infoblox_upgradestatus_vnode', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS',
     'GET /wapi/v<ver>/upgradestatus?type=VNODE',
     'read', 'SIGNED_OFF', 60, 'none', 'once per inventory',
     'one client per run',
     'failed object or no version: NIOS version stays UNKNOWN',
     'member names in the object reference: never stored or logged',
     '["count", "field_names"]'::jsonb,
     'docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md amendment B-2026-11-10 (PO chat approval 2026-11-10)')
ON CONFLICT (gate_id) DO NOTHING;
