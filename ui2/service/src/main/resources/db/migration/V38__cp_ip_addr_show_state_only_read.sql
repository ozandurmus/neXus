-- V38 -- adds the Check Point interface-state-only read.
--
-- V37 moved the interface/address read to "fw getifs", which carries no up/down state column at
-- all. cphaprob -a if's own "Interface Name: Status:" table (parsed since the same movement) only
-- lists a "Required interfaces" subset, not every configured interface -- measured live by the
-- Product Owner on 2026-09-21 against a real cluster (13 interfaces in a VS, only 7 in that
-- table). "ip -4 addr show" is Check Point's own interface report and still carries a per-
-- interface up/down flag for every interface, unaffected by the address-scoping bug V37 fixed
-- (that bug was specific to the value fw getifs/ip addr show print as the address on a VSX
-- cluster member; the flag is a kernel-level link/admin property, unrelated to it). It is issued
-- a second time, on both a physical member and each virtual system, and only its state field is
-- ever read -- never its address.
--
-- Additive only, per V15/V36/V37's own rule: no existing row is touched.
SELECT set_config('app.actor_fingerprint', 'migration:V38_cp_ip_addr_show_state_only_read', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_inventory_ip_addr_show_state_only', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'ip -4 addr show', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'no interfaces configured is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 18 (2026-09-21 amendment)'),
    ('cp_inventory_ip_addr_show_state_only_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && ip -4 addr show''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per run', 'one session per device per run',
     'no interfaces configured is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 19 (2026-09-21 amendment)'),
    ('cp_inventory_vsid_ip_addr_show_state_only', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv <VSID> && ip -4 addr show''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per virtual system per run', 'one session per device per run, VS0 never re-entered',
     'no interfaces configured is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 20 (2026-09-21 amendment)');
