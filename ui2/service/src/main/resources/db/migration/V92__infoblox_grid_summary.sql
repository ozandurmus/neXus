-- PO 2026-09-26: five read-only WAPI objects, one summary per completed inventory run.
SELECT set_config('app.actor_fingerprint', 'migration:V92_infoblox_grid_summary', true);
SELECT set_config('app.action_id', 'infoblox_grid_summary_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('infoblox_dns_views', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS', 'GET /wapi/v<ver>/view', 'read', 'SIGNED_OFF', 60, 'none', 'once per inventory', 'one client per run', 'failed object -> null; paging cap -> at least count', 'none', '["count","field_names"]'::jsonb, 'PO 2026-09-26 Infoblox grid summary'),
 ('infoblox_auth_zones', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS', 'GET /wapi/v<ver>/zone_auth', 'read', 'SIGNED_OFF', 60, 'none', 'once per inventory', 'one client per run', 'failed object -> null; paging cap -> at least count', 'none', '["count","field_names"]'::jsonb, 'PO 2026-09-26 Infoblox grid summary'),
 ('infoblox_dhcp_networks', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS', 'GET /wapi/v<ver>/network', 'read', 'SIGNED_OFF', 60, 'none', 'once per inventory', 'one client per run', 'failed object -> null; paging cap -> at least count', 'none', '["count","field_names"]'::jsonb, 'PO 2026-09-26 Infoblox grid summary'),
 ('infoblox_dhcp_ranges', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS', 'GET /wapi/v<ver>/range', 'read', 'SIGNED_OFF', 60, 'none', 'once per inventory', 'one client per run', 'failed object -> null; paging cap -> at least count', 'none', '["count","field_names"]'::jsonb, 'PO 2026-09-26 Infoblox grid summary'),
 ('infoblox_member_licenses', 'infoblox', 'infoblox_grid_manager', 'not_applicable', 'HTTPS', 'GET /wapi/v<ver>/member:license', 'read', 'SIGNED_OFF', 60, 'none', 'once per inventory', 'one client per run', 'failed object -> null; paging cap -> at least count', 'none', '["count","field_names"]'::jsonb, 'PO 2026-09-26 Infoblox grid summary')
ON CONFLICT (gate_id) DO NOTHING;

CREATE TABLE infoblox_grid_summary (
    run_id text PRIMARY KEY REFERENCES device_inventory_run(run_id) ON DELETE CASCADE,
    dns_views integer, dns_views_at_least boolean NOT NULL DEFAULT false,
    auth_zones integer, auth_zones_at_least boolean NOT NULL DEFAULT false,
    dhcp_networks integer, dhcp_networks_at_least boolean NOT NULL DEFAULT false,
    dhcp_ranges integer, dhcp_ranges_at_least boolean NOT NULL DEFAULT false,
    top_networks jsonb, licenses jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
