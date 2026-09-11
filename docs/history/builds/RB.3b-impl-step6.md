# RB.3b-impl-step6 — RB.3b implementation step 6 - main.py wiring

## Summary

main.py's --recovery-collect --recovery-vendor checkpoint branch now builds and binds the operational-write ledger, recovery store paths/vault key, the cp_config_telemetry.json platform map and a prior-backup-size lookup from the recovery store into CheckpointGaiaBackupCollector, instead of constructing it bare (which failed closed at the D4 credential guard with nothing else wired). A __vsid_ target in --recovery-gateways is now rejected with a clean parser.error before admission (belt-and-suspenders alongside the collector's own precheck()); the CLI summary gained an explicit skipped-outcome count.

## Evidence

2 new CLI-integration tests in tests/test_rb2_recovery_collect.py (VSX pre-admission reject; full constructor-kwargs wiring). Full suite 879 passed / 23 skipped / 2 failed (same two pre-existing, unrelated, order-dependent failures). Privacy gate PASS/0.

## Risks forward

Step 7 (project metadata + CURRENT_STATE.md trim) and the mandatory watched real R81.10/R81.20 gateway run both remain before RB.3b can leave in_progress; a normal run with no operator configuration still touches no device (SECURITYEXPERT_CP_BACKUP_SSH_* and a non-empty SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES are both required and unset by default).
