-- V248 -- "Traffic and ARP (information)" readiness check for Cisco ASA, FortiGate and Palo Alto (PO chat 2026-11-17:
-- "like Check Point, show that traffic really passes on the other vendors", "count ARP before and after the switch").
-- Six read-only gate rows, one traffic read and one ARP read per vendor, used only by a readiness run on the active (ASA,
-- Palo Alto) or enrolled primary (FortiGate) member. They are inserted DRAFTED: a DRAFTED row never resolves, nothing is
-- sent, and the new check stays UNKNOWN and non-blocking until the Product Owner approves the exact literals.
--   cisco_asa  : show traffic, show arp statistics              (SSH_INTERACTIVE, privileged_exec, one session)
--   fortinet   : get system performance status, get system arp  (SSH_EXEC, top_or_global; the support-bundle row
--                fgt_sb_performance_status has another transport and purpose and stays as it is)
--   palo_alto  : <show><counter><interface>all</interface></counter></show> (sampled twice, at least 5 s apart)
--                and <show><arp><entry name = 'all'/></arp></show>  (PAN_XML_API)
-- SIGN-OFF IS ONE LINE: change 'DRAFTED' in the sign_off(...) row below to 'SIGNED_OFF' (and the note beside it); every
-- row takes both values from there. No new table, constraint or function. Dry-run in BEGIN/ROLLBACK on the live database.
SELECT set_config('app.actor_fingerprint', 'migration:V248_failover_traffic_arp_reads', true);
SELECT set_config('app.action_id', 'failover_traffic_arp_reads_by_migration', true);

WITH sign_off(state, note) AS (
    VALUES ('DRAFTED', 'DRAFT, awaiting PO approval of the exact literals (PO chat 2026-11-17)')
)
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
SELECT g.gate_id, g.vendor, g.platform_role_scope, g.shell_context, g.transport_kind, g.canonical_command_key, 'read',
    s.state, 30, 'none', g.max_frequency, g.session_reuse_rule,
    'refused, unparsable or timed out -> the "Traffic and ARP (information)" check is UNKNOWN and never blocks; nothing is retried',
    g.secret_output_risk, '[]'::jsonb, g.reference || '; ' || s.note
FROM sign_off s CROSS JOIN (VALUES
 ('asa_failover_show_traffic', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_INTERACTIVE', 'show traffic',
  'once per readiness run, on the active member only',
  'the existing readiness SSH session of the active member (privilege 15, single context), after the failover reads',
  'output names interfaces; reduced in memory to one summed byte rate, never stored or recorded in the transcript',
  'Cisco ASA command reference (show traffic): 1 minute input rate plus 1 minute output rate, bytes/sec, over the Aggregated Traffic on Physical Interface section or else every interface'),
 ('asa_failover_show_arp_statistics', 'cisco_asa', 'cisco_asa_firewall', 'privileged_exec', 'SSH_INTERACTIVE', 'show arp statistics',
  'once per readiness run, on the active member only',
  'the existing readiness SSH session of the active member (privilege 15, single context), after the failover reads',
  'output carries counters only; reduced in memory to the Number of ARP entries line, never stored or recorded in the transcript',
  'Cisco ASA command reference (show arp statistics): Number of ARP entries'),
 ('fgt_failover_performance_status', 'fortinet', 'fortigate', 'top_or_global', 'SSH_EXEC', 'get system performance status',
  'once per readiness run, on the enrolled primary only',
  'one SSH session on the enrolled cluster address (global context when VDOMs are on), commands one at a time with a configurable pause',
  'output carries counters only; reduced in memory to the 1-minute network usage and average sessions, never stored or recorded in the transcript',
  'FortiOS CLI reference (get system performance status): Average network usage inbound / outbound kbps in 1 minute, Average sessions in 1 minute'),
 ('fgt_failover_arp', 'fortinet', 'fortigate', 'top_or_global', 'SSH_EXEC', 'get system arp',
  'once per readiness run, on the enrolled primary only',
  'one SSH session on the enrolled cluster address (global context when VDOMs are on), commands one at a time with a configurable pause',
  'output names addresses, MAC addresses and interfaces; reduced in memory to the number of entry lines, never stored or recorded in the transcript',
  'FortiOS CLI reference (get system arp): entry lines are counted, addresses are never kept'),
 ('pan_failover_traffic_counters', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API', '<show><counter><interface>all</interface></counter></show>',
  'twice per readiness run, at least 5 s apart, on the active firewall only (counter reset or decrease -> UNKNOWN)',
  'one key per peer per run',
  'output names interfaces; reduced in memory to one summed ibytes plus obytes delta per second over the hardware interfaces, never stored',
  'PAN-OS XML API op command (show counter interface all): hardware interface ibytes and obytes'),
 ('pan_failover_arp_table', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API', '<show><arp><entry name = ''all''/></arp></show>',
  'once per readiness run, on the active firewall only',
  'one key per peer per run',
  'output names addresses, MAC addresses and interfaces; reduced in memory to the total field (else the entry count), never stored',
  'PAN-OS XML API op command (show arp all): total')
) AS g(gate_id, vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key, max_frequency,
       session_reuse_rule, secret_output_risk, reference)
ON CONFLICT (gate_id) DO NOTHING;
