# RB.3b-impl-steps-2-4 — RB.3b implementation steps 2-4 - operational-write ledger + CP backup offline gates

## Summary

Built the device-free layer of the CP Gaia backup collector against the frozen contract. New utils/recovery_operational_ledger.py plus a fifth utils/evidence_backend.py concern (filesystem + Postgres, INSERT-only, fail-closed on an unreadable ledger). checkpoint/checkpoint_recovery_collector.py replaces the D3-blocked stub with the pilot allowlist (B10), the D4 fail-closed backup-credential guard (B11, no fallback to CP_CONFIG_SSH_*), the platform / VSX / software_version gates (B7/B8/§3 rule 5), and the §7.7 /var/log free-space parser + 3x threshold. Steps 5-7 (device core, main.py wiring + C6, state) still owed.

## Evidence

Full suite 851 passed / 25 skipped / 2 failed - the two documented pre-existing test-order-pollution failures, both pass in isolation, zero regressions. New: tests/test_rb3b_operational_ledger.py, tests/test_rb3b_cp_backup_collector.py.

## Risks forward

Step 5 (add backup local / SCP fetch / digest verify / delete) is the production-firewall-risk part, scoped for Sonnet 5 extended thinking. §7.7/§7.8 literal Clish strings remain confirm-on-hardware. Status stays in_progress - not IMPLEMENTED - until AC-1..AC-14 are green and the watched real R81.10/R81.20 run has happened.
