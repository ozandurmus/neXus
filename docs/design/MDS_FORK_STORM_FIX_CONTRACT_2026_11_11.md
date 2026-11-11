# MDS fork storm — fix contract (manual-only export, device hold, runtime ceiling, credential alert)

## Status

DRAFT — DO NOT IMPLEMENT the command changes until the Product Owner (PO) approves each literal below, through the
PO session. The PO approves literals, not this document. Schema changes in part B need the usual migration dry run
(BEGIN/ROLLBACK on the live database) before deploy. Incident record: `docs/design/INCIDENT_2026_11_11_MDS_FORK_STORM.md`.

The MDS stays out of backup (not a backup target, pending jobs cancelled) until normalization phase 1 and parts B, C
and D ship. After that the export runs **by hand only, under watch** — PO 2026-11-11 (verbatim): "İzlenen elle
çalışsın". It is not scheduled.

## Summary for the PO

| Part | What changes | New or changed device command? | Needs |
|---|---|---|---|
| A | How every Check Point command is started (separate contract) | Yes — see that contract | PO approval of literals |
| B | No schedule for the export; the device stays "busy" while `mds_backup` may still run; no second run after a lost lease | No | Migration |
| C | Export ceiling 4 h → 45 min; alert on ceiling, lost contact and OUTCOME_UNKNOWN | No | Manifest env change first |
| D | First credential failure raises one alert; two failures on different devices stop the rest of the run | No | Migration |
| E | Remote kill of a runaway `mds_backup` | Proposed only, not built | Separate decision |

Declined by the PO on 2026-11-11 and not part of this contract: the start literals L1 and L1b ("kabuk istemiyorum"),
the process-count read L5 and the profile read M1 ("kabul istemiyorum"), and a separate export schedule.

## A. Shell environment — in the normalization contract

How neXus starts every Check Point command (no login shell; `BASH_ENV` and `ENV` unset; the Check Point environment
loaded explicitly) is product-wide: `docs/design/CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md`. Its phase 1 is
the three MDS export rows (`mds_backup_start`, `mds_mdsstat`, `mds_cplic_print`).

## B. MDS export: manual only, device stays busy

1. **No schedule.** The export is never requested by a scheduler. `BackupScheduler.tick` stops calling
   `requestScheduledMdsExports`, and the MDS-export actor's window branch in the claim query is removed. A manual
   export, started by an authorised operator, is the only path. (This replaces the earlier proposal of a separate
   switch and a 03:30 window.)
2. **Who may start it.** The manual start stays where it is today (operator role, audited); the screen and the CLI say
   that the run is to be watched.
3. **Device busy hold.** Today neXus starts no job on a device that has a running job, but only while the neXus job
   lives. New: a `device_remote_hold` row (device, job, reason, opened time) is written **before** the start command is
   sent. It is removed when the run reads `mds_backup.rc`, or when the start command is refused cleanly (non-zero exit,
   not a timeout). If the job ends without reading the exit code (lost contact, ceiling, cancel, lost lease), the hold
   **stays** — remote state is UNKNOWN — until a security_admin releases it with a reason (audited), after checking the
   device. It never expires by itself. The job claim query skips any device with an active hold, for every job type.
4. **Gaia backup and export never together.** Already true while both are neXus jobs; item 3 extends it to the time
   `mds_backup` may still run on the device.
5. **Eligibility is no longer used for scheduling.** With no schedule, `MdsExportEligibility` has no caller; it is
   removed. If a schedule is ever brought back, eligibility must require a manual export completed **after** the
   device's latest SSH host-key enrolment, not any export in the device record's history.
6. **No second run after a lost lease.** The export marks its point of no return (`markBoundaryCrossed`) before it
   sends the start command, so a lost lease ends the job OUTCOME_UNKNOWN instead of requeuing it (frozen 14H BK-5
   semantics; today `BackupJobExecutor` never marks it).
7. **Stale work directories.** Each export records its work directory. The next export removes recorded directories
   of earlier runs whose hold has been released, with the existing approved literal `rm -rf %1$s %1$s.tgz` (gate row
   `mds_remove_workdir`), one directory at a time — no new command.

## C. Runtime ceiling and alerts (no new device command)

1. `UI2_MDS_EXPORT_RUN_DEADLINE_SECONDS` default 14400 → **2700** (45 min: five times the usual 8-9 min, 1.6 times
   the longest measured 28 min). Changed in the worker manifest first, then the code default. It bounds the wait for
   `mds_backup.rc` only; bundling (1 h), digest (10 min) and fetch (2 h) keep their own timeouts.
2. **Lost contact.** Three polls in a row that cannot connect or authenticate raise an alert "device stopped answering
   during an MDS export" at once, while the run keeps polling until the ceiling.
3. **Ceiling reached.** The job ends **OUTCOME_UNKNOWN** (unchanged state; `mds_backup` may still be running and may
   still finish), raises an alert, and keeps the B.3 hold. OUTCOME_UNKNOWN from an export alerts the same way FAILED
   does.
4. **Alert path.** An alert is an in-product item on the Overview and the Backups screen (visible without any channel),
   an audit event, and an entry in the `backup_failure` route when that route is on. On 2026-11-11 all routes, SMTP and
   syslog are off. Recommended as a gate (PO decision): unattended exports are re-enabled only once one channel
   (SMTP or syslog) is on.
5. **Cancellation stops polling.** The poll loop ends when the worker thread is interrupted (a cancelled job); today
   the interrupted `sleep` returns at once and the loop would poll without pause until the ceiling.

## D. Credential failure alert

1. On the first job that ends with an SSH or API authentication failure for a credential reference, neXus opens one
   `credential_failure_alert` (credential reference, first and last failure time, failed job count). It is shown on
   the Overview and the device and Backups screens, audited, and sent through the `backup_failure` route when on.
   Further failures update the same alert; the next success with that credential closes it.
2. **Stop early on authentication failures.** The scheduled run is not serialised behind a full canary backup (that
   would push later devices out of the one-hour window). Instead, once two **different** devices using the same
   credential reference fail authentication in the same run, every still-queued job for that credential reference in
   that run is cancelled as `credential_auth_failed_hold` (one alert, not 56 failures). A single device's failure
   alone does not hold the fleet (it may be a device-local account problem). This also avoids sending dozens of failed
   logins at once, which can lock an account or trip intrusion detection.
3. Authentication failure becomes a structured result (`ConnectFailed` with an authentication flag) in
   `BackupCapabilityExecutor`, `MdsExportExecutor` and `CheckPointSnapshotExecutor`, instead of text in a reason
   string.
4. No new device command.

## E. Remote kill — proposed only, not built

Killing a runaway `mds_backup` is a device write (class 2, operational-state change). Not built. The PO declined
recording the session id (L1b), so a future kill would have to find the session another way; it would need its own
gate entry, contract and PO approval, operator-triggered only (never automatic). Until then the alerts tell a human
to act, as on 2026-11-11.

## F. Acceptance run before the PO re-enables the MDS

Before the run: the PO checks free space in `/var/log` and removes leftover `/var/log/nexus-mds-*` directories from
10-15, 10-16 and 11-11 (by hand, on the device). After normalization phase 1 and parts B, C and D ship: one
manual export on the MDS, outside 00/02/06/12/18, with the PO watching `sar -q` or the task count. Pass: task count
within 10% of the pre-start value for the whole run, `mds_backup.rc` = 0, bundle fetched and verified. Stop rule: task
count above 6,000 → the PO kills `mds_backup` and its session leader by hand, as on 2026-11-11. After a pass the PO may
set the MDS back as a backup target; every later export is still started by hand and watched.

## G. Tests

- Literal tests and the architecture test: see the normalization contract, section 5.
- `MdsExportExecutor`: lost-contact alert after three failed polls; OUTCOME_UNKNOWN plus alert at the ceiling with
  the hold kept; hold written before the start command and removed on reading the exit code or a clean refusal;
  polling stops on interrupt; boundary marked before the start command.
- Claim query: a held device gets no job; release clears the hold.
- Scheduler: no code path requests an MDS export.
- Credential alert: first failure opens one alert; success closes it; two distinct device failures hold the rest.
- Lanes before ship: Java lane (`jt.sh`) and `tools/tests`.

## Cross-references

- `docs/design/INCIDENT_2026_11_11_MDS_FORK_STORM.md`
- `docs/design/CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md`
- TAC/R&D report (PO session, shared document): https://claude.ai/artifact/AJJhAPf8QEhvmjwoPC5udR
- `docs/design/ARGON_MDS_FORK_STORM_REVIEW_2026_11_11.md`
- `docs/AI_DEVELOPMENT_PROTOCOL.md`
- `docs/design/VENDOR_BACKUP_CONTRACTS_2026_09_22.md`
