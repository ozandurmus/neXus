-- V179 -- PO decision 2026-10-24 (docs/design/PO_DECISION_RECORD_2026_10_24_PAN_OS12_REPLACEMENT_READS.md, RATIFIED):
-- on Palo Alto firewalls running PAN-OS 12 or later, failover check 6 (sessions carried) reads show session meter in
-- place of show session info (candidate GR-7 of PAN_OS12_COMMAND_ALTERNATIVES_2026_10_21.md). Below PAN-OS 12 nothing
-- changes: pan_failover_session_info (V104) stays as it is and is still the read there.
--
-- One new gate row, platform_role_scope pan_firewall, read-only, SIGNED_OFF per that decision: the readiness run and the
-- failover run (pre, post-switch, post-return), one op request per member per phase, key in the X-PAN-KEY header. The
-- same columns and conventions as V104. Re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V179_pan_failover_session_meter', true);
SELECT set_config('app.action_id', 'gate_registry_signoff_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('pan_failover_session_meter', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<show><session><meter/></session></show>', 'read', 'SIGNED_OFF', 30, 'none',
     'PAN-OS 12 or later only (replace_12): once pre, once post-switch and once post-return per member per failover run; once per readiness run',
     'one key per peer per run',
     'error envelope, no entry or an entry without a numeric current -> check 6 UNKNOWN (blocks); never a guessed count',
     'none', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_24_PAN_OS12_REPLACEMENT_READS.md (PO chat 2026-10-24: PAN-OS 12 replacement reads)')
ON CONFLICT (gate_id) DO NOTHING;
