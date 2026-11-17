-- V249 -- sign off the six "Traffic and ARP (information)" readiness reads inserted DRAFTED by V248 (PO chat
-- 2026-11-17, "Evet, onaylıyorum" to: ASA show traffic and show arp statistics; FortiGate get system performance
-- status and get system arp, the ARP read inside each VDOM; Palo Alto interface counters sampled twice and the ARP
-- table; and the already approved show system interface used at every FortiGate readiness run to list the VDOMs).
-- Record: docs/design/PO_DECISION_RECORD_2026_11_17_FAILOVER_TRAFFIC_ARP_READS.md.
-- Only those six rows change, and only from DRAFTED; the literals, keys, timeouts and retry rules stay as V248 set
-- them. fgt_show_system_interface stays SIGNED_OFF with its literal and key; only its stated frequency widens to the
-- readiness use. The check stays information only: it never blocks a readiness outcome. No state-changing literal.
-- Dry-run in BEGIN/ROLLBACK on the live database before rollout.
SELECT set_config('app.actor_fingerprint', 'migration:V249_failover_traffic_arp_signoff', true);
SELECT set_config('app.action_id', 'gate_registry_signoff_by_migration', true);

UPDATE gate_registry
SET sign_off_state = 'SIGNED_OFF',
    source_document_pointer = replace(source_document_pointer,
        'DRAFT, awaiting PO approval of the exact literals (PO chat 2026-11-17)',
        'PO approval 2026-11-17 (''Evet, onaylıyorum'': readiness runs only, read-only, reduced in memory); '
        || 'PO_DECISION_RECORD_2026_11_17_FAILOVER_TRAFFIC_ARP_READS.md')
WHERE gate_id IN ('asa_failover_show_traffic', 'asa_failover_show_arp_statistics',
                  'fgt_failover_performance_status', 'fgt_failover_arp',
                  'pan_failover_traffic_counters', 'pan_failover_arp_table')
  AND action_class = 'read' AND sign_off_state = 'DRAFTED';

UPDATE gate_registry
SET session_reuse_rule = 'one SSH session on the enrolled cluster address; on a multi-VDOM unit the read runs inside '
        || 'each VDOM (config vdom / edit <vdom> / end), the VDOMs listed by show system interface'
WHERE gate_id = 'fgt_failover_arp' AND action_class = 'read';

UPDATE gate_registry
SET max_frequency = 'once per inventory; once per FortiGate readiness run on a multi-VDOM unit, to list the VDOMs '
        || '(PO 2026-11-17)'
WHERE gate_id = 'fgt_show_system_interface' AND action_class = 'read' AND sign_off_state = 'SIGNED_OFF'
  AND max_frequency = 'once per inventory';
