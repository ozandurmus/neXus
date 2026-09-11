# restore_readiness — Restore Readiness

## summary

Per-device READY / STALE / PARTIAL / UNPROTECTED / UNKNOWN readiness over already-collected evidence, plus the Recovery UI module and coverage-vs-inventory gap view. The Recovery UI module half (RB.5) is contracted as CON.4, rendering in both the exported report and the console from one shared payload.

## why

Answers 'if this device died right now, what do we actually have?' with zero new device commands, zero credentials and no network. Buildable immediately, depends on nothing later in the sequence, and quantifies the BackBox-exit risk with real numbers instead of assumption -- which is why it is pulled forward from 0.9.x. docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md + docs/design/BACKUP_RECOVERY_CONTRACTS.md (design frozen 2026-08-30). RB.0 landed 2026-08-30: utils/restore_readiness.py + main.py --restore-readiness-check. RB.5 (Recovery UI module) is contracted 2026-08-31 as docs/history/phase/CON_4_CONSOLE_RECOVERY_MODULE.md.
