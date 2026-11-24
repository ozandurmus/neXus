-- V269 -- SNMP device metrics, wave 1: command-gate rows for the SNMPv3 reads of the pilot vendors
-- (docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md, FROZEN 2026-11-24, section 4 and section 11 items 4 and 6).
--
-- New transport kind SNMP_V3. Every row is action_class 'read': SNMP GET of a fixed scalar OID, or a bounded
-- GETBULK walk inside one fixed table subtree (WALK <table oid>). There is no SET row and no SET code path; the poller
-- refuses any OID that is not under a row below. Pilot vendors only (PO decision 4): Check Point (cp_gaia_gateway) and
-- Palo Alto (pan_firewall). Cisco ASA and FortiGate get rows when their network path is open.
--
-- Check Point memory: 1.3.6.1.4.1.2620.1.6.7.4.4.0 is memActiveReal64 (used memory) in CHECKPOINT-MIB; the design's
-- first label "memFreeReal64" was corrected by the design owner on 2026-11-24 (label only, same OID). Memory % =
-- memActiveReal64 / memTotalReal64. M1 checks the value against the device.
--
-- Idempotent (ON CONFLICT DO NOTHING). The same rows are in capabilities/gate_registry_fixture.yaml; SnmpMetricGateTest
-- keeps the two equal. Dry-run in BEGIN/ROLLBACK on the live database before deploy.
SELECT set_config('app.actor_fingerprint', 'migration:V269_snmp_v3_metric_read_gates', true);
SELECT set_config('app.action_id', 'snmp_v3_metric_read_gates_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields, source_document_pointer)
VALUES
    ('cp_snmp_sys_uptime', 'check_point', 'cp_gaia_gateway', 'not_applicable', 'SNMP_V3',
     'GET 1.3.6.1.2.1.1.3.0',
     'read', 'SIGNED_OFF', 3, 'one retry after a 3 s timeout', 'one poll per interval per device (default 300 s, at least 60 s); every 30 minutes after 3 misses', 'one SNMPv3 authPriv request in flight per device; at most 16 devices at a time; no session state kept',
     'noSuchObject, noSuchInstance, an error status or no answer: the value is UNKNOWN (stored as null), never 0; a lower value than the last poll marks a reboot', 'none',
     '["sysUpTime"]'::jsonb, 'docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 4 and section 11 item 6 (PO decision 2026-11-24)'),
    ('cp_snmp_proc_usage', 'check_point', 'cp_gaia_gateway', 'not_applicable', 'SNMP_V3',
     'GET 1.3.6.1.4.1.2620.1.6.7.2.4.0',
     'read', 'SIGNED_OFF', 3, 'one retry after a 3 s timeout', 'one poll per interval per device (default 300 s, at least 60 s); every 30 minutes after 3 misses', 'one SNMPv3 authPriv request in flight per device; at most 16 devices at a time; no session state kept',
     'noSuchObject, noSuchInstance, an error status or no answer: the value is UNKNOWN (stored as null), never 0', 'none',
     '["cpuPct"]'::jsonb, 'docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 4 and section 11 item 6 (PO decision 2026-11-24)'),
    ('cp_snmp_mem_total_real64', 'check_point', 'cp_gaia_gateway', 'not_applicable', 'SNMP_V3',
     'GET 1.3.6.1.4.1.2620.1.6.7.4.3.0',
     'read', 'SIGNED_OFF', 3, 'one retry after a 3 s timeout', 'one poll per interval per device (default 300 s, at least 60 s); every 30 minutes after 3 misses', 'one SNMPv3 authPriv request in flight per device; at most 16 devices at a time; no session state kept',
     'noSuchObject, noSuchInstance, an error status or no answer: the value is UNKNOWN (stored as null), never 0', 'none',
     '["memPct"]'::jsonb, 'docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 4 and section 11 item 6 (PO decision 2026-11-24)'),
    ('cp_snmp_mem_active_real64', 'check_point', 'cp_gaia_gateway', 'not_applicable', 'SNMP_V3',
     'GET 1.3.6.1.4.1.2620.1.6.7.4.4.0',
     'read', 'SIGNED_OFF', 3, 'one retry after a 3 s timeout', 'one poll per interval per device (default 300 s, at least 60 s); every 30 minutes after 3 misses', 'one SNMPv3 authPriv request in flight per device; at most 16 devices at a time; no session state kept',
     'noSuchObject, noSuchInstance, an error status or no answer: the value is UNKNOWN (stored as null), never 0; CHECKPOINT-MIB memActiveReal64 (used memory)', 'none',
     '["memPct"]'::jsonb, 'docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 4 and section 11 item 6 (PO decision 2026-11-24)'),
    ('cp_snmp_fw_num_conn', 'check_point', 'cp_gaia_gateway', 'not_applicable', 'SNMP_V3',
     'GET 1.3.6.1.4.1.2620.1.1.25.3.0',
     'read', 'SIGNED_OFF', 3, 'one retry after a 3 s timeout', 'one poll per interval per device (default 300 s, at least 60 s); every 30 minutes after 3 misses', 'one SNMPv3 authPriv request in flight per device; at most 16 devices at a time; no session state kept',
     'noSuchObject, noSuchInstance, an error status or no answer: the value is UNKNOWN (stored as null), never 0', 'none',
     '["sessions"]'::jsonb, 'docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 4 and section 11 item 6 (PO decision 2026-11-24)'),
    ('pan_snmp_sys_uptime', 'palo_alto', 'pan_firewall', 'not_applicable', 'SNMP_V3',
     'GET 1.3.6.1.2.1.1.3.0',
     'read', 'SIGNED_OFF', 3, 'one retry after a 3 s timeout', 'one poll per interval per device (default 300 s, at least 60 s); every 30 minutes after 3 misses', 'one SNMPv3 authPriv request in flight per device; at most 16 devices at a time; no session state kept',
     'noSuchObject, noSuchInstance, an error status or no answer: the value is UNKNOWN (stored as null), never 0; a lower value than the last poll marks a reboot', 'none',
     '["sysUpTime"]'::jsonb, 'docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 4 and section 11 item 6 (PO decision 2026-11-24)'),
    ('pan_snmp_hr_processor_load', 'palo_alto', 'pan_firewall', 'not_applicable', 'SNMP_V3',
     'WALK 1.3.6.1.2.1.25.3.3.1.2',
     'read', 'SIGNED_OFF', 3, 'one retry after a 3 s timeout', 'one poll per interval per device (default 300 s, at least 60 s); every 30 minutes after 3 misses', 'one SNMPv3 authPriv request in flight per device; at most 16 devices at a time; no session state kept; GETBULK inside the table subtree only, at most 8 requests of 16 repetitions per poll',
     'noSuchObject, noSuchInstance, an error status or no answer: the value is UNKNOWN (stored as null), never 0; an empty walk leaves CPU UNKNOWN', 'none',
     '["cpuPct", "cpuDataplanePct"]'::jsonb, 'docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 4 and section 11 item 6 (PO decision 2026-11-24)'),
    ('pan_snmp_hr_storage_table', 'palo_alto', 'pan_firewall', 'not_applicable', 'SNMP_V3',
     'WALK 1.3.6.1.2.1.25.2.3.1',
     'read', 'SIGNED_OFF', 3, 'one retry after a 3 s timeout', 'one poll per interval per device (default 300 s, at least 60 s); every 30 minutes after 3 misses', 'one SNMPv3 authPriv request in flight per device; at most 16 devices at a time; no session state kept; GETBULK inside the table subtree only, at most 8 requests of 16 repetitions per poll',
     'noSuchObject, noSuchInstance, an error status or no answer: the value is UNKNOWN (stored as null), never 0; no hrStorageRam row leaves memory UNKNOWN', 'none',
     '["memPct"]'::jsonb, 'docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 4 and section 11 item 6 (PO decision 2026-11-24)'),
    ('pan_snmp_session_active', 'palo_alto', 'pan_firewall', 'not_applicable', 'SNMP_V3',
     'GET 1.3.6.1.4.1.25461.2.1.2.3.3.0',
     'read', 'SIGNED_OFF', 3, 'one retry after a 3 s timeout', 'one poll per interval per device (default 300 s, at least 60 s); every 30 minutes after 3 misses', 'one SNMPv3 authPriv request in flight per device; at most 16 devices at a time; no session state kept',
     'noSuchObject, noSuchInstance, an error status or no answer: the value is UNKNOWN (stored as null), never 0', 'none',
     '["sessions"]'::jsonb, 'docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 4 and section 11 item 6 (PO decision 2026-11-24)')
ON CONFLICT (gate_id) DO NOTHING;
