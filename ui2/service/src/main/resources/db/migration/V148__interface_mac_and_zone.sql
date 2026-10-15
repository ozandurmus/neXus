-- V148 -- interface link-layer address and zone (PO, 2026-10-15).
--
-- Palo Alto: both come from the "show interface all" response already read and gated (V15, 14E):
-- hw/entry/mac and ifnet/entry/zone -- a parse-scope extension, no new command.
-- Check Point: "fw getifs" and "ip -4 addr show" carry no link-layer address, so one new read,
-- "ip -o link show" (Expert, read-only, once per device per run; vsenv 0 on a VSX host), approved by the
-- Product Owner in chat on 2026-10-15 ("1- ok"). Recorded in
-- docs/design/PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md.
--
-- Additive only: two nullable columns, two gate rows. An older run keeps NULL -- "not reported", never a guess.
SELECT set_config('app.actor_fingerprint', 'migration:V148_interface_mac_and_zone', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

ALTER TABLE device_interface ADD COLUMN IF NOT EXISTS mac_address TEXT
    CHECK (mac_address IS NULL OR mac_address ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$');
ALTER TABLE device_interface ADD COLUMN IF NOT EXISTS zone TEXT
    CHECK (zone IS NULL OR length(zone) BETWEEN 1 AND 128);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_inventory_ip_link_show', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'ip -o link show', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'an interface without a link-layer address is a result, not an error',
     'none', '[]'::jsonb, 'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 21 (2026-10-15 amendment)'),
    ('cp_inventory_ip_link_show_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && ip -o link show''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per run', 'one session per device per run',
     'an interface without a link-layer address is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 22 (2026-10-15 amendment)')
ON CONFLICT (gate_id) DO NOTHING;
