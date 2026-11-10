# Fleet backups enabled: daily 02:00 Istanbul; 7-day PO retention, capacity verification pending

status: in_progress · target: docs/design/INCIDENT_2026_10_10_BACKUP_FREQUENCY_DISK_FULL.md

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Fleet backups enabled: daily 02:00 Istanbul; 7-day PO retention, capacity verification pending

Target (full): docs/design/INCIDENT_2026_10_10_BACKUP_FREQUENCY_DISK_FULL.md

2026-11-10 (plan refresh): development mode (PO 2026-11-03) turned every scheduled ui2-service task off, fleet backups included; only the system-log collection keeps its own switch. Revert through the manifest environment and NEXUS_SHIP_WINDOWS=1 when development mode ends.
