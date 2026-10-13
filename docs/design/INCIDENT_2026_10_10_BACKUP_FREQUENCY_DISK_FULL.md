# Backup frequency caused disk exhaustion — 2026-10-10

Status: RECORDED — incident evidence, not an implementation or command contract.
Source: Product Owner incident facts supplied in the state-docs-oct brief
(2026-10-14); scheduling fix corroborated by repository history/source at
`c29079fa`. No host, database or device was contacted for this record.

## Trigger and impact

PR #564 coupled scheduled fleet backups to every device-job window:
00:00 / 06:00 / 12:00 / 18:00 Europe/Istanbul. Full backups therefore ran
four times daily, producing approximately **222 GB/day**. The neXus development
host's disk reached **97%** utilization; kubelet DiskPressure evicted the
in-cluster registry.

The worker was stopped from **2026-10-10 01:25 to 2026-10-12 09:30
Europe/Istanbul**. There was **no device polling and no backups** during that
interval. Stored observations from before the outage do not prove runtime
health or backup coverage during it.

## Recovery and design deviation

With explicit PO approval, **457 same-day duplicate backups**, approximately
**244 GB**, were purged manually. Their manifest rows were deleted and the
removals were ledgered as `removed`. This was an emergency deviation from the
normal pruner design, which retains manifest rows and records removal in the
retention ledger. This incident is not permission to repeat manual deletion.
With PO approval, **2,449 stale REQUESTED jobs** were cancelled.

## Corrective change

PR #570 separates scheduled full backups from polling windows: full fleet
backups run **once daily at 02:00 Europe/Istanbul**, with admission during
02:00–02:59. Daily MDS export is retained alongside Gaia backups. Missed-day
occurrences expire rather than creating a catch-up backlog. Repository evidence:
`e09b1401`, `47f08170`, `c29079fa`;
`ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/ScheduledBackupWindowPolicy.java`
and `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/device/backup/BackupScheduler.java`.
The PO set backup retention to **7 days on 2026-10-12**; this is a supplied
policy decision, not a fresh database-policy verification.

## Lessons and affected scheduling scope

Every scheduling change must list each affected job type, frequency before/after,
expected bytes per run/day, retention footprint and free-disk headroom. Polling
frequency is not automatically an appropriate backup frequency.
The current scheduled full-backup vocabulary is:
`cp_gateway_backup`, `cp_spark_sftp_backup`, `pan_device_state_backup`,
`fgt_config_backup`, `asa_config_backup`, `https_vendor_backup`,
`rdw_cc_config_backup`; daily management export uses `cp_mds_export`.
These are the affected backup scheduling classes, not a claim that every class
produced artifacts during the incident. Device polling and readiness scheduling
must be assessed separately when shared windows change.

Watch disk utilization and growth, kubelet DiskPressure, registry availability,
backup output volume and queued-job age. Seven-day retention alone is not a
capacity proof: measure actual retained bytes and preserve headroom for builds,
registry layers and the database. Engineering-owner follow-up is capacity and
stored-policy verification; this documentation lane performs no cleanup or
operational validation.
