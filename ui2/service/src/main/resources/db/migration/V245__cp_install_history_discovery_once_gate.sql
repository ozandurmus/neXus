-- V245 -- One-off: where Check Point keeps Jumbo/hotfix install history, read once on one device.
-- PO approval 2026-11-16 (chat: "Evet, onaylıyorum") of this exact literal:
--   unset BASH_ENV ENV; ls -l --time-style=+%Y-%m-%dT%H:%M:%S /opt/CPInstLog 2>&1 | tail -n 80; find /opt/CPda /var/log/CPda -maxdepth 3 -name 'DA_Actions*' 2>/dev/null | head -5
-- Sent only while ui2-worker runs with UI2_CP_INSTALL_HISTORY_DISCOVERY_DEVICE=<device id>, set for one inventory run
-- and removed right after. Plain expert shell, never a login shell. See
-- docs/design/PO_DECISION_RECORD_2026_11_16_CP_INSTALL_HISTORY_DISCOVERY.md.
SELECT set_config('app.actor_fingerprint', 'migration:V245_cp_install_history_discovery_once_gate', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_install_history_discovery_once', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; ls -l --time-style=+%Y-%m-%dT%H:%M:%S /opt/CPInstLog 2>&1 | tail -n 80; find /opt/CPda /var/log/CPda -maxdepth 3 -name ''DA_Actions*'' 2>/dev/null | head -5',
     'read', 'SIGNED_OFF', 30, 'none', 'once, one STANDBY non-VSX R81.20 gateway (2026-11-16)',
     'the inventory session',
     'a failure or no answer is logged in the job transcript; the inventory outcome is unchanged',
     'none (file names, sizes and dates only)',
     '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_11_16_CP_INSTALL_HISTORY_DISCOVERY.md (PO chat approval 2026-11-16)')
ON CONFLICT (gate_id) DO NOTHING;
