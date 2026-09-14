# PO Decision Record — 2026-09-14 I — Where artefacts live, how the operator reads one, deviation between backups, and management servers as devices

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-14.** Four directions given in
session, after `PO_DECISION_RECORD_2026_09_14H` opened the backup step. §3
below **supersedes** `BACKUP_RECOVERY_CONTRACTS.md` §6 rule 1 and narrows
`UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` §3.6's reading, both
FROZEN and neither edited in place; the supersession is deliberate, reasoned
and bounded, and the Product Owner is the author of the rule being changed.

## 1. Where artefacts live (AL)

- **AL-1.** The artefact store's location is **configuration, not code**.
  The product reads a root path and the credentials of whatever holds it;
  nothing in the worker, the service or the schema assumes a local disk.
- **AL-2. Target state:** artefacts live on the server that runs the
  product, beside its database, on that server's own storage. **Interim
  state, now:** artefacts live on the local development cluster's recovery
  volume. The move between them is a configuration change and a copy, never
  a code change or a migration.
- **AL-3.** Because the interim host may be unreachable from the operator's
  workstation, the manifest row is the durable record: an artefact's
  existence, its digests, its size and its provenance are readable from the
  database even when its bytes are not reachable. A run whose bytes cannot
  be located reports `artefact_unreachable` and never reports success.
- **AL-4.** Off-host custody of the wrapping key (`C7` §4.4) stays open and
  is not decided here; the interim master key remains the mounted secret.

## 2. Deviation between backups (DV)

- **DV-1.** Every artefact already carries a plaintext digest. The product
  compares each new artefact with the **previous artefact of the same class
  for the same device** and records one of `unchanged`, `changed`, or
  `first` on the run.
- **DV-2.** For the text-shaped artefacts — Check Point Gaia configuration,
  Palo Alto running configuration — `changed` is refined into a **structural
  deviation summary**: which sections gained, lost or altered entries, by
  section name and count, never by value. The Check Point section index and
  the Palo Alto category index already computed for the configuration view
  are the inputs; nothing new is read from a device.
- **DV-3.** A Gaia system backup archive is opaque: for it, deviation is
  digest-level only (`unchanged` / `changed`), and the product says so
  rather than implying it inspected the contents.
- **DV-4.** Deviation is shown on the device's backup and configuration
  views and is the basis of a later alerting feature; no alert is built here.

## 3. The operator may read an artefact (OR) — supersedes BRC §6 rule 1

`BACKUP_RECOVERY_CONTRACTS.md` §6 rule 1 reads "No payload bytes, no
download URL, no decrypt affordance, ever", written to stop a browser-served
download of a device's configuration. The Product Owner requires the ability
to open and read a retained configuration. The prohibition is kept where it
was aimed and lifted where it was overreach:

- **OR-1. No artefact byte, path or decrypt affordance ever reaches an HTTP
  response.** `C7` §3.2 and §3.6 stand in full. There is no download button,
  no link, no API route that returns bytes. This is unchanged.
- **OR-2. The operator retrieves an artefact through the product's own
  command line, on the host that holds it**, naming the artefact and an
  output path, under `role:backup_admin` plus a reason of at least eight
  characters. The command decrypts to the path the operator named and to
  nowhere else.
- **OR-3. Every retrieval is audited** as its own typed action with the
  actor, the artefact id, the reason and the destination path, in the same
  audit trail as every other action. A retrieval is not a read of a view; it
  is an act, and it is recorded as one.
- **OR-4.** The retrieved file is plaintext configuration and is the
  operator's responsibility from that moment. The product does not copy it
  anywhere, does not retain the destination, and warns once in the command's
  own output that the file contains secret-bearing lines.
- **OR-5.** This authorizes reading only. Nothing here enables writing
  anything back to a device; restore stays disabled (`C7` Status, A-1).

## 4. Raw configuration is retained alongside the backup (RC)

- **RC-1.** For every device in scope, the product retains, encrypted, the
  **untouched** configuration read: Check Point's `show configuration`
  output and Palo Alto's running configuration, exactly as `13F` CF-2
  already requires, as artefacts of their own class beside the Gaia system
  backup.
- **RC-2.** These are the artefacts OR-2 retrieves. The sanitized view in
  the product is unchanged and remains the only rendered form.
- **RC-3.** Retention for them follows the same policy as the backup
  artefact; a configuration artefact is never the last artefact deleted
  under the floor rule.

## 5. Management servers are devices of this family (MS)

- **MS-1.** A Check Point management server or MDS, and a Panorama, are
  **devices in their own right**, not merely discovery sources. They are
  added through the same one add menu, hold the same device row, and carry
  the same enrollment and confirm path.
- **MS-2. Inventory, configuration and backup are collected from them**,
  with their own per-vendor read sets, because they are the devices whose
  loss hurts most. Their read sets are measured and gated exactly as the
  gateway ones were: nothing is issued against a management server until
  its own gate entries exist.
- **MS-3.** This does **not** reopen collection *through* the management
  server: `13F` §1's rule stands, device data comes from the device. MS-2 is
  about the management server's **own** inventory, configuration and backup.
- **MS-4. Known constraints to carry into the measurement:** the Check Point
  management export (`migrate_server export` / `mds_backup`) is a long,
  heavy, class-1 operation that earlier contracts left blocked, and an open
  management client can prevent it from starting; Panorama's own
  configuration is large (measured at 84.8 MB) and is streamed, never held.
  A management-server backup is a separate movement from the gateway one and
  needs its own pilot device under `14H` BK-1's allowlist rule.
- **MS-5.** Until MS-2's gates exist, a management server added to the
  product is a device that can be discovered from and confirmed, and whose
  collection actions are refused with a reason naming the missing gate.

## 6. Cross-references

- `PO_DECISION_RECORD_2026_09_14H` BK-1..BK-18 — the backup step this extends.
- `UI2_0_C7_…` §3.2, §3.5, §3.6, §4.4, §6.1 — unchanged except as §3 states.
- `BACKUP_RECOVERY_CONTRACTS.md` §6 rule 1 — superseded by §3 (OR-1..OR-5).
- `PO_DECISION_RECORD_2026_09_13F` §1, CF-2, CF-3; `…_14F` DR-4 (the
  management-server rows MS-1 now requires); `…_14G` CG-6, CG-7.
