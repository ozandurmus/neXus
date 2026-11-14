# Fleet backups enabled: daily 02:00 Istanbul; 7-day PO retention, capacity verification pending

status: in_progress · target: docs/design/INCIDENT_2026_10_10_BACKUP_FREQUENCY_DISK_FULL.md

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Fleet backups enabled: daily 02:00 Istanbul; 7-day PO retention, capacity verification pending

Target (full): docs/design/INCIDENT_2026_10_10_BACKUP_FREQUENCY_DISK_FULL.md

2026-11-10 (plan refresh): development mode (PO 2026-11-03) turned every scheduled ui2-service task off, fleet backups included; only the system-log collection keeps its own switch. Revert through the manifest environment and NEXUS_SHIP_WINDOWS=1 when development mode ends.

2026-11-14 backlog truth: still open (P1, size S). Today: Backups run, but no disk headroom alert after the 10-10 disk-full incident. Evidence: PR 570 (02:00 daily); schedules back on 2026-11-10; backups run daily; no capacity/free-disk monitor in code

2026-11-14: retitled. Daily backups run (02:00); the open part is an alert before the host disk fills (the 2026-10-10 disk-full incident). HOST-A root file system 72% used on 2026-11-14; SystemStatusService only displays usable bytes, no threshold or alert. Pairs with vault_has_no_enforced_quota_on_a_shared_host.
