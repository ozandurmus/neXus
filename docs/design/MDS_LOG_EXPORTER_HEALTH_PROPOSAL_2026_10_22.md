# Management server (MDS) Log Exporter health detection — design proposal

**Status: DRAFT for PO approval (2026-10-22).** Nothing here is implementation authority. Every command in section 5 is
NEW and is not approved until the Product Owner approves its exact literal (`AGENTS.md`, "Network action taxonomy", PO
amendment 2026-09-26). Docs only: no code, no migration, no gate row is added by this document. Vendor output shapes
that this document could not verify are marked `UNKNOWN` (vendor semantics law) and are resolved by one PO-run
measurement (section 9, step M0) before any freeze.

Inputs read for this proposal: [DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) (D3, 2.3,
3.1, 4.3, 10.1, 12), [SYSTEM_HEALTH_SCREEN_2026_10_20.md](SYSTEM_HEALTH_SCREEN_2026_10_20.md),
[PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md](PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md),
[CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md](CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md) (`mdsenv` precedent),
[CP_PLATFORM_IDENTITY_MEASUREMENTS.md](CP_PLATFORM_IDENTITY_MEASUREMENTS.md) (`cpinfo -y all`), the change-feed code
(`ChangeFeedService`, `CpwdEvents`) and the gate registry conventions (`gate_registry_fixture.yaml`, V160).
`CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md` was **not found** on `origin` (no `lane/cp-failover-one-shell` branch); this
proposal therefore applies the written session rule of Phase B (D6: one trusted SSH session per device per run, commands
strictly sequential) and the PO's one-session rule as stated in the task. Section 4 lists what changes if that document
says more.

## 1. The incident this answers (masked)

On a Check Point multi-domain management server (Gaia R82, Jumbo Hotfix (JHF) Take 127 installed on 10 October) every Log
Exporter target (17 targets over 5 domains plus the MDS level, to a SIEM over UDP and to a firewall-policy tool over TCP)
stopped for 12 days without any alarm.

| Link | What happened | Where it was visible on the device |
|---|---|---|
| 1 | The JHF changed the log file format (version byte `0x0a`) | not visible by itself |
| 2 | The GA exporter binary rejected every record as malformed | each target's `log_indexer.elg`: `CBinaryLogFile::ReadLog: failed to find FileIdFormat`, `unrecognized Log File Version: 0xa` |
| 3 | The fixed exporter build (AutoUpdate Take 70, sk182866) failed to install every 3 hours: `Installation flow failed ... Missing backup file for uninstallation`, because stale registry entries (written by the `mds_restored` script on 10 October) pointed to a missing rollback path | `/opt/CPInstLog/AutoUpdater.log` |
| 4 | Status said `Running` with `last log read at: N/A`, or `Not running` | `cp_log_export status` |

The fix was manual (snapshot, registry cleanup, install, re-export, backfill). neXus saw nothing, because MDS system-log
collection is off (Phase B D3, 2.3) and no source covers the exporter. Items 1, 2 and 4 of section 5 read independent evidence of the
same failure, so one unreadable source does not hide it; whether each would have fired in the first window after the JHF
install can only be shown by the M0 measurement (section 9), not claimed here.

## 2. Scope

In scope: read-only detection on `cp_multi_domain_server` devices, run only inside the 00/06/12/18 Europe/Istanbul job
windows (`JobWindowPolicy`, staggered inside the window as Phase B 3.1). Standalone management servers and log servers also
run Log Exporter (without domain contexts); whether to include them is Q6.

Out of scope (all, no exception):

- Any write: no `cp_log_export` start/stop/restart/reexport/set, no `autoupdatercli` install/download/check-for-update, no
  `mdsstop`/`mdsstart`, no `cpstop`/`cpstart`, no registry access (the stale registry entries of link 3 are never read or
  changed by neXus; the AutoUpdater log is the only evidence used for that link).
- Repairing anything. The screen says what is wrong; remediation is a human act.
- Reading exporter targets' configuration (`cp_log_export show`, `targets/*/conf`). Those carry destination addresses and
  protocol settings and are not needed to decide health.
- Reading, storing or exporting the raw lines of the exporter logs. Counters, state words and closed-vocabulary classes
  only (raw-evidence law). This is not a new member of the system-log programme (section 6).
- Traffic/audit log content; Log Exporter's own output.
- Gateways (they do not run Log Exporter).

## 3. Overall shape

One scheduled job per MDS per window, capability `cp_mds_exporter_health_collect` (new; module `diagnostics`), switched by
a new setting `mds_exporter_health.enabled` (default `false`, `security_admin`, with the same confirm step as
`spike_data_enabled`). It is deliberately **separate** from `system_log.include_management_servers`: that setting guards raw
log retention on the MDS and stays off (the crash-investigation hold of D3). This job keeps nothing raw.

One trusted SSH session per MDS per window, reused for every step, commands strictly sequential and never concurrent:

1. `E0` device clock, `E1` MDS-level `cp_log_export status`, `E2` MDS-level exporter log stat.
2. For each domain server (list = the stored domain list from the existing policy/discovery inventory; no new command), in
   a fixed order: `E3` `mdsenv <DS>&& cp_log_export status`, `E4` `mdsenv <DS>&& ` exporter log stat.
3. `E5` ranged tail reads (SFTP, no shell command) of each exporter's `log_indexer.elg` named by E2/E4.
4. `V1` JHF level, `V2` AutoUpdater view, `V3` exporter build.
5. `L1` AutoUpdater log stat, `L2` ranged tail read of `AutoUpdater.log`.
6. Disconnect.

`mdsenv` follows the proven rule of the discovery gate entries: it alters the calling shell, so it is always on the same
command line as the command it scopes (`mdsenv <DS>&& <command>`); a separate line would silently run against the wrong
domain. The domain identifier is quoted as one POSIX argument by the existing helper (`ManagementShellCommands`), never
string-concatenated.

Per MDS in a typical run with 5 domains: 2 x (1 + 5) = 12 exec commands, up to 18 small SFTP tail reads, 3 version commands,
1 stat, 1 log read. Every output is bounded (section 5). Total bytes read from the device are bounded at 8 MiB per run
(expected under 6 MiB: 18 x 256 KiB + 512 KiB), which is the Phase B per-read cap used as the run's ceiling, not as a target.

Wall-clock budget per MDS job: 10 min. A step that times out is `UNKNOWN` for its fact; the run continues with the next
independent step; no retry (`retry_rule: none`; the next window is the retry), exactly the Phase B rule.

Manual start: administrators follow the 2026-10-20 record (not window-gated; business-hours confirmation applies);
operator and aiview stay window-gated. Scheduled starts stay in the windows. Nothing here changes either rule.

Interaction with other jobs on the same MDS in the window: this job takes its own admission and its own SSH session, and
the MDS policy collection may already hold up to four sessions (policy parallel-sessions decision, 2026-10-02). The stagger
`hash(device_id) mod 40` is reused; Q7 asks whether the PO wants this job to run first or last within the window.

## 4. Session rule

The PO's one-session rule is applied literally: one shell session per device per window. The MDS-level steps and the
per-domain steps share that session; domain switching is done by `mdsenv <DS>&&` on each command line, never by leaving the
session in a domain context. If the one-shell document (not on `origin`) forbids more than one command per shell
invocation, the E3/E4 pair can be merged into one literal per domain (status, a separator marker, then stat); this
changes only the literal strings, not the facts, and would need the PO's re-approval of the merged literal.

## 5. The five detections

Conventions for the tables: all rows are action class `read` (class 0), vendor `check_point`, `platform_role_scope =
cp_multi_domain_server` (new rows; a row for a scope that already has an identical string is still a new row, because the
purpose is new and `max_frequency`/`secret_output_risk` differ, per the Phase B 10.3 precedent), shell context `expert`
(the validated login shell), transport `SSH_EXEC` unless stated. `<EXPORTERDIR>` is the Log Exporter base path. Whether the
environment variable `$EXPORTERDIR` is defined in an `mdsenv` context on this platform is `UNKNOWN` (M0); until measured the
literals below use it as the proposed spelling. Output shape for every Check Point `cp_log_export` / `autoupdatercli` /
`cpvinfo` command is `UNKNOWN` beyond the words quoted from the incident (M0 provides one sanitized sample of each); the
parser is written to those samples only, and an output it cannot parse is `UNKNOWN`, never "healthy".

### 5.1 Exporter liveness

Why: link 4. A `Running` exporter with `last log read at: N/A` had read nothing.

| Item | Value |
|---|---|
| E0 | `bash -lc 'date +%s'` — device clock, one line; bound 4 KiB; 30 s. (Same idea as C1's `date +%s`; may be folded into E2 as in C1 if the PO prefers one fewer command.) |
| E1 NEW | `bash -lc 'cp_log_export status'` — MDS level |
| E3 NEW | `bash -lc 'mdsenv <DS>&& cp_log_export status'` — one per domain server; `<DS>` = a domain identifier already stored by the existing discovery, safe form `^[A-Za-z0-9._-]{1,128}$`, quoted by the existing helper |
| Expected output | per exporter target: a name, a state word (`Running` / `Not running`), and a "last log read at" value that is a time or `N/A`. Exact layout, time format and time zone: `UNKNOWN` (M0). Only the target name, state, and last-read time/`N/A` are extracted; every other field is discarded in memory. |
| Output bound | 64 KiB each (a target line is under 200 B; 17 targets are under 4 KiB) |
| Timeout / retry | 60 s / none |
| Frequency | once per context per window (<= 4/day each); manual per the admin rule |
| Session rule | the run's one SSH session, sequential |
| unsupported_behavior | command not found, a non-zero exit with no parsable rows, or an `mdsenv` failure = `UNKNOWN` for that context (never "no exporters"). A parsable output with zero targets is `NO_EXPORTERS` (informational, not red). |
| secret_output_risk | low; destination addresses may appear; not extracted and not stored; the raw output is discarded after parsing |
| safe_telemetry_fields | `["contextClass", "targets", "running", "notRunning", "lastReadNa", "oldestReadAgeS"]` |

Rule (per exporter target, evaluated by neXus on the device clock of E0, never on neXus's own clock):

- `Not running` -> CRITICAL, fact `EXPORTER_NOT_RUNNING`.
- `Running` and last read `N/A` -> CRITICAL, fact `EXPORTER_NEVER_READ`.
- `Running` and `now_device - last_read` greater than the threshold -> CRITICAL, fact `EXPORTER_READ_STALE_<hours>H`. The
  threshold default is 12 hours (two consecutive windows missed); it is the one documented default, overridable by
  `ui2.mds-exporter-health.last-read-threshold-hours`, and the value in force is shown. Q2.
- `Running` and a recent read -> NORMAL for this fact.
- Unparsable or command failure -> UNKNOWN.
- A quiet domain with no log traffic could show an old "last log read": that is shown as red on purpose (the reader sees
  the age and the evidence); a per-target "expected idle" acknowledgement is a neXus-side flag, not a device write (Q3).

### 5.2 Exporter decode errors

Why: link 2. This is the earliest and most specific signature: it names the format problem even when status words are
ambiguous.

Two steps: a stat that yields absolute paths and sizes, then bounded tail reads.

| Item | Value |
|---|---|
| E2 NEW (MDS level) | `bash -lc 'stat -c "%n %i %s %Y" <EXPORTERDIR>/targets/*/log/log_indexer.elg'` |
| E4 NEW (per domain) | `bash -lc 'mdsenv <DS>&& stat -c "%n %i %s %Y" <EXPORTERDIR>/targets/*/log/log_indexer.elg'` (`<EXPORTERDIR>` is expanded by the shell after `mdsenv`; the printed `%n` is the expanded absolute path) |
| Expected output | one line per exporter log: `/abs/path inode size mtime`; an unmatched glob prints an error line and means `NO_EXPORTER_LOG` for that context (not a failure). Leading non-data lines are ignored by shape (Phase B 4.2 rule; 25 of 53 gateways printed one). |
| Output bound | 64 KiB; timeout 30 s; retry none; frequency once per context per window |
| unsupported | a missing file for a target that `cp_log_export status` listed = fact `EXPORTER_LOG_MISSING` ATTENTION; for an unlisted one nothing |
| secret_output_risk | none beyond path names (target names are in the path): the path is kept as an HMAC plus a closed class, masked for aiview |
| safe_telemetry_fields | `["files", "sizeBytes", "mtime"]` |
| E5 NEW (SFTP ranged read, no shell command) | read-only SFTP open of one path printed by E2/E4 in the same run; read `[max(0, size - 262144), size)`; close. `pos` and `cap` are computed by neXus from the stat, never operator input. Basename must equal exactly `log_indexer.elg` and the directory must be a `targets/<name>/log` directory printed by the stat. Bound 256 KiB per exporter; timeout 60 s; retry none; once per exporter per window. |

Counting (in memory, then discarded): after dropping a partial first line, count lines matching
`failed to find FileIdFormat`, `malformed`, and `unrecognized Log File Version: 0x<hex>` (the version token is kept as a
class: the hex value only when it matches `^0x[0-9a-f]{1,4}$`). Also record the newest matching line's own timestamp when
the elg header time parses (`[DD Mon HH:MM:SS]`, Phase B 4.7 form; the form for `log_indexer.elg` is `UNKNOWN` until M0),
and the share of tail lines that matched.

Rule: matches whose newest timestamp is within the lookback (default 24 hours on the device clock) -> CRITICAL, fact
`EXPORTER_DECODE_ERRORS_<n>`; matches present but no parsable time -> ATTENTION, fact `EXPORTER_DECODE_ERRORS_AGE_UNKNOWN`
(the tail can hold old, fixed errors, so an unparsable age is not red); no matches -> NORMAL; unreadable or file changed during
read -> UNKNOWN. A stat `mtime` older than the lookback with the target `Running` is shown as ATTENTION
`EXPORTER_LOG_SILENT` (the indexer wrote nothing).

Prefer the existing file-read path. The Phase B ranged read (C2: `CpSyslogRangedRead`, `DeviceTransport.fetchBounded`, the
"path was printed by a gated prior step" rule, which gives the transfer gate resolution `NOT_APPLICABLE` under C4 2.3) is
reused as the transport primitive. Two things are not reusable and are stated rather than assumed:

1. **Purpose approval.** The PO's approval of C2 "covers this one purpose (system-log incremental reads) and extends to no
   other source or capability" (Phase B 4.3). Reading `log_indexer.elg` and `AutoUpdater.log` is a different purpose, so
   E5 and L2 need their own PO approval, and the C4 2.3 amendment text must name them. (Q1.)
2. **Storage and cursor.** C2 is built around the cursor/anchor/chunk/artefact path (raw retention under the exception of
   D5). This detector needs none of that: it reads the tail, counts and discards. It therefore uses the transport and the
   range-spec types but not `system_log_cursor`, `system_log_chunk` or the artefact store.

Alternative (recorded, not recommended now): add `log_indexer.elg` per target and `AutoUpdater.log` as new system-log
sources (stored, redacted, incremental, with gap accounting) and run the same detectors over the stored chunks, as the
change feed does for `cpwd.elg` (`CpwdEvents`). That gives history and exact "since last run" counts, but it needs MDS
system-log collection enabled (the D3 hold), MDS-scoped C1/C2 rows, a contract amendment for two more source keys with
dynamic family names, and raw retention of lines whose content is unmeasured. Section 6 compares.

### 5.3 Version drift

Why: link 1 and link 3. The log format changed with the JHF; the fix is an exporter build released as an AutoUpdate take.
Whether a given JHF take requires a given exporter take is vendor compatibility knowledge that is **not on the device**.
This detector therefore reports facts and their order; it does not claim a compatibility matrix (vendor semantics law).

| Item | Value |
|---|---|
| V1 NEW (MDS scope) | `bash -lc 'cpinfo -y all'` — byte-identical to the approved gateway row `cp_identity_cpinfo_hotfixes`, which the PO ran by hand on an R81.20 management server on 2026-09-22 (`CP_PLATFORM_IDENTITY_MEASUREMENTS.md`). The existing parser (`CheckPointPlatformFactsParser.jumboTake`) is reused. Output bound 256 KiB; 60 s; retry none; once per MDS per window; sensitive output none. Alternatives the PO may prefer: `bash -lc 'installed_jumbo_take'` (shorter output, shape `UNKNOWN`). If the inventory already stores `device_platform_facts.hotfix_level` for the MDS, no V1 is issued and the stored value is used (the table keeps only the latest value; section 7 adds the history). |
| V2 NEW | `bash -lc 'autoupdatercli show logExporter -v'` — the installed take, the available take, and the state of the Log Exporter component (downloaded / installed / failed words). Shape `UNKNOWN` (M0). Bound 64 KiB; 60 s; retry none; once per MDS per window. It is a read verb of the AutoUpdater CLI; no `install`, `download` or `check` verb is ever composed (the closed literal has none). Q4: confirm the exact verb spelling. |
| V3 NEW | `bash -lc 'cpvinfo <EXPORTER_BINARY>'` — the exporter binary's build/version. `<EXPORTER_BINARY>` is one closed constant, an absolute path under the Log Exporter install directory, that M0 must identify (it is `UNKNOWN` today; not requested for approval until measured). Bound 16 KiB; 30 s; once per MDS per window. |

Facts and states (all by comparing numbers or words the commands print; nothing inferred):

- `JHF_TAKE`, `EXPORTER_TAKE_INSTALLED`, `EXPORTER_TAKE_AVAILABLE`, `EXPORTER_BUILD`: shown as read.
- `EXPORTER_UPDATE_AVAILABLE`: available take greater than installed take -> ATTENTION ("component behind what AutoUpdater
  offers").
- `EXPORTER_DOWNLOADED_NOT_INSTALLED`: V2 reports the take as downloaded but not installed -> ATTENTION; becomes CRITICAL
  together with a failure in 5.4 for the same component (the incident exactly: downloaded, installation failing every 3 h).
- `EXPORTER_NOT_UPDATED_SINCE_JHF_CHANGE`: the JHF take changed between two stored reads (section 7) and the exporter's
  installed take did not change in the same interval -> ATTENTION ("component behind JHF"). Drift alone is never red:
  that exporter may be compatible. It becomes red only through 5.1, 5.2 or 5.4, so a false compatibility claim cannot
  hide or cause a red.

### 5.4 AutoUpdater failures as a log source

Why: link 3. This is the only place the stuck installation is visible, every 3 hours, for the whole 12 days.

| Item | Value |
|---|---|
| L1 NEW | `bash -lc 'date +%s; stat -c "%n %i %s %Y" /opt/CPInstLog/AutoUpdater.log'` — fixed path, no glob, no variable |
| L2 NEW (SFTP ranged read) | read-only open of the path L1 printed; read `[max(0, size - 524288), size)`; close. Bound 512 KiB; 60 s; once per MDS per window. Subject to the same purpose-approval note as E5. |
| Output bound / timeout / retry / frequency | L1: 4 KiB, 30 s, none, once per window |
| unsupported | missing file = `ABSENT` (the AutoUpdater has never run there), not a failure; stat error otherwise UNKNOWN |
| secret_output_risk | low; the log can name file paths and component names; not stored |
| safe_telemetry_fields | `["sizeBytes", "mtime", "failures", "components"]` |

Detector (in memory, counters only): count lines containing `Installation failed for component` and
`Installation flow failed`; from the first, extract the component token (kept only when it matches `^[A-Za-z0-9._-]{1,64}$`;
otherwise `OTHER`); from the second, classify the reason against a short closed vocabulary (`Missing backup file for
uninstallation` -> `MISSING_BACKUP_FOR_UNINSTALL`; anything else -> `OTHER`, count only). Parse each matching line's
timestamp when it parses (form `UNKNOWN`, M0).

Rule: a failure for component `logExporter` (or any component when the exporter component cannot be named) within the
lookback (default 24 hours on the device clock) -> CRITICAL, fact `AUTOUPDATER_INSTALL_FAILING_<component>_<n>`; failures
only older than the lookback -> ATTENTION `AUTOUPDATER_FAILED_EARLIER`; none -> NORMAL; unreadable -> UNKNOWN. There is no
"success" line vocabulary measured yet, so a clearing success line is not used (Q5 offers one if M0 shows it).

### 5.5 Change-feed correlation

Why: the question the incident left open is "what changed just before this went red", which the operator answered by hand
from two unrelated places.

The change feed "orders and shows; it never interprets causality" (`ChangeFeedService`). This proposal keeps that law: a
correlation is **chronological adjacency**, shown as context, never as a cause.

New feed events (kind `DEVICE_EVENT`, source `cp_mds_update_event`, one new source in the feed's `coverage` list, so an
absent source is named and not shown as "no changes"):

| Event | Source of truth | Time and basis |
|---|---|---|
| JHF take changed | two consecutive stored health runs (section 7) with different `JHF_TAKE` | the interval between the two reads (`UTC`, "observed between A and B"); the exact install time is not claimed |
| Exporter take changed | same, `EXPORTER_TAKE_INSTALLED` | same |
| AutoUpdater install failed / install flow failed | the matching lines of 5.4 (component class and reason class only) | `DEVICE_LOCAL` time as the feed already does for cpwd lines, ordered as Europe/Istanbul for ordering only |
| `mds_restore` ran | **source `UNKNOWN`** | see below |

`mds_restore` (the `mds_restored` script of the incident) has no neXus-readable trace today. Candidates, none verified: a
`/var/log/messages` line (needs MDS system-log collection), a file under `/opt/CPInstLog/`, or a restore performed by a
neXus backup/restore job (which would already be in the jobs source of the feed). M0 must show one real evidence line for a
restore; until then the feed shows no `mds_restore` event and says so in `coverage`. This is the one place where the
incident's link 3 cannot be reconstructed from device evidence we hold; Q8.

Link to health: when a device or exporter turns red (first red = the first stored run with CRITICAL where the previous run
was not), the System health row shows "Changes in the 7 days before the first red" with the feed events above for that device,
newest first, each with its source and time basis, and a deep link into Operations > Change feed filtered to the device.
No sentence says "caused by". A device that was already red when monitoring began shows "no earlier run" instead.

## 6. Where the detectors live: two options

| | A. Stateless health read (recommended now) | B. New system-log sources |
|---|---|---|
| Raw lines stored | none; counters and classes | yes, redacted, encrypted, 30 days |
| Needs MDS `include_management_servers` | no (own switch) | yes (the D3 crash hold) |
| History of "since last run" counts | no; tail window with timestamps | exact, with gaps reported |
| New PO approvals | commands in section 5, E5/L2 purpose | the same commands plus MDS C1/C2 rows, two source keys, redaction rules for unmeasured text |
| Time to first signal | one release, then M0 | after MDS collection is re-enabled and measured |
| Load on the MDS | bounded tail reads only | the full Phase B run on an MDS (paths and rotation unmeasured there) |

Recommendation: A now. Revisit B only when the PO lifts the MDS system-log hold; the detectors in section 5 are pure
functions over text and carry over unchanged.

## 7. Data and screens

### 7.1 Storage (counters only; designed here, created by an implementation migration after approval)

| Table | Fields (no raw text, no names in clear) |
|---|---|
| `mds_exporter_health_run` | `run_id`, `device_id`, `job_id`, `observed_at`, `device_epoch`, `jhf_take`, `exporter_take_installed`, `exporter_take_available`, `exporter_build` (all text exactly as printed, bounded 64 chars), `autoupdater_state` (closed vocabulary), `outcome` (per section 8) |
| `mds_exporter_target_health` | `run_id`, `context_class` (`MDS` or domain-server HMAC), `target_name_hmac`, `state_word`, `last_read_age_s` (null for `N/A`), `decode_error_count`, `newest_decode_error_age_s`, `log_mtime_age_s`, `fact_states` (JSON of fact -> CRITICAL/ATTENTION/NORMAL/UNKNOWN) |
| `mds_autoupdater_failure_count` | `run_id`, `component_class`, `reason_class`, `count`, `newest_age_s` |
| `mds_exporter_mute` | `device_id`, `target_name_hmac`, `muted_by`, `muted_at`, `reason` (security_admin only; Q3) |

Retention 180 days (the ledger class of Phase B 5.3). The JHF history needed for the change feed and the "first red" rule
comes from consecutive `mds_exporter_health_run` rows; `device_platform_facts` keeps only the latest value and is not
changed. Gate rows: one per literal of section 5, `SIGNED_OFF` only after the PO approves that literal, with the `source`
pointer to this document.

### 7.2 What the UI shows (Operations > System health, MDS rows)

The MDS row today has no applicable fact and reads `OFF` (Phase B 2.3 / System health 2). With this switch on it gets four
facts, worst fact wins, same state words and sort order as the existing screen:

| Column / fact | Content |
|---|---|
| Exporters | `<running>/<total>` with a drill-down per context and target: state word, last read age, decode-error count |
| Decode errors | total in the lookback; newest age; the version token class (`0xa`) |
| Versions | JHF take, exporter installed/available take, exporter build; `EXPORTER_NOT_UPDATED_SINCE_JHF_CHANGE` as an ATTENTION chip |
| AutoUpdater | failing components in the lookback, with the reason class (`MISSING_BACKUP_FOR_UNINSTALL` shown as words) |
| Last health read | time and outcome, as the existing "Last collection" column |
| Context | the "Changes in the 7 days before the first red" block (5.5) |

A target row says exactly why it is red (its fact name in words) and what evidence the reader can check on the device
(command name and file name by class, not the raw line). The KPI strip gets one card: "Log Exporter not healthy", counted
in MDS exporters, plus the evidenced/UNKNOWN counts as the other cards do. CLI parity: `mdsexporter-health [deviceId]` and
`mdsexporter-health-settings`/`-set`, API `GET /api/v2/system-logs/mds-exporter-health` under `system_log_read`
(operator and above, aiview masked). Notification: reuse the existing routing with a new type `mds_exporter_unhealthy`
(a routing-contract amendment, as `device_crash_file` was); sent once per device per first-red, digest by the routing
contract's rules; Q9.

### 7.3 Privacy and masking

- Domain names and target names are customer identifiers. Stored only as HMACs (the keyed-fingerprint secret, domain-
  separated); shown to aiview as the pseudonymizer's stable pseudonyms (domain `DOM-<WORD>-NN`, target `EXP-<WORD>-NN`,
  device `FW-`/`CLS-` style names as today); operators and admins see clear names per Q10 of Phase B (audited view).
- Output of every command is parsed in memory; only the fields in section 7.1 survive. Destination addresses, ports and
  any other value on a status line are discarded and covered by a redaction test with synthetic output.
- Log lines (`log_indexer.elg`, `AutoUpdater.log`) are never stored or returned; only counts, ages and closed-vocabulary
  classes. A file name is never used as a class; only the closed literal paths of section 5 are read.
- Tests use synthetic output in the measured shapes only; no real hostname, address, domain, serial or target name enters
  the repository (repository privacy gate, `tools/privacy/repository_privacy_check.py`).
- Engineering sessions follow the aiview mentality: counts, opaque ids and outcomes only.

## 8. Outcomes

Per run exactly one of: `COLLECTED` (all five reads parsed), `PARTIAL` (some contexts or steps `UNKNOWN`; the facts that
did parse are stored), `UNSUPPORTED` (no `cp_log_export` on this MDS or role not in scope), `ABSENT` (no exporter log
directory and no targets listed), `UNKNOWN` (session or authentication failure). The job state is `COMPLETED` for the
first four where nothing failed to run, `FAILED partial:` when a step was `UNKNOWN`, as Phase B 9. A failed read is never
"no exporters" and never green.

## 9. Measurement before freeze (M0)

`AGENTS.md` requires the PO to approve each exact command before a first neXus run. Proposed sequence:

1. The PO reviews and approves the literals of section 5 (after fixing the `UNKNOWN` spellings).
2. neXus runs each literal once on one MDS in one window, under the aiview mentality (output through the masked projection
   only), with the `PanSyslogMeasure`-style measurement executor pattern: shape classes, sizes, token classes, no raw
   retention. Result recorded in a findings record next to this one.
3. From that record: the `$EXPORTERDIR` spelling, the `status` layout and time format, the `log_indexer.elg` header time
   form, the AutoUpdater CLI output, the exporter binary for `cpvinfo`, any success vocabulary, and one real `mds_restore`
   evidence line. Then the DRAFT parameters are frozen (this document becomes a contract) and implementation begins.

Implementation outline after that (not started): migration with the gate rows and tables, parsers as pure functions with
synthetic-text tests for each rule above, executor on the existing SSH transport with the ranged read, the System health
panel and change-feed source, CLI parity, architecture tests and Java lane tests, then a first rollout on one MDS in one
window and the PO's real-environment check against the incident's signature on a copy or a deliberately stopped exporter
(never by stopping one from neXus).

## 10. PO questions

1. Approve a second purpose for the SFTP ranged read (tail of `log_indexer.elg` and `AutoUpdater.log`, counters only, no
   storage)? It needs a named extension of the C4 2.3 amendment, because the Phase B approval covers system-log
   incremental reads only. If not, the detectors would have to run over stored system-log chunks (option B), which needs the
   MDS system-log hold lifted.
2. What last-read threshold turns an exporter red: 12 hours (proposed, two missed windows), 6 hours, or 24 hours? Should it
   differ per target?
3. Should a security_admin be able to mute a target in neXus (for an exporter that is off on purpose), shown as "muted by,
   when, why" and never hidden? Or does every `Not running` stay red?
4. Approve the literal spellings of section 5 as measurement candidates (`cp_log_export status`,
   `mdsenv <DS>&& ...`, `autoupdatercli show logExporter -v`, `cpinfo -y all` or `installed_jumbo_take`), knowing the output
   shapes are `UNKNOWN` until the single M0 run?
5. Is a drift between JHF and exporter takes only ATTENTION (proposed, because compatibility is vendor knowledge not on the
   device), or should the PO supply a compatibility rule (for example "exporter take must be at least N for JHF take M") to
   make it red? Also: should a success line in `AutoUpdater.log`, once measured, clear the red?
6. Include standalone management servers and log servers that run Log Exporter (no domain contexts), or only multi-domain
   servers? This needs the exact platform-role scope value for them.
7. Where in the window should this job run on an MDS (first, last, or the existing hash stagger), given that policy
   collection can hold four sessions on the same server? Is a 10-minute job budget acceptable?
8. Where can neXus see an `mds_restore` event: do you have one real evidence line (file and line class) from the incident
   host, or should the correlation start with JHF and AutoUpdater events only?
9. Notification: add the type `mds_exporter_unhealthy` (routing-contract amendment) or show the red on the screen only?
10. The one-shell document (`CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md`) was not available to this proposal. Does it
    forbid more than one command per shell invocation or per `mdsenv` switch (which would force the merged per-domain
    literal of section 4)?
11. Confirm that the new setting `mds_exporter_health.enabled` stays independent of `include_management_servers`, so the
    MDS raw-log hold is not affected.
12. Granularity on screen: one MDS row with the per-domain/per-target drill-down (proposed), or also one System health row
    per domain so the five domains and the MDS level are sorted separately?
