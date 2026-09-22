-- V43 -- PAN backup bundle: the set-format running configuration over the CLI
-- (backlog pan_backup_include_set_format_config, Product Owner P0 2026-09-22).
-- Backbox's measured sequence (trail 34411050) is scripting-mode on, pager off,
-- config-output-format set, then configure + show (the candidate configuration).
-- neXus takes `show config running` in operational mode instead: the running
-- configuration is what the device enforces, and one prompt serves the whole
-- session. Four interactive reads over the device's own SSH (port 22), the
-- device's collection credential, host key trusted on first use like every
-- other SSH endpoint. docs/design/PAN_BACKUP_COMMAND_GATE_ENTRIES.md.
SELECT set_config('app.actor_fingerprint', 'migration:V43_pan_backup_ssh_set_config_gates', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('pan_backup_ssh_scripting_mode_on', 'palo_alto', 'pan_firewall', 'operational', 'SSH_EXEC',
     'set cli scripting-mode on', 'read', 'SIGNED_OFF', 20, 'none', 'once per run, first command of the session',
     'one interactive session held for the four reads',
     'a CLI session setting (no device state changes); answers with the prompt only', 'none', '[]'::jsonb,
     'docs/design/PAN_BACKUP_COMMAND_GATE_ENTRIES.md entry 1 -- Backbox trail 34411050'),
    ('pan_backup_ssh_pager_off', 'palo_alto', 'pan_firewall', 'operational', 'SSH_EXEC',
     'set cli pager off', 'read', 'SIGNED_OFF', 20, 'none', 'once per run',
     'one interactive session held for the four reads',
     'a CLI session setting; without it the show output stops at --more--', 'none', '[]'::jsonb,
     'docs/design/PAN_BACKUP_COMMAND_GATE_ENTRIES.md entry 2 -- Backbox trail 34411050'),
    ('pan_backup_ssh_config_output_format_set', 'palo_alto', 'pan_firewall', 'operational', 'SSH_EXEC',
     'set cli config-output-format set', 'read', 'SIGNED_OFF', 20, 'none', 'once per run',
     'one interactive session held for the four reads',
     'a CLI session setting; selects the set-format rendering for the read that follows', 'none', '[]'::jsonb,
     'docs/design/PAN_BACKUP_COMMAND_GATE_ENTRIES.md entry 3 -- Backbox trail 34411050'),
    ('pan_backup_ssh_show_config_running', 'palo_alto', 'pan_firewall', 'operational', 'SSH_EXEC',
     'show config running', 'read', 'SIGNED_OFF', 180, 'none', 'once per run',
     'one interactive session held for the four reads',
     'the whole running configuration in set format: carries password hashes and keys (secret-bearing); stored only inside the envelope-encrypted bundle, never parsed, never surfaced',
     'high', '[]'::jsonb,
     'docs/design/PAN_BACKUP_COMMAND_GATE_ENTRIES.md entry 4 -- neXus deviation from Backbox (running, not candidate)');
