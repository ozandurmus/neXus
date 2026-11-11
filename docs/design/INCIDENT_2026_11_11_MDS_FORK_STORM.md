# MDS fork storm caused by the neXus MDS export — postmortem (2026-10-15, 2026-10-16, 2026-11-11)

## Status

RECORDED — incident evidence and lessons. It is not a command contract and approves no command. The fixes are
proposed in `docs/design/CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md` (how neXus starts every Check Point
command) and `docs/design/MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md` (MDS export schedule, ceiling, alerts); both
DRAFT, command literals pending Product Owner approval. The report to Check Point TAC and R&D is the PO session's
shared document "R82 Multi-Domain Server fork storm during mds_backup — incident report for Check Point TAC and R&D"
(https://claude.ai/artifact/AJJhAPf8QEhvmjwoPC5udR); this record uses its measurements.

Sources: device-side facts come from commands the Product Owner (PO) ran on the device on 2026-11-11 between 02:00
and 02:45 and relayed by the PO session; job and audit facts come from read-only queries of the neXus database
(counts, times and states only); code facts come from the repository at `4b90a12c`. Nothing was run on any device
for this record. Device names, addresses and serials are left out; "the MDS" is the Check Point Multi-Domain Server
(R82 Jumbo Hotfix take 127, 48 CPUs, neXus role `management_server`). All times are Europe/Istanbul.

PO instruction (2026-11-11, verbatim): "mds'i iptal edelim. Buradan çıkarılacak ders için çalışalım".

## Summary in plain words

neXus backs up the MDS by starting Check Point's own `mds_backup` script over SSH. It starts that script inside a
**login shell** (`bash -lc`). A login shell reads the account's start-up files. On the MDS, the stock start-up file
for root (`/root/.bash_profile`, shipped by the operating-system package, last changed in 2008) sets a variable
called `BASH_ENV`. That variable tells **every later bash script** to re-read the shell start-up files before it
runs. Check Point's start-up files themselves run small bash helper scripts. With `BASH_ENV` set, each helper
re-reads the start-up files, which run the helper again, which re-reads them again — a loop that never ends.

The result is a "fork storm": the server creates new processes as fast as it can (measured at about 2,850 per
second) until it runs out of process slots. The management Java services then cannot create threads, crash, and
cannot be restarted. That is what happened on 2026-10-15 and 2026-10-16. On 2026-11-11 the PO reproduced it on
purpose, watched it, and stopped it by killing the `mds_backup` process and the shell that neXus had started. The
MDS survived.

In October the crashes were wrongly blamed on the other nightly job, the Gaia backup (`add backup local`).

## Timeline

| When | What happened | Source |
|---|---|---|
| 2026-09-23 | MDS export added as a second backup type (`cp_mds_export`, V61). The start command wraps `mds_backup` in `bash -lc` so the Check Point environment variables (`$CPMDIR` and others) are loaded. | repo `481430e0` |
| 09-24 to 10-10 00:00 | 19 MDS exports complete on the **previous** MDS unit, usually in 7-9 minutes (longest 28). No storm is recorded. | jobs table |
| 10-10 to 10-12 | neXus worker stopped for the disk incident (`INCIDENT_2026_10_10_BACKUP_FREQUENCY_DISK_FULL.md`); queued exports cancelled. | jobs table |
| 10-10 to 10-13 | The MDS is **replaced** by a new unit (PO: "cihaz değiştirdik"). Same software version (R82). | PO, audit log |
| 10-13, 10-14 02:00 | Exports fail before connecting: the new unit's SSH host key does not match. The key is re-enrolled on 10-14 with PO approval. The device's observed model string changes on 10-14 12:00; its software version does not. | jobs table, audit log |
| **10-15 02:00** | First export on the new unit. The Gaia backup completes in about 4.5 minutes. The export job is claimed **twice** (lease epoch 2): the first attempt's lease was lost (cause not recorded), and the second attempt starts the export again from the beginning; its SSH connect fails with an authentication failure at 02:28, while the MDS is refusing logins. The MDS crashes around 02:25-02:30: process count 3.78k at 02:00, 10.2k at 02:10, 38.4k at 02:20; fork rate about 2.8k/s; management Java processes fail with "OutOfMemoryError: Failed to create a thread, errno 11"; the Check Point watchdog cannot restart CPM, FWM and SOLR; SSH logins are refused. | jobs table; sanitized CPSDC export (2026-10-16 notes) |
| **10-16 02:00** | Same pattern and crash. The export ends OUTCOME_UNKNOWN at 06:15, when its 4-hour deadline runs out. | jobs table |
| 10-16 | **Misattribution.** The crash is blamed on the Gaia backup. The note of the day says "`mds_backup` ran only on 10-16"; the jobs table shows it also ran on 10-15. MDS collection paused; MDS no longer a backup target; Gaia backup on management servers refused in code (`UI2_CP_MANAGEMENT_GAIA_BACKUP`). | audit log, memory note |
| 10-18 | MDS re-enabled for every collection except backup. | audit log |
| 11-03 | The Check Point backup credential's password expires (reported by the PO session). From 11-03 to 11-10 scheduled jobs are switched off for development, so **no Check Point backup runs** from 11-04 to 11-10 and nothing notices the expiry. | PO session; jobs table |
| 11-10 20:50 | PO re-enables MDS backups for a watched debug night in the 02:00 window. | audit log |
| 11-11 02:00-02:04 | The scheduled backup run fails on authentication for **56 gateways and the MDS (Gaia backup and export)** — the expired password. | jobs table |
| 02:08-02:31 | After the credential is fixed, 58 gateway backups complete. | jobs table |
| 02:14-02:19 | MDS Gaia backup, started by hand, runs **alone** and completes. Task count stays normal (about 3.9k). | jobs table, PO |
| **02:23** | MDS export started by hand. Within about 10 minutes the task count (`ps -eLf`, which counts threads) goes from about 3.9k to 11k, 13k, 20k and 24k; `is_scalable_platform.sh` processes alone number 11,909, then 15,276, all sleeping; `sar -w 5 3` averages 2,849 new processes per second, the same rate as the October nights. | PO, TAC report |
| 02:24 | A second MDS Gaia backup is queued. It never starts: neXus does not start a job on a device that already has a running job. | jobs table |
| ~02:30-02:40 | The PO inspects the process tree (below). Killing the leaf processes with `pkill` does not stop the storm. Killing the `mds_backup` (csh) process and the `bash -lc` session leader (its session id) does. The MDS survives. | PO |
| 02:43 | Export and queued backup cancelled; the MDS is no longer a backup target (PO-approved, audited). It stays so until the fix ships and the PO re-enables it. | audit log |

## Root cause

Two things together cause the storm.

1. **What neXus does.** The export starts
   `cd /var/log && setsid nohup bash -lc '$CPMDIR/scripts/mds_backup -b -l -d <dir> > <dir>/mds_backup.log 2>&1; echo $? > <dir>/mds_backup.rc' </dev/null >/dev/null 2>&1 &`
   (`MdsExportPlan.MDS_BACKUP_START`, gate row `mds_backup_start`, V62). `bash -lc` is a login shell. It reads
   `/root/.bash_profile`, the stock file from the operating-system package `rootfiles` (cp738000002, modified 2008),
   which exports `BASH_ENV=$HOME/.bashrc`. `mds_backup` and **everything it starts** inherit that variable.

2. **What the device does with it.** A non-interactive bash script reads the file named by `BASH_ENV` before it
   runs. So every bash helper started under `mds_backup` re-reads `/root/.bashrc` → `/etc/bashrc` (line 52 sources
   `/etc/profile.d/*.sh`) → `/etc/profile.d/CP.sh` → `/opt/CPshrd-R82/tmp/.CPprofile.sh` →
   `cpprofile_functions.sh` and `MDSprofile.sh`. On the replacement MDS, something reached from this chain starts
   the bash helper `is_scalable_platform.sh` again (the storm processes were `/bin/bash /bin/is_scalable_platform.sh`,
   each the child of another copy), and that helper re-reads the chain again. Nothing stops the recursion. Which file
   makes the call is not known (below). Lines seen on the device: `/root/.bashrc:11` sources `/etc/bashrc`;
   `/etc/bashrc:52` loops over `/etc/profile.d/*.sh`; `/etc/profile.d/CP.sh:2` sources
   `/opt/CPshrd-R82/tmp/.CPprofile.sh`; `.CPprofile.sh:50` sources `/opt/CPmds-R82/scripts/MDSprofile.sh`;
   `/etc/profile:90` runs `/usr/bin/is_scalable_platform.sh mho`.

**Evidence (2026-11-11, PO on the device):** thousands of `/bin/is_scalable_platform.sh` processes, each one's
parent another copy of the same script, all in state S (waiting), all with `BASH_ENV=/root/.bashrc` in their
environment; a later wave of `cpprod_util` processes; 2,850 forks/s; a task count (threads) of 3.9k → 24k in about 10 minutes
after 02:23. The Gaia backup alone at 02:14 caused nothing. Killing only the leaves did not help (the tree regrows);
killing the root of the tree — `mds_backup` and the neXus-started shell — did.

### What is not yet known

- **The exact last link of the loop** — which file in the chain starts the next helper — was not pinned on the device.
  The only call to `is_scalable_platform.sh` found is `/etc/profile:90`, and nothing found on the `.bashrc` path
  sources `/etc/profile`. The TAC report asks Check Point for the missing link.
- **Why the same command did not storm 19 times on the previous unit.** The command text has not changed since
  2026-09-24. The previous unit and the replacement report the same software version, but the replacement may
  differ in Jumbo Hotfix take, installation type or start-up scripts. Unknown.
- **Why the shorter reads in the same export did not storm.** The export runs `bash -lc 'mdsstat'` and
  `bash -lc 'cplic print -x'` just before `mds_backup`, and neXus runs `bash -lc` reads on gateways every day,
  without any recorded storm. Possible reasons: those tools are not bash scripts; or `mds_backup` (a csh script)
  changes an environment variable that the Check Point start-up chain uses as an "already loaded" guard, so the
  guard fails in its children. Unknown.

### Why removing `BASH_ENV` breaks the loop whatever the last link is

A non-interactive bash script reads **no** start-up file unless `BASH_ENV` names one. Without it, a helper script
cannot re-read the chain, so it cannot start itself again. This holds whichever file closes the loop. The one case
it would not cover is a Check Point script inside `mds_backup` that itself starts a new login shell (which would set
`BASH_ENV` again for its own children); the acceptance test in the fix contract is designed to show that.

## Contributing factors

1. **A correlation was taken for the cause (October).** The Gaia backup ran on both crash nights, so it was blamed.
   The export also ran on both nights; the jobs table showed it, but the note of the day said otherwise and the
   jobs table was not checked for every job that touched the MDS in the crash window.
2. **The device replacement was not treated as a change.** The new host key was re-enrolled, but the heavy jobs
   were not re-validated on the new unit. The first export on the new unit was an unattended 02:00 run.
3. **A login shell was chosen for convenience.** `bash -lc` was adopted to load the Check Point environment. It is
   now used by 80 gate rows (77 gateway, 3 MDS) and by several command builders. Its side effect — exporting whatever
   the device's root start-up files export — was never assessed.
4. **The runtime ceiling is far above the measured run time.** The export waits up to 4 hours for `mds_backup.rc`
   (`UI2_MDS_EXPORT_RUN_DEADLINE_SECONDS=14400`). Measured runs take 7-9 minutes, 28 at most. During a storm the
   polls fail quietly and the job keeps waiting; on 10-16 it took 4 hours to give up, and it raised no alert.
5. **Serialisation covers the neXus job, not the device.** neXus already refuses to start a second job on a device
   that has a running job (job claim query, `JooqJobLeaseDao`). But `mds_backup` runs detached on the device. When
   the neXus job ends early (10-15: failed at 02:28 while the device-side process was probably still running), the
   device counts as free again. The guard holds only while the neXus job lives.
6. **No alert channel is switched on.** On 2026-11-11 the live settings have SMTP off, syslog off, and the
   `backup_failure` and `job_failure` routes off. A failed export or a failed credential reaches nobody unless
   someone opens the screen.
7. **A broken credential was found only by the next backup.** The password expired on 11-03; schedules were off
   until 11-10; the first job to use the credential was the 02:00 run, which failed 57 jobs at once. neXus has no
   early credential check and no first-failure alert.
8. **Nobody was watching the device during an unattended heavy job.** neXus does not read the device's process count
   during an export (the code has a TODO for it), so it cannot see a storm starting.
9. **An earlier warning was worked around, not investigated.** On 2026-11-06/07 on the same replacement MDS,
   `bash -lc 'cpinfo -y all'` answered nothing within 60 seconds. The read was moved to the interactive shell (V195,
   `InventoryReadPlan.java:87-90`) and the hang was not explained. It may have been the same mechanism; that is
   UNKNOWN, but it was a signal five days before the reproduction.
10. **Eligibility for unattended exports survived the device replacement.** Scheduled exports run only on an MDS
   that has completed at least one manual export (`MdsExportEligibility.measuredTargets`). The check looks at the
   device record's whole history, so the old unit's September runs qualified the replacement unit.
11. **A lost lease re-runs the export from the start.** On 10-15 the job was claimed a second time and began the
   export again. The backup job executor never marks the point after which a device action has been sent
   (`markBoundaryCrossed` is not called in `BackupJobExecutor`), so a lost lease is treated as "nothing sent yet" and
   requeued (code reading, to be confirmed in the fix). Had the second connect succeeded, a second `mds_backup` could
   have started while the first was still running.
12. **Work directories are left behind.** An export that does not finish cleanly keeps its `/var/log/nexus-mds-<job>`
   directory on the MDS by design, and nothing removes it later. Directories from 10-15, 10-16 and 11-11 may still
   be there.
13. **A ship resets live settings.** A ship deploy re-applies the manifest's environment values, so an emergency
   switch set only on the live service (for example to turn the export off) can be undone by the next ship.

## Lessons

1. **Before naming a cause, list every job that touched the device in the window** — from the jobs table, not from
   memory or notes. A job that "always runs" is the first suspect, not the last.
2. **The shell neXus opens on a device is an execution environment neXus owns.** Start it with the smallest
   environment that works: no login shell, no inherited `BASH_ENV`, the vendor environment loaded explicitly, through
   one shared wrapper. The device's stock start-up files are inputs we do not control, and a major upgrade (the fleet
   is moving to R82, whose gateways ship the same root start-up files) can change them under us.
3. **A replaced device is a new device.** After a replacement (new host key, new model string), run each heavy job
   once under watch before it runs unattended.
4. **Every remote job needs a ceiling close to its measured run time, and the ceiling must alert.** A ceiling 30
   times the normal run time is no ceiling.
5. **"One job per device" must cover what keeps running on the device after the neXus job ends**, not only the
   neXus job.
6. **Alerting must be on before unattended jobs are.** Without a channel, a failure is only a row in a table.
7. **A credential is checked before it is needed**, and its first failure is an alert, not the first of many
   silent failures.
8. **Emergency switches go in the manifest first**, so a ship cannot undo them.
9. **An unexplained hang is a finding.** A workaround that makes a symptom go away is recorded with the open
   question, and the question is followed up.
10. **A lost lease must never repeat a device action.** Mark the point of no return before the action is sent; after
   it, a lost lease ends in OUTCOME_UNKNOWN, never in a second run.

## Current state (2026-11-11)

The MDS is not a backup target; its pending jobs are cancelled (PO-approved, audited). It stays so until the fix
ships and the PO re-enables it. After the fix the export is not scheduled: it runs by hand, under watch (PO
2026-11-11: "İzlenen elle çalışsın"). Other MDS collections (inventory, policy, discovery) continue.

## External review

Argon reviewed an earlier draft of this record and of the fix contract
(`docs/design/ARGON_MDS_FORK_STORM_REVIEW_2026_11_11.md`, saved unaltered; verdict "SHIP DOCS AFTER FIXES").
Checked against the code and the database before use:
- Confirmed and applied here: the 10-15 job was claimed twice (lease epoch 2); `markBoundaryCrossed` is never called
  in `BackupJobExecutor`; `MdsExportEligibility` accepts the old unit's history; the 2026-11-06/07 `cpinfo` hang;
  work directories are never cleaned up; the root-cause wording overstated which file calls the helper.
- Applied in the contracts: interactive login shells (P0, normalization literal S1), the
  hold written before the start and never self-expiring, OUTCOME_UNKNOWN kept at the ceiling, the poll loop on
  interrupt, the credential stop rule, the failover phase split. The separate claim window became moot when the PO
  chose no schedule; the recommendation to make L1b mandatory was declined by the PO.
- Not taken: the review quotes a sentence from this record about holding the MDS ("When an export does not finish
  cleanly, neXus holds the MDS ...") that this record never contained. Its preference for keeping the login shell was
  superseded by the PO's normalization decision of 2026-11-11.
- A second Argon review covers the product-wide command normalization
  (`docs/design/ARGON_CP_SHELL_NORMALIZATION_REVIEW_2026_11_11.md`, verdict REWORK on the full literal list). As a
  result only the three MDS export literals are put to the PO now; the rest waits for a lab check.

## Cross-references

- `docs/design/CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md`
- `docs/design/MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md`
- TAC/R&D report (PO session, shared document): https://claude.ai/artifact/AJJhAPf8QEhvmjwoPC5udR
- `docs/design/ARGON_MDS_FORK_STORM_REVIEW_2026_11_11.md`
- `docs/design/INCIDENT_2026_10_10_BACKUP_FREQUENCY_DISK_FULL.md`
- `docs/design/VENDOR_BACKUP_CONTRACTS_2026_09_22.md`
