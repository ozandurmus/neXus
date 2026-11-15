-- V221 -- the Panorama predefined App-ID catalogue read for application-default ports (PO approval 2026-11-14,
-- verbatim "Onaylıyorum", option A), docs/design/PO_DECISION_RECORD_2026_11_14_GATEWAY_BOUND_AND_APPID_PORTS.md.
-- One call per policy collection on the same Panorama session as the four policy reads (V117); timeout 120 s, no
-- retry, 64 MB cap; only default/port, default/ident-by-ip-protocol and default/ident-by-icmp(6)-type are kept.
-- Additive only: one gate row. Without it the collector skips the read and nothing else changes.
SELECT set_config('app.actor_fingerprint', 'migration:V221_pan_predefined_applications_gate', true);
SELECT set_config('app.action_id', 'gate_registry_insert_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind, action_class, sign_off_state, timeout_s, retry_rule, max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields, source_document_pointer, canonical_command_key)
VALUES ('pan_policy_predefined_applications', 'palo_alto', 'panorama', 'not_applicable', 'PAN_XML_API', 'read', 'SIGNED_OFF', 120, 'none',
    'once per policy collection run, with the policy reads', 'the policy collection''s existing-authentication API key; serial',
    'any failure, an unexpected shape or more than 64 MB: no catalogue, application default ports stay unknown, the collection continues',
    'vendor content data, no customer secret; streamed, only default ports and identification protocol kept, raw reply discarded',
    '["outcome", "applications", "withPorts", "bytes"]',
    'docs/design/PO_DECISION_RECORD_2026_11_14_GATEWAY_BOUND_AND_APPID_PORTS.md',
    'type=config&action=get&xpath=/config/predefined/application')
ON CONFLICT (gate_id) DO NOTHING;
