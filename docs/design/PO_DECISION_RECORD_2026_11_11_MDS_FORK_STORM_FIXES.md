# PO decision record - 2026-11-11: MDS fork storm fixes (phase 1 literals, export ceiling, alerts, credential checks)

**Status: RATIFIED - PO DECISION (PO session, 2026-11-11 morning).** Answers to the questions raised by the 2026-11-11
MDS fork storm (`INCIDENT_2026_11_11_MDS_FORK_STORM.md`) and its two draft contracts
(`CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md`, `MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md`). This record, not
the drafts, is the approval for the items below; everything the drafts propose beyond them stays unapproved.

## PO answers (verbatim, with translation)

1. Phase 1 of the Check Point shell normalization, the three MDS export literals: "Üçünü onayla" ("Approve all three").
2. Lab read M2: approved in scope (shell type, presence of `vsenv`/`mdsenv`, lengths of four Check Point variables,
   names of the files that define `vsenv`; no values). The exact literal and target scope go to the PO before any run.
3. MDS export run-time ceiling: "45 dk + alarm" ("45 minutes plus an alarm"), replacing 4 hours.
4. Notification channels: syslog and the `backup_failure` route on; SMTP stays off.
5. Credential: alert on the first failure and a nightly canary authentication check: "İkisi birden" ("Both").
   The canary is read-only authentication over the existing login path; a new device command needs its own approval.

Earlier the same day: the MDS export runs by hand only, under watch, never scheduled: "İzlenen elle çalışsın"
("Let it run by hand, watched").

## 1. Approved literals (gate rows rewritten by V206)

| Gate id | Literal |
|---|---|
| `mds_backup_start` | `unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cd /var/log && setsid nohup bash --noprofile --norc -c "$CPMDIR/scripts/mds_backup -b -l -d %1$s > %1$s/mds_backup.log 2>&1; echo \$? > %1$s/mds_backup.rc" </dev/null >/dev/null 2>&1 &` |
| `mds_mdsstat` | `unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mdsstat > %s/mdsstat.txt 2>&1` |
| `mds_cplic_print` | `unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cplic print -x > %s/cplic.txt 2>&1` |

`%s` / `%1$s` is the run's own work directory, `/var/log/nexus-mds-<job token>`. No other gate row changes.

## 2. Boundaries

- The MDS stays out of backups and schedules. The first export after deploy is started by hand while the PO watches;
  no agent starts it.
- The other 137 rows of the normalization inventory stay as they are until M2 has run and phase 2 is redesigned,
  reviewed and approved literal by literal.
- The remote kill of a runaway `mds_backup` is not approved (fix contract part E).

## Amendment 2026-11-18: the MDS export is scheduled again

**Status: RATIFIED - PO DECISION (PO session, 2026-11-18).** This amendment replaces the 2026-11-11 boundary "the MDS
export runs by hand only, never scheduled" and the first bullet of section 2; everything else in this record stands.

- Evidence: the first watched manual export after the fixes ran on 2026-11-18 between 01:25 and 01:30 local and completed
  in about 5 minutes, with no growth in the process count on the MDS. Check Point TAC confirmed the root cause of the
  2026-11-11 incident: `bash -lc` with `BASH_ENV` exported (the fork storm), which phase 1 removed.
- PO answer (verbatim), to "put the MDS export back on the schedule": "al" ("take it").
- Schedule rule: once per night per management server (Check Point, `role` management_server, `backup_target`,
  enrolled, not disabled), inside the existing backup window (02:00-02:59 Europe/Istanbul; no new window). It is
  requested from 02:30, and only on a night whose fleet backup run was claimed; a server is due only while it has no
  non-terminal job of any type (TAC: serialise the Gaia backup and `mds_backup` on one server), and not if any
  `cp_mds_export` job was already submitted for it that local day (whoever started it). A failed or unknown export is
  never retried that night.
- Safeties kept unchanged: the 45-minute ceiling, the point-of-no-return marking, the OUTCOME_UNKNOWN/FAILED alert route
  (`backup_failure` and syslog), no retry, the three approved gate literals.
- Switch: `UI2_CP_MDS_EXPORT_SCHEDULE` in `deploy/ui2/50-service-deployment.yaml` ("true"); the code default is off when
  the variable is unset. Set it to "false" and roll the service to stop the schedule; manual exports are unaffected.
- Code: `BackupScheduler.mdsExportTick` (02:30 rule), `MdsExportEligibility.dueTargets` (serialisation and once-a-night),
  `BackupCollectService.requestScheduledMdsExport` (actor `system:mds-export-scheduler`, key per device and day).
