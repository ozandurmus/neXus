-- V37 -- corrects the Check Point interface/address read.
--
-- V15's cp_inventory_ip_addr_show_v4/v6 (and their vsenv0/vsid-composite
-- siblings) registered "ip -details -4 addr show" / "ip -6 addr show" as
-- the interface/address read, on both a physical member and, chained with
-- "vsenv <VSID> &&", each virtual system.
--
-- Product Owner measured live (2026-09-21, cp_vsx_interfaces_identical_to_
-- physical): after two prior fixes to how the per-VSID reads were shelled
-- out, VS-1 and VS-2 on the same physical device still returned the same
-- repeating address sequence from the same base. Root cause: chaining
-- multiple "vsenv <vsid> ...; <read>;" segments for DIFFERENT VSIDs inside
-- one shared bash -lc process does not reliably re-scope every later
-- "ip addr show" read to its own VSID. The Product Owner's own manual
-- reproduction on the live fleet, issuing "fw getifs" (bare on a physical
-- member, "vsenv <VSID> && fw getifs" on a virtual system), returned
-- correct, distinct interface/address data every time -- including for
-- the exact physical member and VSIDs sampled live.
--
-- "fw getifs" is Check Point's own interface/address report and is not
-- affected by this failure mode. It carries no up/down state column, so
-- device_interface.state is recorded as 'unknown' for any interface parsed
-- from it, rather than guessed (UNKNOWN/fail-closed law) -- a known,
-- accepted narrowing versus the old "ip addr show" read, tracked in the
-- same backlog item.
--
-- This migration is additive only, per V15/V36's own rule (no existing row
-- is touched): the superseded ip-addr-show rows stay exactly as SIGNED_OFF
-- history of what was actually run at the time, and three new rows are
-- added for the corrected command family. The application code,
-- capability-registry/cp_inventory_collect.yaml and
-- gate_registry_fixture.yaml move to "fw getifs" in the same commit as
-- this migration; nothing will look up the old canonical_command_key again
-- after that ships, but its row is kept rather than deleted.
SELECT set_config('app.actor_fingerprint', 'migration:V37_correct_cp_interface_read_command', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_inventory_fw_getifs', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'fw getifs', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'one session per device per run', 'no interfaces configured is a result, not an error', 'none',
     '[]'::jsonb, 'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 15, corrected (V37)'),
    ('cp_inventory_fw_getifs_vsenv0', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv 0 && fw getifs''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per device per run', 'one session per device per run',
     'no interfaces configured is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 16, corrected (V37)'),
    ('cp_inventory_vsid_fw_getifs_and_route', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''vsenv <VSID> && fw getifs && ip -4 route show''', 'read', 'SIGNED_OFF', 30, 'none',
     'once per virtual system per run', 'one session per device per run, VS0 never re-entered',
     'an address-less/route-less virtual system is a result, not an error', 'none', '[]'::jsonb,
     'docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md entry 17, corrected (V37)');
