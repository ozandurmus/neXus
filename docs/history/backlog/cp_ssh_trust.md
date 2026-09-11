# Replace CP AutoAddPolicy compatibility with trusted known_hosts/pinned host keys

status: real_env_validated · target: 0.6.4

0.6.4 REAL_ENV_VALIDATED 2026-08-27. R1 PASS compat mode; R3 PASS strict+no-trust raises CpSshStrictPreflightError before connect(). R2 (strict=on with provisioned known_hosts) deferred to DEPLOY.1 production server: requires real MDS host-key entry in server known_hosts before end-to-end read-only collection can be verified in strict mode. CORRECTION 2026-09-03 (OP.0b S8-P0.1, build op0b_s8_p01_cp_ssh_trust_preflight_correction): the shipped strict preflight gated on Paramiko's writable local store (get_host_keys) instead of the system store load_system_host_keys fills, so strict=on could never pass with a provisioned known_hosts -- R3 was genuinely correct, R2 was structurally impossible. Corrected through public Paramiko APIs with real-Paramiko regression tests; R2 remains owed (cp_ssh_trust_r2_prod_server).
