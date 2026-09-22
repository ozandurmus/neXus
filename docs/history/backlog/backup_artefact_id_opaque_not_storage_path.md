# The artefact id returned over HTTP must be opaque, not the storage path

status: done · target: BACKUP_LINE_IMPLEMENTATION_AUDIT_2026_09_15.md Tier 1: BK-14, OR-1

2026-09-22: verified live -- all 128 backup_artefact rows carry a UUID artefact_id, none equal recovery_volume_path; BackupControllerTest#deviceBackupsReturnsOpaqueSummaries holds it.
