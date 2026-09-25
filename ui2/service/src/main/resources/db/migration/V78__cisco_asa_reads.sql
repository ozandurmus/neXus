-- V78 -- Cisco ASA over SSH (PO 2026-09-25: "Cisco ASA'ları halledelim"; docs/design/CISCO_ASA_CONTRACT.md). One
-- interactive shell per run on a privilege-15 account. Every command is a read; "terminal pager 0" is a setting of
-- this login's session only. The backup is the configuration text -- never the ASA's own "backup" command, which
-- writes an archive to the device's flash and needs "ssh scopy enable" to be pulled.
SELECT set_config('app.actor_fingerprint', 'migration:V78_cisco_asa_reads', true);
SELECT set_config('app.action_id', 'cisco_asa_reads_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
 ('asa_terminal_pager_0', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'terminal pager 0', 'read', 'SIGNED_OFF', 30,
  'none', 'once per session', 'one session per device per run', 'a refusal leaves paging on; reads still bounded by their timeout',
  'none', '[]'::jsonb, 'Cisco ASA command reference (terminal pager); Backbox ASA 9.4+ trail 2026-09-22'),
 ('asa_show_curpriv', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show curpriv', 'read', 'SIGNED_OFF', 30,
  'none', 'once per session', 'one session per device per run', 'below 15 -> the run stops with the level named',
  'username of the session (never persisted)', '[]'::jsonb, 'Cisco ASA command reference (show curpriv)'),
 ('asa_show_version', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show version', 'read', 'SIGNED_OFF', 30,
  'none', 'once per confirm, inventory and backup', 'one session per device per run', 'no "Adaptive Security Appliance Software Version" -> not an ASA, failed',
  'serial number and hostname (masked to aiview)', '[]'::jsonb, 'Cisco ASA command reference (show version); Backbox ASA trail'),
 ('asa_show_failover_this_host', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show failover | include This host', 'read', 'SIGNED_OFF', 30,
  'none', 'once per inventory', 'one session per device per run', 'no line -> standalone',
  'none', '[]'::jsonb, 'Cisco ASA command reference (show failover)'),
 ('asa_show_mode', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show mode', 'read', 'SIGNED_OFF', 30,
  'none', 'once per inventory and backup', 'one session per device per run', 'no answer -> single context assumed and logged',
  'none', '[]'::jsonb, 'Cisco ASA command reference (show mode)'),
 ('asa_show_ip_address', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show ip address', 'read', 'SIGNED_OFF', 30,
  'none', 'once per inventory', 'one session per device per run', 'no rows -> interfaces without addresses',
  'interface addresses (masked to aiview)', '[]'::jsonb, 'Cisco ASA command reference (show ip address)'),
 ('asa_show_interface_ip_brief', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show interface ip brief', 'read', 'SIGNED_OFF', 30,
  'none', 'once per inventory', 'one session per device per run', 'no rows -> interface states unknown',
  'interface addresses (masked to aiview)', '[]'::jsonb, 'Cisco ASA command reference (show interface ip brief)'),
 ('asa_show_route', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show route', 'read', 'SIGNED_OFF', 180,
  'none', 'once per inventory', 'one session per device per run', 'no rows -> no routes recorded',
  'route destinations and next hops (masked to aiview)', '[]'::jsonb, 'Cisco ASA command reference (show route)'),
 ('asa_more_system_running_config', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'more system:running-config', 'read', 'SIGNED_OFF', 180,
  'none', 'once per backup', 'one session per device per run', 'no "ASA Version" line -> backup refused, nothing stored',
  'full configuration with keys: stored only inside the encrypted backup artefact, never logged', '[]'::jsonb,
  'Backbox ASA 9.4+ trail 2026-09-22 (the same read); Cisco ASA command reference (more)'),
 ('asa_show_startup_config', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_EXEC', 'show startup-config', 'read', 'SIGNED_OFF', 180,
  'none', 'once per backup', 'one session per device per run', 'no answer -> the bundle has no startup-config.txt (manifest says so)',
  'configuration (keys masked by the ASA): stored only inside the encrypted backup artefact', '[]'::jsonb,
  'Backbox ASA 9.4+ trail 2026-09-22 (the same read)')
ON CONFLICT (gate_id) DO NOTHING;
