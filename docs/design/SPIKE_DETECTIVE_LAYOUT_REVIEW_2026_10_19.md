# Spike Detective layout review — run record (C4 + one C5, one gateway, 2026-10-19)

**Status: RECORD — STUB (the result section is filled from the stored summary after the run).** It records how the one
review listing that [DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) §8.3 asks for is run,
and, once run, what it showed. It decides nothing and authorizes no command beyond the PO approval below. Counts, sizes,
name classes and an age class only: no directory or file name, path, host name, address or raw line is written here.
Device names are aiview pseudonyms.

## 1. Approval and scope

- **PO approval 2026-10-19.** One run on one device: the Check Point gateway whose masked name is `FW-UNIFORM-02-M1`,
  chosen at submission by its opaque device id (never written in the repository), inside the next job window,
  read-only.
- **Commands** (both APPROVED 2026-10-17 and seeded as V160 gate rows; byte-identical in code and tested):
  - C4 `cp_syslog_collect_ls_spike`: `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/'` — 30 s, no
    retry, 256 KiB bound.
  - C5 `cp_syslog_collect_ls_spike_dir`: `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/<dir>/'` for
    ONE directory — 30 s, no retry, 64 KiB bound.
- **Not done:** no file is fetched, no content is read, nothing else runs on the device. Enabling
  `system_log.spike_data_enabled` (the next step if the layout matches §8.3) is a separate decision and not part of
  this run.

## 2. How the run works (job `cp_spike_layout_review`)

1. One trusted SSH session (the same strict transport as `cp_system_log_collect`). Both gate rows are resolved before
   the session opens and again right before each command; each command is ledgered as a job step and never replayed.
2. C4 is sent. Its output is parsed by shape (the login banner some gateways print first is counted and ignored).
   A missing directory is `ABSENT`; a timeout, channel failure or output at the bound is `UNKNOWN` and nothing else is
   sent.
3. The C5 directory is chosen in memory: among the entries of type `d` with link count 2 (§8.3), the newest whose
   mtime is at least 10 minutes older than the clock. Newer unsettled directories are skipped and counted. If that
   directory's name does not match `^[A-Za-z0-9._-]{1,128}$` (and is not `.`/`..`), no C5 is sent and the outcome is
   `NAME_REJECTED`. No data directory: `NO_DATA_DIRECTORY`; none settled: `NO_SETTLED_DIRECTORY`.
4. C5 is sent once for that name. A directory gone in between is `DIRECTORY_ABSENT`; an empty one is `REVIEWED` with
   0 members.
5. **Clock.** Neither approved command prints the device clock, and no other command may run, so settling and the age
   class use the neXus clock (`ageBasis: NEXUS_CLOCK`). The count of data directories whose mtime is ahead of that
   clock is recorded as a skew hint.
6. **Storage.** One `system_log_measurement` row, vendor `check_point`, source `cp_spike_layout_review` (V166 widens
   the source check; V166 also maps the job to the diagnostics module). Nothing is written to the artefact store and
   the exec runs with an output limit, so the transport keeps the command and answer out of its log lines and
   transcripts. The chosen name exists only in memory to build C5.
7. **One-shot.** One job per device per window (idempotency key `spike-layout-review:<device>:<slot>`), and a device
   that already has a `MEASURED` review row is refused (`SPIKE_LAYOUT_ALREADY_REVIEWED`). A failed (`UNKNOWN`) run
   may be resubmitted in a later window.

### Stored summary (schema `cp_spike_layout_review_v1`)

| Field | Meaning |
|---|---|
| `status` | `MEASURED` or `UNKNOWN` (with `reason`, e.g. `PARENT_TIMEOUT`, `DIRECTORY_OUTPUT_LIMIT_EXCEEDED`, `CONNECTION_FAILED`) |
| `outcome` | `REVIEWED`, `ABSENT`, `NO_DATA_DIRECTORY`, `NO_SETTLED_DIRECTORY`, `NAME_REJECTED`, `DIRECTORY_ABSENT`, `UNKNOWN` |
| `commandsSent` | 0, 1 or 2 |
| `parent` | C4: `dataDirectories`, `plainFiles` (and how many have link count 1), `otherDirectories` (type `d`, link count not 2), `linkCountTwoNotDirectory`, `otherEntries`, `dataDirectoriesUnsettled`, `dataDirectoriesMtimeAheadOfClock`, `ignoredLines`, `rejectedLines`, `exitStatus` |
| `directory` | the chosen directory: `nameClass`, `ageClass` (`10M_TO_1H`, `1H_TO_6H`, `6H_TO_24H`, `1D_TO_7D`, `7D_TO_30D`, `30D_OR_MORE`), `unsettledNewerSkipped`; after C5: `memberCount`, `files`, `directories`, `links`, `otherEntries`, `fileBytes`, `nameClasses` (per class `count`, `minBytes`, `maxBytes`, `bytes`), `contractForms` (`perfCpuLog`, `profilingSummaryElg`, `otherMembers`), `contractFormBytes`, `contractFormBytesWithinBound` (16 MiB, §3.4) |

Name classes use the closed vocabulary of the phase A numeric summaries: known stems stay, digit runs become
`N`/`NN`/`NNN`/`N+`/`YYYYMMDD`/`EPOCH`, every other word becomes `X` (for example `perf_cpu_N.log`,
`profiling_summary.elg`). `directory.nameClass` is the class of the chosen directory's name (a class, not the name).

## 3. How to submit and read it (as aiview, inside a job window)

```bash
# inside the service pod (docs/design/NEXUS_CLI.md "Where it runs"), logged in as aiview
/app/nexus-cli syslog-spike-review <DEVICE_ID>
/app/nexus-cli syslog-measurements <DEVICE_ID> vendor=check_point source=cp_spike_layout_review
```

The same calls over HTTP: `POST /api/v2/diagnostics/system-log-measurements/spike-layout-review` with body
`{"device_id": "<DEVICE_ID>"}` (action `system_log_measure_start`: security_admin or aiview, machine session included;
202 when admitted, 409 with a code when refused or outside a window), then
`GET /api/v2/diagnostics/system-log-measurements?device_id=<DEVICE_ID>&vendor=check_point&source=cp_spike_layout_review`
(masked for aiview). The job appears in the jobs list as "Check Point Spike Detective layout review".

## 4. Result

**PENDING — not run yet.** To be filled from the stored summary: job id (opaque), outcome, `commandsSent`, the parent
counts, the chosen directory's name class and age class, member count, the member name classes with count and size
min/max/sum, the contract-form counts, and whether the layout matches §8.3 (`MATCH` / `MISMATCH` / `NOT_EVALUABLE`).
