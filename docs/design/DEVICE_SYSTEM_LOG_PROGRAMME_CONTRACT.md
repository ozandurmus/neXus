# Device system-log programme — contract (phase A)

**Status: DRAFT — phase A (contract and measurement) approved by the PO on 2026-10-15; no collection command is
approved by this document.** Authority:
[PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md](PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md) §3.
This document becomes FROZEN only after the measurements in §4 are recorded and the PO approves each read in §3.

## 1. Goal

Build a per-device, per-day pool of each managed firewall's system logs. The pool must be:

- incrementally collected;
- encrypted at rest;
- retained for a PO-set period;
- browsable in the UI;
- analysed for likely problems.

The pool answers operational questions such as:

- "What happened on CLS-ALPHA-01 before the failover?"
- "Which gateways had interface flaps or `fwk` crashes this week?"

## 2. Sources (candidates; each needs measurement and PO approval)

### Check Point Gaia (Expert shell, read-only)

| Candidate | What it carries | Notes |
|---|---|---|
| `/var/log/messages` (+ rotated `.1..n`) | OS syslog: kernel, interface link changes, routed, ClusterXL state transitions, clish/expert logins, disk/memory warnings | primary source; rotated by logrotate |
| `$FWDIR/log/fwd.elg` | firewall daemon errors (log transfer, policy fetch) | `.elg` files rotate by size |
| `$CPDIR/log/cpd.elg` | CPD (SIC, policy install, status) | |
| `$CPDIR/log/cpwd.elg` | watchdog: process restarts/crashes | high signal |
| `$FWDIR/log/fwk.elg` | VSX/USFW kernel worker issues | VSX/USFW only |
| `/var/log/dmesg` | boot-time kernel messages | low frequency |
| `$FWDIR/log/ha_*` / ClusterXL state messages | already in `/var/log/messages` on R81.x | confirm by measurement |

Excluded: traffic logs (`$FWDIR/log/fw.log`) are not system logs. They are huge and belong to the log
server/SIEM.

### Palo Alto PAN-OS (XML API, read-only)

| Candidate | What it carries | Notes |
|---|---|---|
| System log (`type=log&log-type=system`, asynchronous job: enqueue, then poll results) | HA state changes, link up/down, commit/config events, auth, dataplane/management-plane errors, license, disk | filter by `receive_time` for increments |
| Config log (`log-type=config`) | who changed what in config | optional, overlaps the policy change report |

Panorama is not a source for device system logs. Each firewall is read directly, which is the evidence-grade
rule.

## 3. Read shapes to approve (per vendor; exact strings frozen after measurement)

All reads must be:

- class `read`;
- bounded in bytes and time;
- inside the job windows;
- incremental.

| # | Vendor | Purpose | Proposed read (Expert/API) |
|---|---|---|---|
| L1 | CP | file identity and size, to resume from an offset and detect rotation | `stat -c '%i %s %Y' /var/log/messages $FWDIR/log/fwd.elg $CPDIR/log/cpd.elg $CPDIR/log/cpwd.elg` |
| L2 | CP | the new bytes of one file since the stored offset, capped | `tail -c +<offset+1> <file> \| head -c <cap>` with `<file>` one of the L1 paths, `<offset>`/`<cap>` digits only |
| L3 | CP | after rotation, the remainder of the rotated file | `tail -c +<offset+1> /var/log/messages.1 \| head -c <cap>` |
| L4 | PAN | enqueue a system-log query since the last seen time | `type=log&log-type=system&nlogs=<n>&query=(receive_time geq '<ts>')` |
| L5 | PAN | fetch the job's results | `type=log&action=get&job-id=<id>` |

## 4. Phase A measurements (each needs the PO's exact-command OK before it runs)

Run once, on 2–3 representative devices of each vendor (one plain gateway, one cluster member, one VSX/USFW
host; one PAN firewall), via the existing diagnostic path, to record:

- Daily growth in bytes and lines of each candidate file. Measure with L1 at two times 24 h apart, then compute.
- Rotation behaviour: logrotate period and naming, and `.elg` size-rotation threshold and naming.
- Line format per file (timestamp form, timezone, multi-line records), to define the parser.
- PAN: system-log volume per day, and the job's latency and result-size limits.

Output: the measurement record committed next to this contract (rule: measurement record before contract). Recorded in
[DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md](DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md).

### 4.1 Measurement record: M5 (Check Point extra sources, PO approval 2026-10-16)

M1/M2 (V149) cover `messages`, `fwd.elg`, `cpd.elg` and `cpwd.elg`. M5 (V157) covers the remaining Gaia log sources.
Rules for all ten gates: Check Point Gaia gateways only (`cp_gaia_gateway`, never management servers); Expert shell,
`SSH_EXEC`, class `read`, `SIGNED_OFF`, 30 s timeout, no retry; run through the existing diagnostic path with its
masked projection (file names may contain host names and are masked there); at most twice per device, 24 h apart,
inside a job window; no polling; no raw retention. The registry fixture is
`ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml`; the migration is
`V157__syslog_measure_m5_gates.sql`. The record below lists the exact commands and which fields to read from each
output. It holds no real values; measured values go into a separate findings record.

| # | Gate | Exact command (Expert shell) | Fields to read from the output |
|---|---|---|---|
| 1 | `cp_syslog_measure_stat_ext` | `bash -lc 'stat -c "%n %s %Y" $FWDIR/log/fwk.elg $FWDIR/log/mq_mng.elg /var/log/hcp.elg /var/log/spike_detective/spike_detective.log /var/log/spike_detective/spike_detective.csv'` | per file: name, size in bytes, last-write epoch seconds; a missing file shows as a `stat` error line (a result, not a failure); daily growth = size delta over the two reads 24 h apart |
| 2 | `cp_syslog_measure_ls_sa` | `bash -lc 'ls -lgo --time-style=+%s /var/log/sa/'` | file count (retention depth), name pattern (`sa<DD>` and `sar<DD>`), size and mtime per file |
| 3 | `cp_syslog_measure_ls_spike` | `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/'` | file count, rotation naming and count (suffix form), size and mtime per file |
| 4 | `cp_syslog_measure_ls_hcp` | `bash -lc 'ls -lgo --time-style=+%s /var/log/hcp/last/'` | archive name pattern, file count, size and mtime of each archive |
| 5 | `cp_syslog_measure_ls_crash` | `bash -lc 'ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/'` | per directory: whether any dump exists, name pattern, size and mtime; a missing directory is a result |
| 6 | `cp_syslog_measure_tail_fwk` | `bash -lc 'tail -n 20 $FWDIR/log/fwk.elg'` | timestamp form and timezone, line shape, multi-line records |
| 7 | `cp_syslog_measure_tail_spike` | `bash -lc 'tail -n 20 /var/log/spike_detective/spike_detective.log'` | timestamp form, line shape, event keywords as pattern classes (no values) |
| 8 | `cp_syslog_measure_tail_spike_csv` | `bash -lc 'tail -n 5 /var/log/spike_detective/spike_detective.csv'` | column count, delimiter, timestamp form, numeric versus text columns |
| 9 | `cp_syslog_measure_head_sar` | `bash -lc 'head -n 20 /var/log/sa/sar$(date -d yesterday +%d)'` | header line shape (kernel, host, date), column set of the first report, interval between samples |
| 10 | `cp_syslog_measure_hcp_listing` | `bash -lc 'f=$(ls -t /var/log/hcp/last/*.tar.gz \| head -1); tar -tzvf "$f" \| head -n 50'` | archive member names (the file-name pattern, not host names), member sizes, whether the members are text or JSON/HTML reports |

Notes:

- The `ls` lines use `-lgo` and epoch times, so each line is `mode links size epoch name`; parse by whitespace.
- Gates 1 to 5 additionally give the rotation facts the parser needs: rotation count and naming, and size-rotation
  thresholds (compare the size just before a rotation across the two reads).
- Gate 9 reads yesterday's file, which is complete, so its header and first samples are stable between the two reads.
- Gate 10 lists only the newest archive; it extracts nothing. Gates 6 to 8 and 10 can show names, so the masked
  projection is mandatory; output is not retained.
- Diagnostics exposure: the `%s` in `cp_syslog_measure_stat` (V149), `cp_syslog_measure_stat_ext` and the four `ls`
  gates is a literal part of the remote command, not a template placeholder. `DiagnosticRead.LITERAL_PERCENT_S_GATES`
  lists these six gates so the placeholder guard does not hide them. Before that fix `cp_syslog_measure_stat` was
  missing from the diagnostics targets because its `stat` format string contains `%s`.

## 5. Storage, privacy, retention (the raw-evidence law exception)

- **Storage.** Raw lines go to the artefact store, encrypted with the existing envelope encryption, in one object
  per device per day per source. The DB gets an index of parsed events: time, device, source, facility/process,
  severity, normalised message pattern id, and count. It never gets the raw line.
- **Retention.** PO-set; default 30 days for raw, 180 days for the parsed index. Pruned by the same retention
  mechanism as backups.
- **Secrets.** Lines matching credential patterns are redacted before storage: `password=`, `key=`, base64 blobs
  after auth keywords, and similar. The redaction rules are tested with synthetic lines.
- **AIView.** IPs go through the subnet-preserving masker, and hostnames, users and device names through the
  topology pseudonymizer. Raw text is never shown to aiview unmasked.
- **Leaving HOST-A.** Nothing raw leaves HOST-A. The optional AI agent (phase D) receives only anonymised
  patterns and counts, and is opt-in in settings.

## 6. Later phases (approved in direction, not designed here)

- **B.** Daily incremental collector job (`*_system_log_collect`) and the Logs screen: per device, per day,
  filter by source, severity and pattern; masked.
  Designed in [DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) (APPROVED — PO
  2026-10-17; its PENDING parameters are set from the measurement findings record).
- **C.** Pattern catalog and priority mapping. AI-assisted first pass to classify the patterns seen into
  critical / warning / info; editable by the operator.
- **D.** Knowledge links (curated pattern-to-SK/KB table) and an optional advisory agent.

## 7. PO answers (chat, 2026-10-15)

1. **Retention:** 30 days for raw log objects (and 180 days for the parsed event index, as proposed).
2. **PAN config logs:** included, next to system logs (`log-type=config`).
3. **Measurement scope:** every enrolled Check Point and Palo Alto firewall, not a sample. Other vendors follow
   once their system-log sources are researched from public vendor documentation.
4. **Collection interval: every 6 hours** (PO, 2026-10-15: "log çekme intervalimizde 6 saat olmalı ki üstüne
   yazmasın"), in the 00/06/12/18 Europe/Istanbul job windows. Reason: device-side rotation must never overwrite lines
   before the pool holds them — on 2026-10-15 an MDS's own logs for the night of a crash were already rotated away
   when it was examined. Phase A's M1 (repeated after 24 h) measures the growth rate; if any source rotates faster
   than 6 hours, that source needs a shorter interval or a rotated-file read, decided by the PO from the measurement.

The measurement reads in §4 still need the PO's exact-command OK before they run.
