-- V210 -- Cisco ASA failover: the one-time, PO-attended read-only measurement (PO 2026-11-13, "Onaylıyorum (tek çift,
-- tek sefer)"; docs/design/PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md item 4).
-- Four exact literals, read-only. Only the inventory step gated by UI2_ASA_FAILOVER_MEASURE (two named devices, a
-- window of at most 60 minutes, once per device per worker process) issues them; they are excluded from diagnostic
-- reads. Answers are reduced in memory to a shape (no address, name, serial or version value) and never stored.
-- Regular use (readiness) needs its own approval.
SELECT set_config('app.actor_fingerprint', 'migration:V210_asa_failover_measure_gates', true);
SELECT set_config('app.action_id', 'asa_failover_measure_gates_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('asa_measure_show_failover', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show failover', 'read',
  'SIGNED_OFF', 30, 'none', 'once per device in the PO-attended measurement window',
  'the inventory session (pager off, privilege 15)', 'refused or timed out -> logged, next read', 'none (shape only logged)',
  '[]'::jsonb, 'Cisco ASA command reference (show failover); PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md'),
 ('asa_measure_show_failover_state', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show failover state', 'read',
  'SIGNED_OFF', 30, 'none', 'once per device in the PO-attended measurement window',
  'the inventory session (pager off, privilege 15)', 'refused or timed out -> logged, next read', 'none (shape only logged)',
  '[]'::jsonb, 'Cisco ASA command reference (show failover state); PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md'),
 ('asa_measure_show_failover_history', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show failover history', 'read',
  'SIGNED_OFF', 60, 'none', 'once per device in the PO-attended measurement window',
  'the inventory session (pager off, privilege 15)', 'refused or timed out -> logged, next read', 'none (shape only logged)',
  '[]'::jsonb, 'Cisco ASA command reference (show failover history); PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md'),
 ('asa_measure_show_conn_count', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show conn count', 'read',
  'SIGNED_OFF', 30, 'none', 'once per device in the PO-attended measurement window',
  'the inventory session (pager off, privilege 15)', 'refused or timed out -> logged, next read', 'none (counts and shape logged)',
  '[]'::jsonb, 'Cisco ASA command reference (show conn); PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md')
ON CONFLICT (gate_id) DO NOTHING;
