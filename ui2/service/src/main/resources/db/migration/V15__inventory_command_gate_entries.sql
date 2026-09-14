-- V15 -- NXS-LOCAL-0164: seeds gate_registry (created empty by V4; V14 is
-- the parallel discovery movement's own migration) with the Check Point
-- 14D CF-3/CF-2 literals and the Palo Alto 14E PF-1 requests the Product
-- Owner approved for live runs on 2026-09-14
-- (docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md,
-- docs/design/PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md). sign_off_state is
-- SIGNED_OFF throughout -- the Product Owner ran every literal below on
-- real hardware and approved the read-only set in the same session (the
-- hardware-confirmation C4 §3.3 step 5 requires); only the parser binding
-- against the live transport stays unverified, expressed on the capability
-- specs' own validation_status, never here.
--
-- These rows mirror capability-registry's own committed
-- gate_registry_fixture.yaml exactly (the version-controlled source, per
-- adjudication F2) -- inserted here directly, rather than left to
-- GateRegistrySeeder, because no application entry point in this
-- repository currently invokes that seeder at startup; this migration is
-- this movement's own path to a populated runtime table. Additive only;
-- no existing row is touched.

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    -- Check Point, 14D CF-3 physical reads, bare form (non-VSX gateway; the
    -- form cp_inventory_collect.yaml's own exec steps reference).
    ('cp_inventory_ip_addr_show_v4', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'ip -details -4 addr show', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'no interfaces configured is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 1'),
    ('cp_inventory_ip_addr_show_v6', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'ip -6 addr show', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'no IPv6 configured is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 2'),
    ('cp_inventory_ip_route_show_table_all', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'ip -4 route show table all', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'an empty table is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 3'),
    ('cp_inventory_cphaprob_stat', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'cphaprob stat', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'a standalone gateway reporting no cluster is a result, not an error',
     'none', '[]'::jsonb, 'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 4'),
    ('cp_inventory_cphaprob_a_m_if', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'cphaprob -a -m if', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'no cluster virtual interfaces is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 5'),
    ('cp_inventory_vsx_stat_v', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'vsx stat -v', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run',
     '''VSX is not supported on this platform'', exit 0, is a result, not an error (CF-4)', 'none',
     '[]'::jsonb, 'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 6'),

    -- Check Point, 14D CF-2 VSX-wrapped physical forms (bash -lc 'vsenv 0 && <read>').
    ('cp_inventory_ip_addr_show_v4_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && ip -details -4 addr show''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per run', 'one session per device per run',
     'no interfaces configured is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 7'),
    ('cp_inventory_ip_addr_show_v6_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && ip -6 addr show''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per run', 'one session per device per run',
     'no IPv6 configured is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 8'),
    ('cp_inventory_ip_route_show_table_all_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && ip -4 route show table all''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per run', 'one session per device per run',
     'an empty table is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 9'),
    ('cp_inventory_cphaprob_stat_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cphaprob stat''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per run', 'one session per device per run',
     'a standalone gateway reporting no cluster is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 10'),
    ('cp_inventory_cphaprob_a_m_if_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cphaprob -a -m if''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per run', 'one session per device per run',
     'no cluster virtual interfaces is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 11'),

    -- Check Point, 14D CF-2/CF-3 per-virtual-system composites. <VSID> is
    -- kept literally as the one substituted digits-only parameter these
    -- rows document (InventoryReadPlan.checkPointVsidSteps validates and
    -- substitutes the real VSID at run time; the closed command-template
    -- set, not a per-request GateResolver lookup, bounds these three).
    ('cp_inventory_vsid_addr_and_route', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv <VSID> && ip -4 addr show && ip -4 route show''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per virtual system per run', 'one session per device per run, VS0 never re-entered',
     'an address-less/route-less virtual system is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 12'),
    ('cp_inventory_vsid_cphaprob_a_m_if', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv <VSID> && cphaprob -a -m if''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per virtual system per run', 'one session per device per run, VS0 never re-entered',
     'no cluster virtual interfaces for this VS is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 13'),
    ('cp_inventory_vsid_cphaprob_stat', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv <VSID> && cphaprob stat''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per virtual system per run', 'one session per device per run, VS0 never re-entered',
     'same standalone-result rule as entry 4', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 14'),

    -- Palo Alto, 14E PF-1 requests, unscoped (PF-2: no &vsys=<id> form).
    ('pan_inventory_show_system_info', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<show><system><info/></system></show>', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'the one key-generation session per run', 'non-XML or error envelope is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md entry 1'),
    ('pan_inventory_show_ha_state', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<show><high-availability><state/></high-availability></show>', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per run', 'the one key-generation session per run',
     'HA disabled means no peer, a result, not an error', 'none', '[]'::jsonb,
     'docs/design/PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md entry 2'),
    ('pan_inventory_show_interface_all', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<show><interface>all</interface></show>', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'the one key-generation session per run',
     'a firewall with no configured interfaces beyond defaults is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md entry 3'),
    ('pan_inventory_show_routing_route', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<show><routing><route/></routing></show>', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'the one key-generation session per run', 'an empty route table is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md entry 4');
