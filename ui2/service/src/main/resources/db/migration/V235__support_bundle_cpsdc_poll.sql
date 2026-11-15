-- V235 -- support bundle collection: the approved read of the cpdata_collector exit code file
-- (docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md, FROZEN 2026-11-15;
-- docs/design/PO_DECISION_RECORD_2026_11_15_SUPPORT_BUNDLE_COLLECTION.md section 2, decision of 2026-11-15 about 22:30).
--
-- The Product Owner approved the literal `cat <workdir>/cpsdc.rc` (Check Point, CP_ENV prefix like every Check Point exec literal,
-- a read, 30 s, every 30 s until the deadline) so the cpdata_collector item reads the tool's exit code instead of waiting blind.
-- One row for the gateway scope and one for the management scope, as V234 did for its literals. The log tail literal was NOT
-- approved: a failed run is recorded as TOOL_EXIT_<rc>. V230, V232 and V234 are not edited.
-- Idempotent. Dry-run in BEGIN/ROLLBACK on the live database before deploy is an engineer step.
SELECT set_config('app.actor_fingerprint', 'migration:V235_support_bundle_cpsdc_poll', true);
SELECT set_config('app.action_id', 'support_bundle_cpsdc_poll_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields, source_document_pointer)
VALUES
    ('cp_sb_poll_cpsdc', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cat %s/cpsdc.rc',
     'read', 'SIGNED_OFF', 30, 'none', 'every 30 s until the cpdata_collector deadline (the device bound of 1800 s plus 60 s)', 'one session per run',
     'a missing file (still running) answers non-zero and is polled again; no answer three times in a row is logged', 'none',
     '["exitStatus"]'::jsonb, 'docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6 (cpdata_collector rc read); PO approval 2026-11-15 about 22:30 (PO record section 2)'),
    ('cpm_sb_poll_cpsdc', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cat %s/cpsdc.rc',
     'read', 'SIGNED_OFF', 30, 'none', 'every 30 s until the cpdata_collector deadline (the device bound of 1800 s plus 60 s)', 'one session per run',
     'a missing file (still running) answers non-zero and is polled again; no answer three times in a row is logged', 'none',
     '["exitStatus"]'::jsonb, 'docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6 (cpdata_collector rc read); PO approval 2026-11-15 about 22:30 (PO record section 2)')
ON CONFLICT (gate_id) DO NOTHING;
