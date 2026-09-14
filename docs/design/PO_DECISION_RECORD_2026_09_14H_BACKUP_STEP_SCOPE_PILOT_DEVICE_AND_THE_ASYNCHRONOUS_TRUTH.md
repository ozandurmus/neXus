# PO Decision Record — 2026-09-14 H — Backup: scope, the one pilot device, and the asynchronous truth

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-14.** Opens step 4 of the
product sequence (`PO_DECISION_RECORD_2026_09_13E` PS-1: Discovery → Login →
Inventory + Configuration → **Backup** → Failover; PS-3 keeps the scheduler
in the backlog). It is the Java-side command-gate lift `13E` PS-2 requires
and `13F` §6 left closed, and it resolves the contradiction
`CP_BACKUP_VENDOR_CONTRADICTION_2026_09_12.md` reported and did not settle.
It sits under `UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` (FROZEN),
`BACKUP_RECOVERY_CONTRACTS.md` §7 and `D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`
(both signed off 2026-08-31 on the Python line, adopted here by C7), and
narrows nothing in them.

## 1. Product Owner directives (2026-09-14, in session)

- **BK-1. Class-1 device writes are authorized for backup, on exactly one
  device.** The Product Owner authorizes `add backup local` and its cleanup
  on a **single pilot device the Product Owner names** — a test firewall.
  The product **refuses a backup job against any other device**, with a
  refusal reason that names the allowlist, not a silent skip.
- **BK-2. Every Gaia command is issued as `clish -c "<command>"` from the
  Expert landing shell.** The session lands in Expert (`bash`), as the
  inventory measurement of the same day recorded; no bare Clish form is
  issued and no interactive Clish process is driven.
- **BK-3. The archive is transferred by SFTP and verified on both sides
  before anything is deleted.** A digest is computed **on the device** and
  compared with the digest of the bytes actually received; the device-side
  copy is deleted only after they match. A mismatch is a failure, the
  device-side copy is left in place, and the run says so.
- **BK-4. No separate read-only measurement round.** The first live run is
  performed directly against the pilot test firewall, and its observed
  command forms, timings and output shapes are recorded as the measurement.

## 2. The asynchronous truth (resolving the reported contradiction)

`CP_BACKUP_VENDOR_CONTRADICTION_2026_09_12.md` Finding 1 established, against
vendor documentation for R80.40 through R81.20, that `add backup local` is
**asynchronous**: it returns once the job is queued, not once the archive
exists. The profile shape frozen in `UI2_0_C4` §2.4 and `BRC` §7.3 point 4 —
one blocking exec bounded at 900 s — therefore measures nothing and can
record success before the archive exists, fetch a partial file, and delete a
live archive. It is not carried into Java.

- **BK-5. The Check Point backup is submit-then-poll.** Submit
  `clish -c "add backup local"`; record the archive name **only** from that
  command's own output; then poll `clish -c "show backup status"` on a fixed
  interval until it reports a terminal state or the run's overall deadline
  passes. Finding 2 of the same record establishes `show backup status` as
  vendor-documented; it is added to the gate below. A poll that never
  reaches a terminal state ends the run as `OUTCOME_UNKNOWN` (`C2`), never
  as success, and the device-side archive is **not** deleted.
- **BK-6. The 3× free-space rule is a local heuristic, not vendor fact.**
  Finding 3 stands: it has no vendor basis. The precondition remains — a
  free-space read before submitting — but its threshold is a configurable
  value with a documented default, labelled a heuristic wherever it appears,
  and never presented as a vendor requirement.
- **BK-7. Two command literals are confirmed by the pilot run, not
  assumed.** `show diskspace` and `delete backup <name>` are marked
  CONFIRM-ON-HARDWARE in `BRC` §7.7/§7.8 because vendor documentation does
  not support the Clish form. The pilot run records which form answered; if
  the Clish form fails, the Expert fallback (`df -P /var/log`, and
  `rm -f -- <exact path>`) becomes the primary and this record's successor
  says so. Deletion targets **only the exact archive name this run
  created** — never a pattern, never a name derived from a listing.
- **BK-8. Documented preconditions the earlier contracts did not model** are
  honoured: a backup and a snapshot cannot run concurrently, and an open
  management client can prevent a management backup from starting. The
  product does not attempt to detect the second (it is out of the device's
  own answer set); it reports the vendor's refusal as the failure reason.

## 3. Scope of the first backup movement

- **BK-9. Check Point gateway only.** Palo Alto device-state export is a
  later movement; it carries its own owed decision (`D4` §8: PAN still
  reuses the inventory API key and needs a distinct backup identity first).
- **BK-10. Targets.** A cluster member is backed up as its own device (its
  Gaia system is its own). A **VSX virtual system is never a backup target**
  (`BRC` §7.3 point 3); the VSX host itself is one. Panorama is never a
  device-state source (`BRC` §7.1 point 3). Management-server export
  (`migrate_server export` / `mds_backup`) stays blocked.
- **BK-11. Credential.** The backup runs under a **distinct backup identity
  per vendor**, from the product's credential store, with **no fallback to
  the collection credential** (`D4` Option A). Its absence fails the run
  closed, before any device contact.
- **BK-12. Manual only.** One run per operator action, `role:backup_admin`
  plus a reason of at least eight characters (`C7` §6.1). No schedule, no
  automatic trigger.
- **BK-13. Restore stays disabled** (`C7` Status and amendment A-1). Nothing
  in this movement enables a write back to a device.
- **BK-14. No download, ever.** No artefact byte, no path and no decrypt
  affordance reaches any HTTP response (`C7` §3.2, §3.6; `BRC` §6 rule 1).
  The screen shows posture: what is held, when, how big, its digest, its
  validation level.

## 4. What the artefact store must become first

The store built for configuration collection (one master key encrypting
bytes directly, content-addressed files, no manifest table) is **below** the
C7 shape and must reach it before it holds a backup:

- **BK-15.** Per-artefact data key wrapped by the vault master key, with the
  wrapped key persisted on the manifest row (`C7` §4.1); rotation re-wraps
  keys and never re-encrypts bytes (§4.3).
- **BK-16.** The `backup_artefact` manifest table of `C7` §3.2, including
  the hostname fingerprint (never a raw hostname), both digests and byte
  counts, the artefact class, version locking (§3.3: a Check Point artefact
  whose software version cannot be resolved is **refused, not stored**),
  the validation level (§3.4), and its audit trigger.
- **BK-17.** A recovery volume separate from the database volume, mounted
  only by the worker role, never by the web service.
- **BK-18.** The retention ledger of `C7` §3.5 exists and is append-only
  from the first row; its policy numbers stay at the contract's defaults
  until the Product Owner sets them.

## 5. The gate this record lifts

For the Java product, on the **allowlisted pilot device only**, class 0
unless marked:

| # | Literal (as issued) | Class |
|---|---|---|
| 1 | `clish -c "show diskspace"` (fallback `df -P /var/log`) | read |
| 2 | `clish -c "add backup local"` | **class 1, operational write** |
| 3 | `clish -c "show backup status"` | read |
| 4 | `clish -c "show backups"` | read |
| 5 | SFTP read of the archive path the run's own submit output named | read |
| 6 | a digest computation over that file, on the device | read |
| 7 | `clish -c "delete backup <name>"` (fallback `rm -f -- <exact path>`) | **class 1, operational write** |

Nothing else. Every row needs its ten-field gate entry document before it is
issued, authored from this table; the entries are `SIGNED_OFF` for the
allowlisted device and carry the CONFIRM-ON-HARDWARE note of BK-7 where it
applies.

## 6. Cross-references

- `PO_DECISION_RECORD_2026_09_13E` PS-1..PS-3, SI-1/SI-3; `…_13F` §3, §6.
- `UI2_0_C7_…` §2.2, §3.2–§3.6, §4, §6.1, §7, and its Status on restore.
- `BACKUP_RECOVERY_CONTRACTS.md` §7.3, §7.4, §7.5, §7.7, §7.8, §9; `§6` rule 1.
- `D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md` Option A and §8.
- `CP_BACKUP_VENDOR_CONTRADICTION_2026_09_12.md` Findings 1–3 and §6 —
  answered by §2 above: continue, corrected to the asynchronous pattern.
- `UI2_0_BACKUP_OUTCOME_DECISION_2026_09_12.md` — the outcome model, unchanged.
