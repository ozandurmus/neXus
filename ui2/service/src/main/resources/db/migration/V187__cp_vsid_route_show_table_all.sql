-- V187 -- Check Point per-virtual-system route read: every routing table, not only main (path analysis gap G6).
-- PO approval 2026-11-04 (chat: "1-5 onaylı devam"; AGENTS.md 2026-09-26 amendment, decision D-7) of row 1 exactly as
-- written in docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md (RATIFIED):
--   bash -lc 'vsenv <VSID> && ip -4 route show table all'
-- Same session, timeout, frequency and VSID validation (digits only) as the per-VS read it extends.
--
-- Two additive CLASS_0 read rows, same shape as V37 (no existing row is touched; the superseded composite
-- cp_inventory_vsid_fw_getifs_and_route stays SIGNED_OFF as the record of what ran before):
--   1. cp_inventory_vsid_ip_route_show_table_all -- the approved literal.
--   2. cp_inventory_vsid_fw_getifs -- the interface half of the old composite as its own call. The composite
--      'fw getifs && ip -4 route show' cannot carry the approved route literal, so the two reads are issued separately
--      (InventoryReadPlan.checkPointVsidSteps). It is the same read the composite already ran per virtual system, now
--      issued only where its output is used (a standalone VSX gateway; a ClusterXL member reads cphaprob -a if instead).
--      The orchestrator should confirm with the PO that this split is acceptable.
-- Seed data only; re-running is a no-op. MUST be dry-run (BEGIN ... ROLLBACK) on the live ui2 database before rollout.
SELECT set_config('app.actor_fingerprint', 'migration:V187_cp_vsid_route_show_table_all', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_inventory_vsid_ip_route_show_table_all', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv <VSID> && ip -4 route show table all''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per virtual system per run', 'one session per device per run, VS0 never re-entered',
     'an empty table is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md row 1 (PO approval 2026-11-04: "1-5 onaylı devam"); docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 23'),
    ('cp_inventory_vsid_fw_getifs', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv <VSID> && fw getifs''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per virtual system per run', 'one session per device per run, VS0 never re-entered',
     'an address-less virtual system is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 24 (the interface half of entry 17, split out for the G6 route literal; PO approval 2026-11-04 of PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md row 1)')
ON CONFLICT (gate_id) DO NOTHING;
