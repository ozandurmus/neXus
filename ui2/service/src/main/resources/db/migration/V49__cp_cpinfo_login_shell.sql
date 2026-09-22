-- V49 -- cp_identity_cpinfo_hotfixes runs in a login shell. Measured live on
-- 2026-09-22 across 62 gateways: over the bare exec channel `cpinfo -y all`
-- answered exit 0 with no output (cpinfo is on PATH only once the login
-- profile sources the Check Point environment), so the canonical key becomes
-- the bash -lc form -- the same form the gated vsenv reads already use.
SELECT set_config('app.actor_fingerprint', 'migration:V49_cp_cpinfo_login_shell', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

UPDATE gate_registry
   SET canonical_command_key = 'bash -lc ''cpinfo -y all''',
       unsupported_behavior_ref = 'a device with no jumbo line reports no hotfix level, which is a result, not an error; over the bare exec channel cpinfo is not on PATH (exit 0, empty), hence the login shell'
 WHERE gate_id = 'cp_identity_cpinfo_hotfixes';
