-- V172 -- PO decision 2026-10-21 (docs/design/PO_DECISION_RECORD_2026_10_21_PAN_TYPE_VERSION_AND_CHECK7.md, RATIFIED):
-- a Palo Alto firewall's serial, model and PAN-OS version are read with the documented XML API request type=version
-- ("Tum PAN-OS surumleri" -- every PAN-OS version), in place of the show system info that V171 retired for firewalls.
--
-- One new gate row, platform_role_scope pan_firewall, SIGNED_OFF per that decision: the identity step of the enrollment
-- confirm, the inventory run and the configuration collect (read-only; a form request, POST /api/ with the body
-- type=version and the key in the X-PAN-KEY header, no cmd). The Panorama scope is unchanged: its system info row
-- pan_panorama_show_system_info (V83) stays as it is, and so does the V171 withdrawal of the firewall system info row.
-- The same columns and conventions as the other form-request rows (pan_configuration_active, V16). Re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V172_pan_firewall_type_version_identity', true);
SELECT set_config('app.action_id', 'gate_registry_signoff_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('pan_identity_version', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     'type=version', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per confirm, inventory or configuration run',
     'the one key-generation session per run',
     'error envelope, non-XML or missing serial -> identity NOT_EVALUABLE (strict posture refuses); a missing model or version field is UNKNOWN',
     'serial and model (sensitive identity; compared locally, masked to aiview)', '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_10_21_PAN_TYPE_VERSION_AND_CHECK7.md (PO chat 2026-10-21: all PAN-OS versions)')
ON CONFLICT (gate_id) DO NOTHING;
