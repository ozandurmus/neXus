# Backup size anomaly and the "last good" retention pin

Status: IMPLEMENTED (lane `lane/backup-size-anomaly`, migration V180). PO decision recorded below.

## PO decision record (2026-10-30)

Context: a gateway's nightly Gaia backup jumped from about 1 GB to about 29 GB (local firewall logs were swept into
`add backup local`). The 7-day rule (`RetentionPruningService` / `JooqRetentionQueries`, by `created_at`) then kept
seven identical bloated copies and pruned every good one.

Product Owner, 2026-10-30 (Turkish, intent): in addition to the 7-day rule, a jump such as 1 GB -> 26 GB must be
called an anomaly, and the last 1 GB backup must not be deleted, in case we need to go back. It is to be used for
anomaly detection such as excessive growth. Holding seven faulty backups gives us nothing.

Decided:

1. Detection at ingest. A new standard backup is a **size anomaly** when its plaintext size is
   - at least **5x** the median of the device's last **3** non-anomalous live standard backups and at least
     **1 GiB** larger (growth), or
   - at most **20%** of that median and at least **1 GiB** smaller (shrink, possibly truncated).
   Fewer than 3 earlier backups: no anomaly. Thresholds live in one class, `BackupSizeAnomalyRule`.
2. Protection. While a device has an unresolved size anomaly its **last good** backup is never pruned, whatever its
   age.
3. Anomalous copies. Only the **newest** anomalous backup per device is kept (for investigation); older anomalous
   copies are pruned even inside the retention days.
4. Resolution. A `security_admin` may **accept new size**, with a reason (UI action on the deviation card and the CLI
   `backup-size-accept <deviceId> <reason>`). The anomaly becomes the new normal, the pin is released and the age rule
   resumes. Nothing releases it automatically.

## Design

- **Record.** `backup_deviation_record` (V30, never written before) gains class `size_anomaly` and the resolution
  columns `resolved_at`, `resolved_by`, `resolution_reason` (V180). `artefact_id` is the anomalous backup,
  `previous_artefact_id` the last good one. `summary` and `details` carry sizes, median, ratio and the thresholds only;
  no hostname, address or serial. One record per anomalous artefact (unique partial index).
- **Detection.** `BackupJobExecutor` calls `JooqBackupSizeAnomalyRepository.inspect` right after the manifest row is
  recorded (best effort: a failed check never fails a stored backup). Only `artefact_class = backup` with
  `backup_type = standard` is checked; snapshots have their own depth rule. History excludes removed artefacts, open
  anomalies, and anything older than an accepted anomaly (an accepted size restarts the history, so the old size no
  longer counts). A per-device advisory lock serialises concurrent ingests.
- **Pin: derived, not a flag, and not the baseline.** `backup_baseline` is the operator's chosen reference point for
  Compare, one per device; overwriting it to pin the last good backup would silently destroy that choice, and clearing
  it on acceptance would need to remember the old value. An explicit `pinned` column on `backup_artefact` would be a
  second state to keep in step with the anomaly. Instead the pin is the unresolved record itself: retention excludes
  every `previous_artefact_id` of an unresolved `size_anomaly` row, and the device's newest unresolved anomalous
  copy (`JooqRetentionQueries.NOT_SIZE_ANOMALY_KEPT`). Resolving the rows is therefore the one and only release.
  The baseline exclusion stays as it was.
- **Pruning older copies.** `RetentionPruningService` step 0 reads `supersededSizeAnomalies()` (open anomalous copies
  with a newer open anomalous copy of the same device; never the baseline or a pinned last good) and removes them
  through the existing tombstone path before the age rule reads its list. They count in `backupsPruned`. The
  sole-remaining-backup guard still applies.
- **Card.** `GET /api/v2/backups/deviations` adds `size_anomalies` (one row per device, the newest open record and
  `open_copies`), `can_accept_size` and `size_role_note`. The "Active major deviations" figure counts devices with a
  major configuration change or an open size anomaly; the list under it shows the sizes, the ratio and the action.
- **Accept.** `POST /api/v2/backups/deviations/size-anomalies/{deviceId}/accept` with `{reason}`; route action
  `backup_size_anomaly_accept` (role `security_admin`, audited); the replay viewer is refused. The table gains
  `trg_audit_backup_deviation_record`, so detection and resolution are audit rows with actor and action id;
  `resolution_reason` is redacted from the audit image (tier 2 free text) and kept on the row.

## Not covered

- No backfill: anomalies are detected for backups ingested after V180. An estate that already lost its good backups
  (all copies bloated) has nothing to pin; its next backups compare against the bloated ones and are not anomalous.
- Configuration archives and snapshots are not checked.
