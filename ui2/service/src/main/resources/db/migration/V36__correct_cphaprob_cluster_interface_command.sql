-- V36 -- corrects the Check Point cluster-virtual-interface read.
--
-- V15's cp_inventory_cphaprob_a_m_if (and its two vsenv-wrapped siblings)
-- registered 'cphaprob -a -m if'. Check Point's own documentation
-- (ClusterXL Administration Guide, "Monitoring Cluster Interfaces": cphaprob
-- [-a] if) names no -m flag for this subcommand. Measured live against the
-- fleet after this session's Product Owner-reported empty Cluster VIP
-- column: every device_interface_address row is role='member' (847 of
-- 847); zero is role='cluster_virtual'. The worker's own new diagnostic log
-- confirms the cause directly: vip_output_len=0 on a live collection --
-- the malformed flag returns no output at all, so
-- CheckPointClusterVirtualInterfaceParser never had a byte to read.
--
-- This migration is additive only, per V15's own rule ("no existing row is
-- touched"): the three wrong-flag rows stay exactly as SIGNED_OFF history
-- of what was actually run at the time, and three corrected rows are added
-- for the documented form. The application code and
-- capability-registry/cp_inventory_collect.yaml move to the corrected
-- command in the same commit as this migration; nothing will look up the
-- old canonical_command_key again after that ships, but its row is kept
-- rather than deleted, matching the ledger tables' own append-only
-- discipline elsewhere in this schema.
SELECT set_config('app.actor_fingerprint', 'migration:V36_correct_cphaprob_cluster_interface_command', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_inventory_cphaprob_a_if', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'cphaprob -a if', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'no cluster virtual interfaces is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 5, corrected (V36)'),
    ('cp_inventory_cphaprob_a_if_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && cphaprob -a if''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per run', 'one session per device per run',
     'no cluster virtual interfaces is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 11, corrected (V36)'),
    ('cp_inventory_vsid_cphaprob_a_if', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv <VSID> && cphaprob -a if''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per virtual system per run', 'one session per device per run, VS0 never re-entered',
     'no cluster virtual interfaces for this VS is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 13, corrected (V36)');
