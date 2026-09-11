# backup_recovery_architecture — Backup & Recovery architecture + frozen contracts (RB.0-RB.6; rebase of original 0.6.0B)

## Summary

User request: design the backup architecture and freeze its contracts ahead of the BackBox non-renewal in 2027. Two design documents, no code: docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md (three-plane model, per-vendor analysis grounded in CP/PAN admin+API guides, the new 'operational-write' command class, the four-level validation model, restore-readiness, phasing RB.0-RB.6, seven open decisions) and docs/design/BACKUP_RECOVERY_CONTRACTS.md (storage layout, manifest, validation result, readiness record, recovery_ui payload, ten/fourteen-point command gate entries, retention, twelve security invariants as test obligations). Status is 'planned' deliberately -- design frozen, nothing implemented.

## Evidence

- **vendor_grounding**: Check Point: sk108902 (snapshot = root partition + OS + binaries + hotfixes, >=2.5GB, same-machine restore; system backup = config + networking/OS params, EXCLUDES OS/binaries/hotfixes, not cross-version), R81 Installation and Upgrade Guide (migrate_server export, mds_backup, Management HA must be captured simultaneously -> consistency groups). Palo Alto: device-state export is the RMA-grade artifact (carries certificates + LSVPN satellite auth) and requires SUPERUSER on PAN-OS 7.1+; configuration XML alone is NOT RMA-grade; Panorama-generated partial device state lacks the dynamic information. URLs cited in the architecture doc's Sources section.
- **automated**: py -m pytest -q: 640 passed, 3 skipped, 2 failed -- unchanged from the pre-existing baseline; the project/*.json edits do not disturb the project_plan payload assertions. Repository privacy gate: PASS / 0.
- **no_code**: Design + contracts + project metadata only. No source file, no test, no device command implemented. Every command in contracts section 7 is an explicit DRAFT FOR GATE REVIEW, not an approval.
- **real_env**: n/a for this movement -- nothing network-facing was built.

## Risks forward

D1 (product owner) blocks the BackBox-replacement premise itself: vendor scope is frozen to CP+PAN, so any other vendor BackBox currently backs up is NOT covered by this product. D2 (security lead): PAN device-state export needs superuser -- a privilege increase of the platform's own service account. D3/D6: 'add backup local' writes to the device's /var/log and needs the new operational-write class plus the P0 cp_device_interaction_safety audit, making RB.3 the schedule risk against the 2027 deadline. D7: without a restore-proof lab nothing ever leaves RESTORE_UNPROVEN.
