-- V255 -- VSX chassis failover, option B (VSX HA mode): the two VS0 writes
-- (docs/design/PO_DECISION_RECORD_2026_11_19_VSX_CHASSIS_FAILOVER.md, RATIFIED; the PO answered "onaylıyorum" on
-- 2026-11-19 to the exact literals bash -lc 'vsenv 0 && clusterXL_admin down' and bash -lc 'vsenv 0 && clusterXL_admin up';
-- FAILOVER_EXECUTION_CP_CONTRACT.md section 17 amends section 10).
--   1. Two CLASS_2 operational-state-change rows, modelled on cp_failover_down_vsid / cp_failover_up_vsid (same
--      platform, Expert, SSH_EXEC, 30 s, never retried), with the ids CpFailoverJobExecutor.chassisGateId already names.
--   2. The VS0 reads of the chassis (V177 rows and the two inventory VS0 rows) gain the chassis failover-run purpose in
--      their max_frequency; the per-VS cphaprob stat gains the chassis run's VS-moved verification (the PO's mitigation).
--      Command, context, transport, timeout and retry are unchanged.
-- Seed data only; re-running is a no-op (ON CONFLICT DO NOTHING; every amendment is guarded).
SELECT set_config('app.actor_fingerprint', 'migration:V255_vsx_chassis_failover_gates', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_failover_down_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && clusterXL_admin down''', 'operational-state-change', 'SIGNED_OFF', 30, 'none',
     'once on the chassis''s active member per failover run of a VSX HA-mode chassis; never on a VSLS chassis',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_11_19_VSX_CHASSIS_FAILOVER.md (RATIFIED) section 7; docs/design/FAILOVER_EXECUTION_CP_CONTRACT.md section 17'),
    ('cp_failover_up_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && clusterXL_admin up''', 'operational-state-change', 'SIGNED_OFF', 30, 'none',
     'once on the former active member per failover run (or its one resumed return) of a VSX HA-mode chassis',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_11_19_VSX_CHASSIS_FAILOVER.md (RATIFIED) section 7; docs/design/FAILOVER_EXECUTION_CP_CONTRACT.md section 17')
ON CONFLICT (gate_id) DO NOTHING;

UPDATE gate_registry SET max_frequency = max_frequency
    || '; failover run of a VSX HA-mode chassis (PO 2026-11-19): once pre, once post-switch and once post-return per member'
WHERE gate_id IN ('cp_inventory_cphaprob_a_if_vsenv0', 'cp_failover_tablestat_vsenv0', 'cp_failover_arp_vsenv0',
        'cp_failover_traffic_vsenv0', 'cp_failover_syncstat_vsenv0', 'cp_failover_fw_stat_vsenv0',
        'cp_failover_pnotes_vsenv0', 'cp_failover_bonds_vsenv0', 'cp_failover_bond_members_vsenv0',
        'cp_failover_last_event_vsenv0', 'cp_failover_routing_vsenv0', 'cp_policy_install_cpstat_vsenv0')
  AND max_frequency NOT LIKE '%failover run of a VSX HA-mode chassis%';
UPDATE gate_registry SET max_frequency = max_frequency
    || '; failover run of a VSX HA-mode chassis (PO 2026-11-19): once pre, twice in each post-switch and post-return phase per member, plus a shared budget of 21 polls every 3 s across DOWN and UP'
WHERE gate_id = 'cp_inventory_cphaprob_stat_vsenv0'
  AND max_frequency NOT LIKE '%failover run of a VSX HA-mode chassis%';
UPDATE gate_registry SET max_frequency = max_frequency
    || '; VS-moved verification of a VSX HA-mode chassis failover run (PO 2026-11-19): once per VS per member before the switch, then at most 10 rounds every 3 s after the switch and after the return'
WHERE gate_id = 'cp_inventory_vsid_cphaprob_stat'
  AND max_frequency NOT LIKE '%VS-moved verification of a VSX HA-mode chassis%';
