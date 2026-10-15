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

Output: the measurement record committed next to this contract (rule: measurement record before contract).

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
