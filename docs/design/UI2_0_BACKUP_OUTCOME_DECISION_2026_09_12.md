# Backup outcome semantics — is "Partial" a real outcome?

## Status

**DECIDED, 2026-09-12.** Published by the engineering session under the
Product Owner's written authorization of 2026-09-12 to decide UI 2.0 B1
open items. Scope: the job-outcome vocabulary shown on the jobs screen, and
where a mixed or incomplete backup result belongs. This document does not
amend `C2` or `C7`; its whole point is that no amendment is needed.

## 1. The question

The Operations mockup's job table shows three outcomes — `Succeeded`,
`Partial`, `Blocked` — on a row reading *"PAN configuration collection ·
16 devices"*. The frozen job model (`C2` §3.1) defines `COMPLETED`,
`FAILED`, `REJECTED`, `CANCELLED`, `OUTCOME_UNKNOWN` and `RECONCILED`, and
§3.2 closes the set: *"No other edge exists."* There is no `PARTIAL`.

An earlier recommendation in this session was to add a sixth outcome. The
evidence below reverses that recommendation.

## 2. What the vendors actually document

Researched against official Check Point and Palo Alto Networks
documentation. Sources are listed in §7.

**Neither vendor documents a partial outcome for a single backup.** Check
Point's vocabulary is binary — a backup completes or it fails
(`local backup has failed`, sk100288). Where a backup cannot proceed, the
documented shape is a **precondition that prevents it from starting**, not
a partial result: another backup process already running (sk100403), open
SmartConsole clients, a mixed-model Security Group, or a snapshot running
concurrently. Palo Alto's XML API carries `status` and `code` only; codes
19 and 20 mean success and 1 to 18, 21 and 22 are errors. There is no
`status="partial"`.

**Both vendors report per managed object, not per aggregate operation.**
Panorama stores backups *"for each firewall"*, with *"file names that are
based on serial numbers"*. Its fan-out reporting is explicitly per device
with counts — *"how many configuration pushes to managed firewalls were
successful and how many failed"* — behind a per-device Result column.
Check Point's multi-domain unit is the Domain: `backup-domain` is
per-Domain and restorable only on its originating server.

This is the decisive fact. A run over sixteen devices has sixteen vendor
results. An aggregate label is a **presentation roll-up of per-object
outcomes**, not a new semantic state of one operation.

## 3. What this repository already decided

The repository answered the same question twice, at two layers, and
neither answer produces a job outcome called `PARTIAL`.

At the **job** layer, `C7` §1.2 makes a `BackupRun` *"literally one `C2`
`jobs` row"* and §2.2 is titled *"`BackupRun` is a `C2` job, not a fifth
object"*. `C7` §2.2 maps `target_refs` to *"the assignment's `device_id`"*
— one device, one profile version, one job. A sixteen-device backup is
sixteen jobs. The Line-1 precedent already works this way:
`utils/recovery_collect.py` records a per-device outcome list plus
`collected_count`, `skipped_count` and `failed_count`.

At the **posture** layer, `PARTIAL` **already exists and is already
specified**: `BACKUP_AND_RECOVERY_ARCHITECTURE.md` and
`BACKUP_RECOVERY_CONTRACTS.md` §5 define a per-device readiness state
`PARTIAL` meaning *"some artifact classes present, required ones
missing"*, alongside `READY`, `STALE`, `UNPROTECTED` and `UNKNOWN`,
computed with no network access.

The genuine half-success the code can observe has a different name and a
harsher meaning. `checkpoint/checkpoint_recovery_collector.py` records
`cleanup_failed` when an archive was created and stored but its deletion
from the device could not be confirmed. The repository treats that as a
**failure that also marks the endpoint ineligible**, because the device is
left holding an artefact. Group-level mixture is likewise named
`INCONSISTENT` (`C7` §8 criterion 13) and fails closed.

## 4. Decision

1. **No sixth job outcome.** `C2` §3.1's set stands unamended. A backup
   job over one device is `COMPLETED`, `FAILED`, `REJECTED`, `CANCELLED`
   or `OUTCOME_UNKNOWN`, exactly as frozen.
2. **The jobs table shows one row per job**, therefore one device per row,
   therefore never `Partial`. The mockup's *"16 devices"* row is corrected
   to either sixteen rows or one parent row whose label is a **count**, not
   an outcome — for example *"14 succeeded · 2 failed"*. A count is
   arithmetic over per-object results and invents nothing.
3. **`PARTIAL` keeps its existing meaning** as the per-device readiness
   posture already defined in the backup and recovery architecture. It is
   a property of a device's protection state across artefact classes, not
   of a run. It belongs on a device or readiness surface, never in the job
   outcome column.
4. **`cleanup_failed` is not partial success.** It stays a failure that
   marks the endpoint ineligible, per the existing collector behaviour.
5. **A degraded-content export is not partial either.** Palo Alto
   documents that a non-superuser export obscures sensitive and encrypted
   fields. That produces a complete file with incomplete content, which is
   a **provenance and validation fact about the artefact**, recorded in
   `C7` §3.4's validation levels — not a job outcome.

## 5. Decision table

Left column: a signal the product can actually observe. Right column: the
outcome it maps to. Every row marked UNKNOWN is one the vendor does not
document; per `AGENTS.md`'s UNKNOWN law those rows resolve to
`OUTCOME_UNKNOWN` or to an explicit "not evaluable", never to a guess.

### Check Point, per device

| Observable signal | Documented? | Outcome |
|---|---|---|
| Free space below the required floor, checked before contact | repository behaviour | `REJECTED` — no command sent |
| Another backup process already running (sk100403) | documented | `REJECTED` — precondition, not a failure of this run |
| Backup does not start because SmartConsole clients are open | documented | `REJECTED` |
| Mixed-model Security Group, or a snapshot running concurrently | documented | `REJECTED` — unsupported scope |
| Command rejected on every frozen wire form, nothing created | repository behaviour | `FAILED` |
| `show backup last-successful` advances and the archive is present, fetched and integrity-checked, and on-device deletion is confirmed | documented signals | `COMPLETED` |
| Archive created but its name cannot be parsed, or it is absent at the expected path | repository behaviour | `FAILED`, endpoint marked ineligible |
| Archive stored but on-device deletion unconfirmed | repository behaviour | `FAILED` (`cleanup_failed`), endpoint marked ineligible |
| Command sent, no confirmation obtained, mutation boundary crossed | `C2` §3.5 | `OUTCOME_UNKNOWN` — no automatic second attempt |
| Process exit code of `backup` / `snapshot` / `migrate export` / `mds_backup` | **UNKNOWN** | not used as a signal until documented |
| A checksum or integrity field published alongside the archive | **UNKNOWN** | product computes its own; no vendor claim made |
| Whether a reported-successful archive can be truncated or internally incomplete | **UNKNOWN** | validation level capped, never promoted |

`show backup status` is documented by the vendor but the frozen `C7` §2.4
explicitly rejects a status-poll profile as *"not the shipped profile"*.
This table therefore does not use it. That is a deliberate repository
choice, not a documentation gap.

### Palo Alto Networks, per device

| Observable signal | Documented? | Outcome |
|---|---|---|
| API response `status="success"` with code 19 or 20, and a retrieved payload | documented | `COMPLETED` for that device |
| API response with an error code in 1–18, 21 or 22 | documented | `FAILED` |
| Code 15 operation denied, or 16 unauthorized | documented | `REJECTED` |
| Export performed by a non-superuser, so sensitive fields are obscured | documented | `COMPLETED` with a **reduced validation level** on the artefact; never silently equal to a superuser export |
| Request sent, no response obtained | `C2` §3.5 | `OUTCOME_UNKNOWN` |
| An integrity or completeness signal for an exported config or bundle | **UNKNOWN** | product verifies structure only; makes no completeness claim |
| Per-device result structure for a backup or export fan-out in the API | **UNKNOWN** — documented for config *push*, not for export | roll-up computed from the product's own per-device jobs |
| Device unreachable, commit in progress, or licence problem as named backup failures | **UNKNOWN** | classified by the generic error code, not by an invented cause |
| A documented post-export verification step | **UNKNOWN** | none claimed |

### Fleet view

| Situation | Presentation |
|---|---|
| N device jobs, all `COMPLETED` | `N succeeded` |
| N device jobs, mixed | `X succeeded · Y failed · Z unknown` — counts, never a blended label |
| Any job `OUTCOME_UNKNOWN` | surfaced separately; it is neither a success nor a failure and must not be folded into either count |
| Device holds some artefact classes but not the required ones | the device's readiness posture is `PARTIAL` — a device property, shown on a device or readiness surface |

## 6. What changes

- `C2` and `C7`: nothing. No amendment is required, which is the point.
- `UI2_0_B1_07_JOBS_SCREEN_CONTRACT.md`: its open item 1 is answered. The
  outcome column renders only the `C2` set; a fan-out parent row renders
  counts; `Partial` does not appear as an outcome.
- `UI2_0_MOCKUP_REFERENCE_NOTES.md`: record that the Operations artboard's
  `Partial` chip and its *"16 devices"* row are a known correction, so a
  later reader does not treat the artboard as authority on outcomes.
- The mockup artboards themselves are reference, not contract, and are not
  edited by this decision.

## 7. Sources

Check Point: Gaia Administration Guide R81 (System Backup; Backing Up and
Restoring the System; Scheduled Backups), Gaia R80.40 Snapshot Management
in Clish, R81 Installation and Upgrade Guide (Backing Up and Restoring),
R81 Multi-Domain Security Management Administration Guide (`mds_backup`;
Backing Up and Restoring a Domain), sk100403, sk100288. sk108902 and
sk62226 were located but their bodies did not render; both are recorded as
UNKNOWN rather than summarized from secondary sources.

Palo Alto Networks: PAN-OS Export Files API, PAN-OS XML API Error Codes,
Panorama Manage Panorama and Firewall Configuration Backups, Schedule
Export of Configuration Files, Save and Export Panorama and Firewall
Configurations, Scheduled Configuration Push to Managed Firewalls,
Troubleshoot Push Failure Due to Pending Local Firewall Changes.

## 8. What would change this decision

A single documented vendor signal that a *one-device* backup can end in a
state that is neither complete nor failed. None was found. If Check Point
publishes exit codes or an archive integrity field, or Palo Alto publishes
a per-device export result structure or a completeness signal, the UNKNOWN
rows above become real signals and this table is extended — but the job
outcome set still would not need a sixth value, because those signals
refine the artefact's validation level, not the run's verdict.
