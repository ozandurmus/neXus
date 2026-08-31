# RB.3b — CP Gaia system backup collection (`add backup local` + SCP fetch)

## Status

**IMPLEMENTATION STEPS 2-7 LANDED 2026-08-31. Status stays `in_progress` — not
`IMPLEMENTED` — until the mandatory watched real R81.10/R81.20 single-gateway
run has happened (see "Definition of done" item 5).** Steps 2-4 (offline
layer), step 5 + C6 (device core, `Sonnet 5, extended thinking`), step 6
(`main.py` wiring) and step 7 (this project-metadata sync) are all
implemented against fixture transports; no live device has been touched.

**`D3` RESOLVED 2026-08-31 — approved by the product owner, scoped to a named
pilot set.** The `operational-write` class is accepted as a real command class.
`add backup local` may run, but **only against gateways explicitly named in
`SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES`, which is empty by default and
fail-closed** — design decision B10 is therefore mandatory, not optional.
Scheduling is **not** approved: `"recovery-cp"` stays out of
`ALLOWLISTED_WORKFLOWS` (B9). Recorded in architecture §13.

**RB.3b unblocking prep completed 2026-08-31** on `feature/rb-3b-gate-prep`
(`ARCHITECTURE` / `DOCS`, no code, no device call), then **reviewed and signed
off 2026-08-31** (product owner / security lead / network-security leads). All
five open items now have an approved artifact; RB.3b is **no longer blocked** —
the next step is implementation.

The five blockers, all cleared 2026-08-31:

1. **`D4`** — backup credential identity. **SIGNED OFF (security lead).**
   `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md` — Option A (distinct
   per-vendor backup service account, `SECURITYEXPERT_CP_BACKUP_SSH_USERNAME` +
   `_PASSWORD_FILE` / `_PASSWORD`, **no fallback** to `SECURITYEXPERT_CP_CONFIG_
   SSH_*`) adopted as the target; Option C (DEV.2.2 mounted-material custody) is
   the pilot mechanism. Architecture §13 `D4` row resolved, §10 rule 4 / §5
   updated. PAN (`RB.2`) owes a matching follow-up before it leaves
   `IMPLEMENTED` — not a blocker here.
2. **Command gate §7.3 point 14** (device-impact assessment) — **SIGNED OFF
   (product owner / network-security leads).** `add backup local` touches no
   config / process / policy / routing / clustering / HA state; sole data-plane
   failure mode is `/var/log` exhaustion, covered by points 12/13. Supersedes
   the (closed 2026-08-25) P0 audit dependency.
3. **§7.7 and §7.8 gate entries** — **SIGNED OFF (product owner / network-
   security leads).** The literal Gaia command strings are kept **as written**
   (`show diskspace` / `df -P /var/log`; `delete backup <name>` /
   `rm -f -- /var/log/CPbackup/backups/<name>`). Estate is **R81.10 + R81.20
   only**. The R81 Gaia Administration Guide documentation check for the sign-off
   found neither `show diskspace` nor a per-name `delete backup <name>` Clish
   form in the published R81 command lists (R81 documents Portal-only deletion;
   R80.30 lists `delete backup` with no name argument); the Expert forms
   (`df -P /var/log`, `rm -f -- …`) are exact and portable. The exact-token
   check is therefore **moved to the first watched real R81.10 / R81.20 gateway
   run** — if a Clish form is absent on that build, the Expert form is the sole
   and primary form (still an explicit literal in the collector's frozen set,
   never a prefix-rule relaxation). Recorded in the §7.7 / §7.8 sign-off notes.
4. **Durable per-endpoint `operational-write` ledger** — **DESIGN ACCEPTED
   (product owner).** `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` — module
   `utils/recovery_operational_ledger.py`, a fifth DEV.3.3 evidence-backend
   concern, **fail-closed on an unreadable ledger** (absent ledger ≠ error),
   read inside the admission-held section. Contract §7.3 point 6 tightened (C3);
   new §9.13 test obligation. Accepted as written, false-refusal-over-false-
   proceed understood.
5. **Refuse to store a version-unknown CP artifact** — **ACCEPTED AS WRITTEN
   (product owner).** Contract §3 frozen rule 5 tightened (C4): a version-locked
   CP class with no resolvable `software_version` is **not stored**; PAN keeps
   the honest `"unknown"` sentinel.

The P0 `cp_device_interaction_safety` audit **closed 2026-08-25**
(`project/backlog.json`, status `done`). It is **not** a blocker on this build.

RB.3a (attestation, `read` class) is a separate contract and is not blocked by
any of the above.

---

## `D3` — the decision and what it accepted (RESOLVED 2026-08-31)

> ~~**Is `add backup local` acceptable at the platform's current maturity as the
> new `operational-write` command class, or must Check Point backup wait for
> full write-capability maturity?**~~
>
> **Approved, scoped to a named pilot set** (product owner, 2026-08-31).

This section is retained as the record of what was put to the product owner and
what they accepted — not as an open question. Architecture §13 carries the
canonical entry.

### What the command actually does

`add backup local` writes a multi-megabyte archive to the gateway's `/var/log`
partition. It contains the Check Point configuration plus networking/OS
parameters — **not** the OS, **not** product binaries, **not** hotfixes
(architecture §3.1). It changes no configuration, installs no policy, and is
reversible by deleting the file.

It is nevertheless **not a read**. `AGENTS.md` states "No automatic
network-device write/change operations at the current maturity", and the
platform's existing taxonomy is binary. Architecture §5 therefore proposes a
third class rather than quietly filing a device write under "read":

| Class | Definition | Maturity |
|---|---|---|
| `read` | no device state change | allowed, via the gate |
| `operational-write` | creates/removes a transient artifact, consumes a bounded resource; **no configuration change**; reversible | **the thing `D3` decides** |
| `config-write` | changes running configuration, policy, routing, credentials, power state | prohibited |

### The actual risk being accepted

**A full `/var/log` on a production Check Point gateway is an outage mode.**
That single sentence is the whole of `D3`. Everything else is mitigation.

Mitigations this contract binds if `D3` is approved:

- **Free-space precondition** (§7.3 point 12): free space on `/var/log` must be
  ≥ **3×** the largest prior backup for that device before the command is sent.
  **If free space cannot be determined, abort** — never proceed optimistically
  (architecture §10 rule 8).
- **Cleanup contract** (§7.3 point 13): the on-device archive is deleted after a
  digest-verified fetch, **and is still deleted if the fetch fails**. Orphaned
  multi-MB archives accumulating on firewalls is itself the resource risk the
  gate exists to prevent.
- **No retry** (§7.3 point 5): a retry risks a second concurrent backup and
  doubled disk consumption.
- **One per endpoint per 24 h**, hard-enforced, not by convention (§7.3
  point 6 — see design decision B4, which is where this gets expensive).
- Never valid inside a VSX virtual-system context (§7.3 point 3).
- Spark / Gaia Embedded is `UNSUPPORTED`, and platform is never inferred from
  direct-Clish behaviour (`AGENTS.md`).

### What is lost if `D3` is declined

The Gaia system backup is the **only** supported Check Point artifact that
restores a gateway's configuration and OS parameters. Declining `D3` means:

- Check Point devices can reach at most `PARTIAL` readiness, on RB.3a
  attestation evidence alone — "the device says it has one" — permanently.
- The 2027 BackBox exit (architecture §2) has no Check Point replacement path.
  PAN is covered by `RB.2`; CP would not be.
- `RB.6` (controlled restore) has nothing to restore from on the CP side.

### What is lost if `D3` is approved

The platform stops being strictly read-only against Check Point devices. That
is a real change in what this system is, and the product trajectory already
anticipates it (`CURRENT_STATE.md`: "the end-state is a **write-capable device
administration platform**; read-only now is a staging phase"). `D3` is the
first step across that line, and it is worth deciding as such rather than as a
backup-feature detail.

### The narrower options

If a full approval is more than you want to give now, two intermediate
positions are coherent:

- **Approve `operational-write` for a named pilot set only** — an explicit
  allowlist of gateway `entity_id`s, empty by default, so the capability exists
  and the blast radius is whatever you put in the list. This contract supports
  it directly (B10).
- **Approve the class but not the schedule** — on-demand operator runs only, no
  scheduler entry, exactly as RB.3a handles it (A9). `"recovery-cp"` stays out
  of `ALLOWLISTED_WORKFLOWS`.

Also on the table, and separable: **`D6`** — whether `operational-write` is
adopted into `docs/AI_DEVELOPMENT_PROTOCOL.md` as a permanent taxonomy
amendment or stays local to this design. `D3` can be answered without
answering `D6`; the reverse is not true.

---

## Objective

Replace `checkpoint/checkpoint_recovery_collector.CheckpointGaiaBackupCollector`
— today a typed stub that raises `RecoveryCollectionBlockedError` on every call
— with a real collector that produces a `cp_gaia_backup` artifact in the RB.1
recovery store, under the RB.2 orchestration that already exists.

Everything around the device call is built: target selection with the VSX
`__vsid_` convention, the `RecoveryCollector` protocol, admission-coordinated
batch execution where one gateway's failure does not abort the batch, the
encrypted store, and the RB.4 V1–V3 validation battery. **Only the device
interaction is missing.**

## Scope

### In scope

1. **`checkpoint/checkpoint_recovery_collector.py`** — the stub becomes a real
   `RecoveryCollector`: preconditions, `add backup local`, SCP fetch, cleanup.
2. **A free-space read** (§7.7, amendment C1) — a new `read`-class CP command,
   used solely as the precondition check.
3. **A backup-deletion command** (§7.8, amendment C2) — a new
   `operational-write` CP command, used solely for the cleanup contract.
4. **`utils/recovery_operational_ledger.py`** — a durable per-endpoint
   last-execution ledger for `operational-write` commands, backed by the DEV.3.3
   evidence backend (filesystem or Postgres). See B4.
5. **`main.py --recovery-collect --recovery-vendor checkpoint`** — already
   wired; the vendor branch stops constructing a blocked stub.
6. **Pilot allowlist** (B10) — `SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES`,
   empty by default, fail-closed.
7. Contract amendments C1–C6 to the design docs.

### Explicitly out of scope

- **Snapshots.** Architecture §3.1: the platform does not pull Gaia snapshots
  (≥ 2.5 GB, same-machine restore only). Their existence is attestation
  evidence (RB.3a), never a held artifact. This is settled and is not reopened
  here.
- Management export (`migrate_server export` / `mds_backup`) and consistency
  groups — RB.3c.
- Restore of any kind — `RB.6`, `OP.2` bar.
- Scheduling: `"recovery-cp"` does **not** join `ALLOWLISTED_WORKFLOWS` in this
  build (B9).
- Retention policy changes — RB.1's GFS model and `D5` are unchanged.

## Design decisions

### B1 — `add backup local` never passes through the read-command gate

`configuration/checkpoint_config_collector._is_allowed_read_command` admits
`show *` plus the single literal exception `cpstat os -f hw_info`. `add backup
local` fails that test, and **that is correct**. This contract explicitly
forbids widening `_is_allowed_read_command` to accommodate it.

The `operational-write` path is a separate code path with a separate, frozen
single-command constant and its own preflight. The read gate stays a read gate.
This is the structural expression of architecture §5: if the two classes share
an allowlist, the taxonomy is decorative.

### B2 — The precondition needs a command that §7 never gated (amendment C1)

§7.3 point 12 mandates a free-space check on `/var/log` before execution. §7
contains no gate entry for the command that performs it. That is a hole in the
frozen contract, not an implementation detail, and it is filled here as **§7.7**:

> **§7.7 — `/var/log` free-space read (CP Gaia) — class: `read`**
>
> 1. **Why required:** §7.3 point 12's precondition cannot be satisfied without
>    it. Architecture §10 rule 8: an `operational-write` runs only after its
>    precondition passes, never optimistically.
> 2. `read` — reports filesystem utilisation; changes nothing.
> 3. Gaia. **The literal command string is owed at gate sign-off**: Clish
>    `show diskspace` where the platform supports it, otherwise Expert
>    `df -P /var/log`. Both are reads; the choice is a compatibility question
>    across the estate's Gaia releases, to be confirmed against the R81 Gaia
>    Administration Guide and the estate's actual mix — not assumed here.
> 4. Timeout 30 s. 5. 1 retry. 6. Max 1 per endpoint per backup attempt, plus
>    ad-hoc.
> 7. Reuses the same SSH session as §7.3 — one session does precondition,
>    backup and cleanup.
> 8. **Unsupported:** if neither form returns a parseable free-space figure, the
>    result is `UNKNOWN` and §7.3 point 12 **aborts the backup**. An unparseable
>    disk reading must never be treated as "probably fine".
> 9. Secret-bearing risk: none. 10. Safe telemetry: free bytes, total bytes,
>    partition name.
>
> Note: if the Expert `df` form is chosen, it becomes the **second** literal
> non-`show` exception in the CP read vocabulary, alongside `cpstat os -f
> hw_info`. It must be added as an explicit literal, never as a relaxation of
> the prefix rule (B1's reasoning).

**"3× the largest prior backup, or a conservative default on first run"** is
made concrete: the "largest prior backup" is read from the recovery store's
`cp_gaia_backup` manifests for that `entity_id`. With no prior backup, the
floor is `SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB`, **default 3072**, hard floor
1024 — the default is a proposal for gate sign-off, not a measured value, and
should be reviewed against the estate's actual backup sizes at the first
real-environment run.

### B3 — The cleanup contract needs a second ungated command (amendment C2)

§7.3 point 13 requires deleting the on-device archive after a verified fetch
**and** after a failed fetch. Deletion is a device command, `operational-write`
class, and §7 never gated it either. Filled here as **§7.8**:

> **§7.8 — backup deletion (CP Gaia) — class: `operational-write`**
>
> 1. **Why required:** §7.3 point 13. Without it, every backup run leaves a
>    multi-MB archive behind and the platform becomes the disk-consumption
>    problem the gate exists to prevent.
> 2. `operational-write` — removes an artifact this platform created, in the
>    same session that created it. Consumes no resource; releases one.
> 3. Gaia Clish. **Literal command string owed at gate sign-off** (Gaia's
>    `delete backup <name>` form, to be confirmed against the R81 Gaia
>    Administration Guide).
> 4. Timeout 60 s. 5. **1 retry** — unlike §7.3, retrying deletion is strictly
>    safer than not retrying. 6. Bounded by §7.3's own frequency.
> 7. Same session as §7.3.
> 8. **Unsupported:** same platform gating as §7.3.
> 9. Secret-bearing risk: the filename is an operational identity (redacted);
>    the command produces no payload. 10. Telemetry: status, free space after.
> 11. Resource consumed: **none — it releases disk.**
> 12. Precondition: the target name must be the archive this run created, held
>     in memory from §7.3's own output. **Never a pattern, never a wildcard,
>     never a name discovered by listing.** The platform deletes exactly what it
>     made and nothing else.
> 13. Cleanup contract: n/a — this *is* the cleanup.
> 14. Device-impact assessment: owed with §7.3's, same review.

Point 12 is the load-bearing one. A deletion driven by a `show backups` listing
or a filename pattern could remove an operator's own backup. The name comes
from the command that created it, in the same session, or the deletion does not
happen — and a run that cannot identify its own archive reports
`CLEANUP_FAILED` loudly rather than guessing.

### B4 — "1 per 24 h" needs a durable ledger; in-memory admission cannot enforce it

§7.3 point 6 says the 24-hour ceiling is "hard-enforced by the admission
coordinator, not by convention". Today it cannot be.

`utils/collection_executor.CollectionCoordinator` is **process-local and
in-memory** (architecture §9's own correction says so, in the course of
correctly arguing that per-endpoint locking does not need DEV.3.2). A
per-endpoint lock prevents two *concurrent* backups. It does not prevent a
second backup ten minutes after the first, and a process restart resets
whatever it knew.

So the ceiling needs durable state: **`utils/recovery_operational_ledger.py`**,
an append-only per-endpoint record of `(entity_id, command_class, executed_at)`
for `operational-write` commands, written through the DEV.3.3 evidence backend
so it is correct on both the filesystem and Postgres backends and shared across
containers when Postgres is configured.

The check runs **before** the free-space read, so an endpoint inside its
24-hour window is skipped with zero device contact. A ledger that cannot be
read **fails closed** — the backup does not run. This is the one place in the
recovery plane where a fail-safe degrade would be wrong: "I couldn't tell
whether I already backed this up today" must not resolve to "back it up again".

This is the single largest piece of genuinely new engineering in RB.3b, and it
is a direct consequence of §7.3 point 6 being written as a hard enforcement
rather than a convention. Worth knowing before the estimate.

### B5 — No retry, enforced structurally

`run_recovery_collection` does not retry `collect()` today. B5 makes that a
tested invariant for the CP path specifically: the collector is invoked exactly
once per target per run, and no retry decorator, loop or wrapper may enclose it.
The PAN collector's 403 guard (§7.1 point 5) documents the same intent for a
different reason; this one is about disk, not privilege.

### B6 — Bytes stream into the encrypting writer, never to a plaintext temp file

§7.4 / architecture §9.1. The SCP fetch reads into memory and hands the buffer
straight to `utils.recovery_store.write_artifact`, which already takes
`plaintext: bytes`.

Holding the whole archive in memory is acceptable **because it is a backup and
not a snapshot** — MB range, not the ≥ 2.5 GB snapshot the architecture already
refused to pull (§3.1). If a real-environment run ever produces a backup large
enough to make this uncomfortable, that is a signal to revisit §3.1's
assumption, not to spill plaintext to disk.

### B7 — Per physical endpoint; a VSX virtual system is never a backup target

§7.3 point 3. Same rule and same reasoning as RB.3a's A3: targets are
deduplicated to physical endpoints, and a `<device>__vsid_<vs_id>` entity is
never contacted and never credited with the host's artifact.

`select_recovery_targets` resolves VS entities today (it is shared with the PAN
path, where they do not arise). The CP collector rejects one at request time,
before any device contact, with a message naming §7.3 point 3 — not a silent
skip, consistent with how an unresolvable `entity_id` is already handled.

### B8 — `software_version: "unknown"` is refused for `cp_gaia_backup`

The PAN collector records `software_version: "unknown"` honestly, because
`unified.json` carries no PAN version field and inventing a device command to
get one was correctly avoided. **That reasoning does not transfer to Check
Point**, for a reason specific to the artifact:

A Gaia backup is **version-locked** — a backup taken on R81.10 does not restore
onto R81.20 (architecture §3.1). Architecture §3.3 invariant 2 makes the exact
version a first-class manifest field, and RB.4's V3 check treats an `"unknown"`
artifact version as `NOT_APPLICABLE` rather than `FAIL`. A CP backup stored
without a version would therefore sit at V2 forever, never reach `READY`, and —
worse — would look like a valid recovery artifact while being unrestorable onto
the device's current software.

So: the collector resolves the Gaia version from existing evidence
(`unified.json` / the configuration evidence store, which
`configuration/checkpoint_config_collector._parse_gaia_version` already
produces) and **refuses to store the artifact** if it cannot. No new device
command for version — the evidence plane already has it.

### B9 — No scheduler entry in this build

`"recovery-cp"` stays out of `ALLOWLISTED_WORKFLOWS`, whatever `D3` decides.
"May this command run" and "may it run unattended on a timer against the whole
fleet" are two decisions; RB.3b asks only the first. The existing allowlist
comment is updated to say the name is withheld pending a separate scheduling
review, rather than pending a blocker that has been resolved.

This does **not** weaken security invariant 9.12 ("backup workflow is
admission-coordinated, not a side channel"). `ALLOWLISTED_WORKFLOWS` is checked
only by the **scheduler policy loader**; `execute_admitted_collection` — which
is what enforces the per-endpoint lock and the concurrency budget — does not
consult it. The on-demand CLI path is admission-coordinated either way. 9.12's
test shape (written for RB.2, where scheduling was in scope) needs one word of
clarification for the CP case; recorded as amendment **C6**: for a workflow with
no scheduler entry, 9.12 is satisfied by asserting the endpoint lock is
acquired, and by asserting the name is *absent* from the allowlist.

### B10 — Pilot allowlist, empty and fail-closed by default

`SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES` — a comma-separated `entity_id`
allowlist. **Unset or empty means no endpoint may be backed up**, and the
collector says so by name.

This exists so `D3` can be answered "yes, for these five gateways" without a
follow-up build, and so that an approved capability cannot become a fleet-wide
operation through a CLI typo. It composes with `--recovery-gateways`:
the allowlist is a ceiling, the CLI selector chooses within it.

### B11 — Credential identity (`D4`) is a hard precondition, not a default

Architecture §10 rule 4: backup credentials are separate identities from
collection credentials, held in the `DEPLOY.1` secrets vault. `D4` is open.

RB.3b **must not** silently reuse the CP collection credential. If `D4` is not
answered before implementation, the collector reads a distinct
`SECURITYEXPERT_CP_BACKUP_SSH_*` identity and fails closed when it is absent —
it does not fall back to the collection identity. A fallback here would quietly
grant the collection account whatever rights backup requires, which is the
exact failure mode `D2` was raised to prevent on the PAN side.

## Correctness contract

1. Order of operations per endpoint, in one SSH session:
   ledger check → allowlist check → platform check → free-space read →
   `add backup local` → SCP fetch → digest verify → store write → delete.
2. Any failure before `add backup local` means **no device write occurred** and
   the outcome is `failed` with the reason.
3. Any failure after `add backup local` **still runs the deletion** (§7.3
   point 13). A failed deletion is reported as `CLEANUP_FAILED`, and the
   endpoint is marked ineligible for further backup until an operator clears it.
4. The stored artifact's digest matches the fetched bytes; a mismatch means no
   store write, and the deletion still runs.
5. `software_version` is real or the artifact is not stored (B8).
6. One endpoint's failure never aborts the batch (existing
   `run_recovery_collection` semantics).
7. The 24-hour ceiling is enforced from durable state, and an unreadable ledger
   blocks the run (B4).

## Privacy and safety invariants

- Backup bytes never reach `output/index.html`, any embedded payload, the
  support bundle, or repository metadata (architecture §10 rule 1). Manifests
  only.
- The artifact is encrypted at rest by `write_artifact`; the wrapping key lives
  outside the recovery volume (rule 2).
- Backup filenames are operational identities — redacted in every log line
  (rule 6).
- No plaintext temp file at any point (B6).
- The repository privacy gate must fail on any recovery artifact, key or
  manifest inside the repository tree (rule 5) — already enforced; re-verified.
- Backup credentials are separate identities (B11, rule 4).

## Contract amendments required (design docs)

Status after RB.3b prep + sign-off (2026-08-31): **C1–C4 landed** in the design
docs; **C5 landed** (the `D3` resolution + the now-resolved `D4` row); the
§7.3 point 14 / §7.7 / §7.8 gate entries and the ledger + §3 rule 5 tightenings
are all **signed off / accepted 2026-08-31**; **C6 outstanding** (deferred to
RB.3b implementation, where §9.12's CP-path test is written).

- **C1 — new §7.7**, `/var/log` free-space read, class `read` — **LANDED**
  in `BACKUP_RECOVERY_CONTRACTS.md` §7.7, *PREPARED FOR GATE REVIEW*, with the
  literal `show diskspace` / `df -P /var/log` strings.
- **C2 — new §7.8**, backup deletion, class `operational-write` — **LANDED**
  in `BACKUP_RECOVERY_CONTRACTS.md` §7.8, *PREPARED FOR GATE REVIEW*, with the
  literal `delete backup <name>` / `rm -f -- /var/log/CPbackup/backups/<name>`
  strings and the "confirm exact token at sign-off" marker.
- **C3 — §7.3 point 6** — **LANDED**: the 24-hour ceiling is enforced from the
  durable ledger (`docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`), read
  inside admission, unreadable ⇒ fail closed. New §9.13 test obligation added.
- **C4 — §3 frozen rule 5** — **LANDED**: a version-locked CP class
  (`cp_gaia_backup`, `cp_mgmt_export`, `cp_mds_backup`) with no resolvable
  version is **not stored**; the PAN classes keep the honest `"unknown"`
  sentinel and are unaffected.
- **C5 — architecture §13** — **LANDED** as the resolved `D3` entry (2026-08-31,
  earlier) plus the new `D4` recommended-resolution row pointing at
  `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`; §5 and §10 rule 4
  updated too.
- **C6 — contracts §9.12** clarified for a workflow with no scheduler entry
  (B9): the invariant is satisfied by the endpoint lock, not by allowlist
  membership. **Outstanding** — folded into RB.3b implementation, where the
  §9.12 test for the CP path is written.

## Implementation plan

Sequenced so that nothing touches a device until the last step.

1. Amendments C1–C6; §7.3 point 14 written; gate sign-off obtained. **No code.**
2. `utils/recovery_operational_ledger.py` + tests — pure local state, no device,
   independently reviewable (B4).
3. Pilot allowlist + platform gating + VSX rejection + `software_version`
   resolution (B7, B8, B10) — all decidable offline, all testable without a
   device.
4. Free-space read and its parser (§7.7), against fixture output.
5. `add backup local`, SCP fetch, digest verify, deletion — the device-touching
   core, last.
6. Wire into `main.py`'s existing `checkpoint` branch; remove the blocked stub.
7. Project metadata; `CURRENT_STATE.md`; `docs/AI_DEVELOPMENT_PROTOCOL.md` if
   `D6` adopts `operational-write` permanently.

## Acceptance criteria

- **AC-1** Precondition abort (contracts §11 / §9.10): free space below 3× → no
  backup command sent; free space unparseable → no backup command sent. Both
  report the reason.
- **AC-2** Cleanup on success: archive deleted after a digest-verified fetch.
- **AC-3** Cleanup on failure: fetch fails → archive still deleted, outcome
  `failed`; deletion also fails → `CLEANUP_FAILED` and the endpoint is marked
  ineligible.
- **AC-4** Deletion targets only the name this run created; a fabricated or
  pattern-derived name is refused (B3 point 12).
- **AC-5** No retry: the CP collector is invoked exactly once per target per run
  (B5).
- **AC-6** 24-hour ledger: a second run inside the window makes **zero** device
  contact; an unreadable ledger blocks the run (B4).
- **AC-7** Pilot allowlist: unset/empty → every endpoint refused by name; an
  endpoint outside the list is refused even when named in `--recovery-gateways`.
- **AC-8** VSX: a `__vsid_` target is refused at request time, before contact
  (B7).
- **AC-9** Spark / Gaia Embedded → `UNSUPPORTED`, zero commands (§7.3 point 8).
- **AC-10** `software_version` unresolvable → artifact **not** stored (B8).
- **AC-11** Credential: no `SECURITYEXPERT_CP_BACKUP_SSH_*` identity → fail
  closed, no fallback to the collection credential (B11).
- **AC-12** Store/manifest: a collected artifact validates V1 and V2 under
  `--recovery-validate`, and reaches V3 when `unified.json` version-matches.
- **AC-13** `"recovery-cp"` remains non-allowlisted; a policy naming it raises
  `SchedulerPolicyError`.
- **AC-14** Privacy: no backup filename in any state file, payload or log; no
  plaintext temp file created at any point in a full run.

All device interaction in AC-1…AC-14 is exercised against a fixture SSH/SCP
transport. **Never a live device in CI** (contracts §11, RB.2's own rule).

## Validation and merge gate

- Full suite one-shot: `py -m pytest -q > pytest_result.log 2>&1`. Baseline on
  `main`: **788 / 3 / 2** with PostgreSQL, **763 / 11 / 2** without; the 2
  failures are pre-existing and unrelated.
- Repository privacy gate PASS / 0.
- Render harness green (no UI change expected from this build, but the ledger
  touches the evidence backend, so the full suite runs).
- **Real-environment validation is mandatory before this build advances past
  `IMPLEMENTED`, and it is a stronger requirement than usual.** Every other
  collector in this repository reads; this one writes to a production firewall's
  disk. The first real run must be a **single, named, non-production-critical
  gateway**, watched, with free space observed before and after, and the
  deletion confirmed. Fleet use follows that run, not this contract.

## Risks

- **`/var/log` exhaustion** — the headline risk, mitigated by the 3× floor, the
  abort-on-unknown rule, the 24-hour ceiling and cleanup. Residual: a device
  whose `/var/log` fills from its own logging between the precondition read and
  the backup completing. Not eliminable from outside the device.
- **Orphaned archives** — if deletion fails repeatedly on an endpoint, disk
  consumption accumulates. AC-3's ineligibility marking bounds it to one
  archive per endpoint, and makes it visible rather than silent.
- **The ledger is new correctness-critical state** (B4). Its failure mode is
  chosen deliberately: unreadable → block. That will produce false refusals
  before it produces a double backup, which is the correct direction.
- **Two command strings are owed at sign-off** (§7.7's free-space form, §7.8's
  deletion form). They are marked as owed rather than assumed; confirming them
  against the R81 Gaia Administration Guide and the estate's Gaia mix is part
  of the gate review, not of implementation.
- **Approving `D3` changes what this platform is.** It is the first
  device-mutating capability. The `operational-write` boundary is only as real
  as the review that keeps `config-write` on the other side of it — `D6`
  decides whether that boundary is written into the protocol or lives only in
  this design.

## Rollback

Restore `checkpoint/checkpoint_recovery_collector.py` to the blocked stub
(`RecoveryCollectionBlockedError`); the `main.py` branch that constructs it is
unchanged either way. Delete `utils/recovery_operational_ledger.py` and its
state. No stored artifact is invalidated by rollback — RB.1/RB.4 read manifests
independently of how they were collected. No schema version changes.

## Definition of done

1. ~~`D3` answered and recorded in architecture §13 (amendment C5); `D4`
   answered; §7.3 point 14 written; §7.7/§7.8 gated and signed off.~~ **DONE
   2026-08-31** — `D3` resolved (architecture §13), `D4` signed off, §7.3 point
   14 signed off, §7.7 / §7.8 signed off (command strings confirm-on-hardware at
   the first R81.10 / R81.20 run), ledger design + §3 rule 5 accepted.
2. AC-1 … AC-14 green against fixture transports. **Steps 2–5 + C6 done
   2026-08-31** — AC-1…AC-6 / AC-12 / AC-14 / C6 / §9.13 (a)(b)(c)(f)(g) green
   in `tests/test_rb3b_cp_backup_device_core.py`; AC-7…AC-11 / §9.13 in the
   step 2–4 suites. AC-13 (`recovery-cp` not allowlisted ⇒ `SchedulerPolicyError`)
   is covered by `tests/test_rb2_recovery_collect.py`.
3. Full suite at or above baseline; privacy gate PASS / 0. **Step 5: 875 / 25 / 2
   (the 2 = documented pre-existing pollution), privacy gate = only the
   gitignored `data/`+`logs/` local noise.**
4. Amendments C1–C6 landed. **C6 landed with step 5** (§9.12 CP-path test).
5. Status `IMPLEMENTED` — **not** `AUTOMATED_VALIDATED` and not `DONE` — until
   step 6 (`main.py` wiring) lands **and** the single-gateway watched
   real-environment run above has happened. **Step 6 landed 2026-08-31**
   (`main.py`'s `--recovery-collect --recovery-vendor checkpoint` branch now
   builds and binds the ledger/store/platform-map/prior-size wiring instead of
   constructing the collector bare); the watched real-environment run remains
   outstanding, so status stays `in_progress`. A device-writing command does
   not get to skip that on the strength of fixtures.
6. **Step 7 landed 2026-08-31** (project metadata / state sync — this
   section, `CURRENT_STATE.md`, `project/build_history.json`,
   `project/roadmap.json`, `project/backlog.json`,
   `project/feature_registry.json`). `docs/AI_DEVELOPMENT_PROTOCOL.md` is
   deliberately untouched — `D6` (adopt `operational-write` permanently) is
   still open.

## Next movement / model

Steps 2–7 are **implemented** (2026-08-31) against fixture transports — step 6
(`main.py` wiring) and step 7 (project metadata) both landed at
**`Sonnet 5, normal`**, as scoped. The device core (step 5) was the one place
the strongest tier earned its cost, and it is done. **What remains is not
another implementation step**: the single, named, non-production-critical
R81.10/R81.20 gateway run, watched, with free space observed before and after
and the deletion confirmed (see "Validation and merge gate") — that run is
external/hardware-gated, not an engineering task, and is what finally moves
this build past `in_progress` to `IMPLEMENTED`. Until then the next
engineering movement on the Recovery track is `RB.3c` (blocked on `D5`/`E1`,
sequenced after this run) or a fresh cold-start pickup of whatever the
product owner prioritizes next.
