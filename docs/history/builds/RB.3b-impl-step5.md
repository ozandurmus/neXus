# RB.3b-impl-step5 — RB.3b implementation step 5 + C6 - CP Gaia backup device core

## Summary

checkpoint_recovery_collector.collect() now runs the one-SSH-session device-touching sequence inside run_under_admission: ledger read, §7.7 free-space read, add backup local (no retry), SFTP fetch into memory (no temp file, B6), size verify, write_artifact before the §7.8 delete of exactly the created name, then ledger.record_execution iff add backup local was sent. New BackupSshSession transport wrapper and frozen Clish/Expert command tuples (Expert primary per the §7.7/§7.8 sign-off notes); RecoveryCollectionSkipped / 'skipped' status added to utils/recovery_collect.py, excluded from failed_count.

## Evidence

24 new tests (tests/test_rb3b_cp_backup_device_core.py: AC-1..AC-6, AC-12, AC-14, C6, §9.13 (a)(b)(c)(f)(g)). Full suite 875 passed / 25 skipped / 2 failed (documented pre-existing test-order pollution, unrelated). No main.py change (step 6).

## Risks forward

Device core is exercised only against fixture SSH/SCP transports; the mandatory watched real R81.10/R81.20 gateway run (which also confirms the §7.7/§7.8 command-string and add backup local output-format on hardware) remains owed. Steps 6-7 remain before RB.3b can leave in_progress.
