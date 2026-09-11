# native_backup_validation — Native Backup Validation

## summary

Validation battery V1 transport / V2 structural / V3 semantic. V3 cross-checks the artifact against unified.json (interface, vsys/VS, version, HA role).

## why

A hash proves a file arrived intact; it proves nothing about restorability. V3 catches truncated, partial and wrong-device artifacts that pass V1/V2 cleanly -- only possible because this platform already has a reconciled inventory to compare against. V4 RESTORE_PROVEN requires a real lab restore and is never inferred. docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md + docs/design/BACKUP_RECOVERY_CONTRACTS.md (design frozen 2026-08-30). AUTOMATED_VALIDATED 2026-08-30: utils/recovery_validation.py (validate_artifact V1-V3) + attach_restore_proof (the only path that can set V4/RESTORE_PROVEN, never automatic) + main.py --recovery-validate.
