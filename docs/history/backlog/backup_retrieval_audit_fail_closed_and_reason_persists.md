# Backup retrieval must be fail-closed on its audit row, and the reason and role must survive to execution

status: done · target: BACKUP_LINE_IMPLEMENTATION_AUDIT_2026_09_15.md Tier 1: OR-3, BK-12, BW-4

2026-09-22: BackupArtefactRetrieval writes the audit row before the plaintext (test refusesAndDoesNotCreatePlaintextWhenItsAuditCannotBeRecorded); the HTTP download path (PO decision record 2026-09-22) keeps the same order (BackupDownloadServiceTest). BK-12/BW-4 (reason and role persisted to the job row and re-checked at claim) remain open as their own concern.
