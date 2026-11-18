# Verify the first scheduled MDS export (02:30) and the first ASA saved-state relations (V250 ***** mask assumption) after the 2026-11-19 nightly backup

status: planned · target: Due 2026-11-19 morning; PR b86b93ae, 1fa4e9f1

2026-11-18: MDS export is admissible again and scheduled nightly at 02:30 (b86b93ae; first scheduled run 2026-11-19, not yet verified). ASA saved state relation V250 (1fa4e9f1) is computed in the backup; it awaits the first nightly backup to confirm that the masked secret placeholder (*****) assumption holds. Check both in the morning of 2026-11-19 with read-only queries; ASA saved state must not read UNKNOWN only because of the mask.
