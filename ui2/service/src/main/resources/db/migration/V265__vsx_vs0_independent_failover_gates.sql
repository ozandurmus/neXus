-- V265 -- VSX: VS0 is its own failover unit, independent of every virtual system, in VSX HA and VSLS mode alike
-- (docs/design/PO_DECISION_RECORD_2026_11_23_VSX_VS0_INDEPENDENT.md, RATIFIED, authorization ref
-- PO-2026-11-23-vsx-vs0-independent; FAILOVER_EXECUTION_CP_CONTRACT.md section 17 as amended 2026-11-23).
--   1. cp_failover_down_vsenv0 / cp_failover_up_vsenv0 (V255): the allowed frequency/context text is widened from "a VSX
--      HA-mode chassis; never on a VSLS chassis" to "a VS0 failover run of a VSX chassis in HA or VSLS mode".
--   2. The VS0 reads of that run (the V177 rows and the two inventory VS0 rows, as amended by V255): the V255 purpose
--      clause "failover run of a VSX HA-mode chassis (PO 2026-11-19)" becomes "VS0 failover run of a VSX chassis in HA or
--      VSLS mode (PO 2026-11-23)"; the counts that follow it are unchanged.
-- Only max_frequency changes. Command, context, transport, class, sign-off, timeout and retry are unchanged; every UPDATE
-- also matches the row's exact canonical command, so no command is touched. Never edits V177 or V255.
-- Seed data only; re-running is a no-op (every amendment is guarded).
SELECT set_config('app.actor_fingerprint', 'migration:V265_vsx_vs0_independent_failover_gates', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

UPDATE gate_registry SET max_frequency =
    'once per VS0 failover run on the active member of a VSX chassis in HA or VSLS mode (PO 2026-11-23)'
WHERE gate_id = 'cp_failover_down_vsenv0'
  AND canonical_command_key = 'bash -lc ''vsenv 0 && clusterXL_admin down'''
  AND max_frequency NOT LIKE '%HA or VSLS mode%';
UPDATE gate_registry SET max_frequency =
    'once per VS0 failover run (or its one resumed return) on the former active member of a VSX chassis in HA or VSLS mode (PO 2026-11-23)'
WHERE gate_id = 'cp_failover_up_vsenv0'
  AND canonical_command_key = 'bash -lc ''vsenv 0 && clusterXL_admin up'''
  AND max_frequency NOT LIKE '%HA or VSLS mode%';

UPDATE gate_registry SET max_frequency = replace(max_frequency,
    'failover run of a VSX HA-mode chassis (PO 2026-11-19)',
    'VS0 failover run of a VSX chassis in HA or VSLS mode (PO 2026-11-23)')
WHERE (gate_id, canonical_command_key) IN (
        ('cp_inventory_cphaprob_stat_vsenv0', 'bash -lc ''vsenv 0 && cphaprob stat'''),
        ('cp_inventory_cphaprob_a_if_vsenv0', 'bash -lc ''vsenv 0 && cphaprob -a if'''),
        ('cp_failover_tablestat_vsenv0', 'bash -lc ''vsenv 0 && cphaprob tablestat'''),
        ('cp_failover_arp_vsenv0', 'bash -lc ''vsenv 0 && arp -an'''),
        ('cp_failover_traffic_vsenv0', 'bash -lc ''vsenv 0 && cat /proc/net/dev'''),
        ('cp_failover_syncstat_vsenv0', 'bash -lc ''vsenv 0 && cphaprob syncstat'''),
        ('cp_failover_fw_stat_vsenv0', 'bash -lc ''vsenv 0 && fw stat'''),
        ('cp_failover_pnotes_vsenv0', 'bash -lc ''vsenv 0 && cphaprob -ia list'''),
        ('cp_failover_bonds_vsenv0', 'bash -lc ''vsenv 0 && cphaprob show_bond'''),
        ('cp_failover_bond_members_vsenv0', 'bash -lc ''vsenv 0 && cphaprob show_bond <BOND>'''),
        ('cp_failover_last_event_vsenv0', 'bash -lc ''vsenv 0 && cphaprob show_failover'''),
        ('cp_failover_routing_vsenv0', 'bash -lc ''vsenv 0 && cpstat os -f routing'''),
        ('cp_policy_install_cpstat_vsenv0', 'bash -lc ''vsenv 0 && cpstat -f policy fw'''))
  AND max_frequency LIKE '%failover run of a VSX HA-mode chassis (PO 2026-11-19)%';
