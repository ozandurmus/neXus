# RB.3a — RB.3a - CP Gaia backup/snapshot attestation (show backups / show snapshots)

## Summary

Implemented the frozen read-only CP Gaia attestation contract: checkpoint/checkpoint_recovery_attestation.py runs exactly ('show backups','show snapshots') per physical endpoint through a literal-tuple pre-wire guard and a bounded fail-closed parser that emits {class, age_days, source} with the artifact name discarded and age_days nullable, a sibling utils.recovery_collect.run_recovery_attestation + RecoveryAttester protocol (no plaintext, no recovery-store write), data/state/recovery_attestations.json, and main.py --recovery-attest plus an attestation read in --restore-readiness-check. VSX virtual systems are never contacted and stay UNPROTECTED; Spark/Gaia Embedded is UNSUPPORTED with zero commands; 'recovery-attest-cp' is not allowlisted for scheduling; design-doc amendments C1/C2 landed and the stale --recovery-vendor help was corrected.

## Evidence

33 new tests (tests/test_rb3a_recovery_attestation.py, AC-1..AC-10) green; full suite 804 passed / 20 skipped / 2 failed - the 2 are the pre-existing unrelated test-order-pollution pair, pass in isolation, zero new failures. Privacy gate: no finding in tracked source. Full detail in project/backlog.json native_backup.

## Risks forward

Real-environment validation is owed and NOT satisfied here - no live CP device is reachable; tests exercise fixtures only. Per AGENTS.md this build reaches AUTOMATED_VALIDATED, never DONE. Parser format drift across Gaia releases is mitigated fail-closed (unrecognised format -> no attestation, never a wrong age); real-env will likely add a format. An attestation is weak evidence and never promotes above PARTIAL; a VSX-heavy estate will read worse after this build (hosts move to PARTIAL, VS entities stay UNPROTECTED) - that is the honest reading (A3). A8 platform gating depends on cp_config_telemetry.json from a prior --cp-config-collect run; absent, every endpoint is treated as supported/unknown and attested normally.
