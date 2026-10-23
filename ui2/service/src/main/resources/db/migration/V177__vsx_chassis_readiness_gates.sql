-- V177 -- VSX chassis step: the VS0 readiness reads (docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md,
-- RATIFIED; the PO answered "Onaylıyorum" on 2026-10-23). Design: docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3.
-- Eleven new rows, each the exact literal bash -lc 'vsenv 0 && <read>' (CF-2: a VSX host's landing context is never
-- assumed), seeded SIGNED_OFF for the readiness of the chassis unit. cp_policy_install_cpstat_vsenv0 is the execution-only
-- read; the worker never issues it while the chassis failover is refused ("Önce readiness, sonra karar":
-- VSX_CHASSIS_FAILOVER_NOT_APPROVED stays). No write row: the chassis clusterXL_admin literal waits for its own decision.
-- The two existing VS0 inventory rows (cp_inventory_cphaprob_stat_vsenv0, cp_inventory_cphaprob_a_if_vsenv0) gain the
-- readiness purpose in their max_frequency, as V170 extended cp_inventory_cphaprob_stat. Command, context, transport,
-- timeout and retry are unchanged. Renumbered past V176 (lane/backup-major-deviations). Seed data only; re-running is a
-- no-op (ON CONFLICT DO NOTHING; the amendments are guarded).
SELECT set_config('app.actor_fingerprint', 'migration:V177_vsx_chassis_readiness_gates', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_failover_tablestat_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cphaprob tablestat''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per readiness run of a VSX chassis; failover-run use waits for the PO''s chassis failover decision',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md (RATIFIED); docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3'),
    ('cp_failover_arp_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && arp -an''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per readiness run of a VSX chassis; failover-run use waits for the PO''s chassis failover decision',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md (RATIFIED); docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3'),
    ('cp_failover_traffic_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cat /proc/net/dev''', 'read', 'SIGNED_OFF', 30, 'none',
     'two samples 5 s apart per member per readiness run of a VSX chassis; failover-run use waits for the PO''s chassis failover decision',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md (RATIFIED); docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3'),
    ('cp_failover_syncstat_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cphaprob syncstat''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per readiness run of a VSX chassis; failover-run use waits for the PO''s chassis failover decision',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md (RATIFIED); docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3'),
    ('cp_failover_fw_stat_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && fw stat''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per readiness run of a VSX chassis; failover-run use waits for the PO''s chassis failover decision',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md (RATIFIED); docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3'),
    ('cp_failover_pnotes_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cphaprob -ia list''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per readiness run of a VSX chassis; failover-run use waits for the PO''s chassis failover decision',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '["pnotes"]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md (RATIFIED); docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3'),
    ('cp_failover_bonds_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cphaprob show_bond''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per readiness run of a VSX chassis; failover-run use waits for the PO''s chassis failover decision',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md (RATIFIED); docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3'),
    ('cp_failover_bond_members_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cphaprob show_bond <BOND>''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per selected bond per member per readiness run of a VSX chassis; failover-run use waits for the PO''s chassis failover decision',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md (RATIFIED); docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3'),
    ('cp_failover_last_event_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cphaprob show_failover''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per readiness run of a VSX chassis; failover-run use waits for the PO''s chassis failover decision',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '["lastFailoverAt", "assumedTimeZone"]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md (RATIFIED); docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3'),
    ('cp_failover_routing_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cpstat os -f routing''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per member per readiness run of a VSX chassis; failover-run use waits for the PO''s chassis failover decision',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'UNKNOWN', 'none', '["routeCount", "defaultRoute"]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md (RATIFIED); docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3'),
    ('cp_policy_install_cpstat_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cpstat -f policy fw''', 'read', 'SIGNED_OFF', 30, 'none',
     'once pre, once post-switch and once post-return per member per failover run of a VSX chassis; unused while chassis failover is refused (VSX_CHASSIS_FAILOVER_NOT_APPROVED)',
     'one SSH session per member per run; commands serial; SSH_EXEC only (the one-shell framing does not name the vsenv 0 form)',
     'missing policy or install time -> UNKNOWN; blocks failover', 'policy identities stay in memory; only equality, presence and install-time skew are projected', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md (RATIFIED); docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md section 3')
ON CONFLICT (gate_id) DO NOTHING;

UPDATE gate_registry SET max_frequency = max_frequency
    || '; failover readiness of a VSX chassis (PO 2026-10-23): once per member per readiness run, in that run''s one SSH session per member'
WHERE gate_id = 'cp_inventory_cphaprob_stat_vsenv0'
  AND max_frequency NOT LIKE '%failover readiness of a VSX chassis%';
UPDATE gate_registry SET max_frequency = max_frequency
    || '; failover readiness of a VSX chassis (PO 2026-10-23): once per member per readiness run, in that run''s one SSH session per member'
WHERE gate_id = 'cp_inventory_cphaprob_a_if_vsenv0'
  AND max_frequency NOT LIKE '%failover readiness of a VSX chassis%';
