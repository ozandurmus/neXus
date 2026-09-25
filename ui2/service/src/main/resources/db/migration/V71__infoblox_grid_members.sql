-- V71 -- Infoblox grid members (PO 2026-09-25, after the first real Grid Manager backup): the confirm also reads the
-- grid's member list, one read per confirm, and shows the members under the Grid Manager the way a firewall's virtual
-- systems are shown. No new device rows: the grid backup already carries every member's configuration.
SELECT set_config('app.actor_fingerprint', 'migration:V71_infoblox_grid_members', true);
SELECT set_config('app.action_id', 'infoblox_grid_members_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('infoblox_member_list', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS', 'GET /wapi/v<ver>/member', 'read', 'SIGNED_OFF', 30,
  'none', 'once per confirm', 'one client per run', 'non-2xx or no array -> member list empty, confirm unaffected', 'member host names (shown masked to aiview as virtual systems are)', '[]'::jsonb, 'VENDOR_BACKUP_CONTRACTS_2026_09_22.md §4 grid members; PO 2026-09-25')
ON CONFLICT (gate_id) DO NOTHING;
