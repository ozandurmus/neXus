# RB.3a — CP Gaia backup/snapshot attestation (`show backups` / `show snapshots`)

## Status

**CONTRACT FROZEN 2026-08-31 — cleared for implementation.**

**IMPLEMENTED / AUTOMATED_VALIDATED 2026-08-31** on `feature/rb-3a-attestation`.
`checkpoint/checkpoint_recovery_attestation.py`,
`utils/recovery_collect.run_recovery_attestation` + `RecoveryAttester`,
`data/state/recovery_attestations.json`
(`securityexpert-recovery-attestations-v1`), `main.py --recovery-attest`, and
the attestation read in `--restore-readiness-check`. Amendments C1 and C2
landed in `docs/design/BACKUP_RECOVERY_CONTRACTS.md` (C3 was already §7.5
points 5-7). `utils/restore_readiness.py` unchanged. AC-1…AC-10 covered by
`tests/test_rb3a_recovery_attestation.py` (33 tests); full suite
804 passed / 20 skipped / 2 pre-existing unrelated failures. Repository
privacy gate clean on tracked source. **Real-environment validation is owed**
and is not satisfied here — no live CP device is reachable from this
workspace; added to `on_hardware_real_env_validation`. Per `AGENTS.md` this
build reaches `AUTOMATED_VALIDATED`, never `DONE`.

- Gate: network-device command gate, class `read`,
  `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.5 — **SIGNED OFF 2026-08-31
  (product owner)**, points 1-7, including the frozen command tuple (A4) and the
  platform-gating rule (A8) that amendment C3 added to the gate text. This was
  the only approval this build needed.
- **Not blocked on open decision `D3`.** `D3` governs the `operational-write`
  class (`add backup local`, RB.3b). `show backups` / `show snapshots` change
  no device state and consume no device resource; they are `read`, and §7.5
  point 4 already says this slice is "worth gating first, independently of
  `RB.3`".
- The P0 `cp_device_interaction_safety` audit **closed 2026-08-25**
  (`project/backlog.json`, status `done`). It is not a co-blocker. Several
  documents cited it as open until a correction on 2026-08-30; do not
  reintroduce that claim.
- Predecessors: `RB.0` (AUTOMATED_VALIDATED), `RB.1` (AUTOMATED_VALIDATED),
  `RB.2` (IMPLEMENTED, real-env owed), `RB.4` (AUTOMATED_VALIDATED).

## Objective

Populate the `attestations` parameter that `utils/restore_readiness.py`
already accepts and nothing currently supplies.

Today `--restore-readiness-check` calls `compute_restore_readiness(unified_devices)`
with no manifests and no attestations, so every Check Point device in the fleet
reports `UNPROTECTED` — correct, but uninformative: it cannot distinguish "this
gateway has no backup anywhere" from "this gateway has held a local Gaia
snapshot for 41 days that we have never counted".

This build asks each Check Point physical endpoint what recovery artifacts it
believes it holds, and records that as **attested-but-unheld** evidence —
architecture §7's third readiness input, between "we hold a validated
artifact" and "nothing".

It does **not** collect a backup. No artifact leaves any device.

## Scope

### In scope

1. **`checkpoint/checkpoint_recovery_attestation.py`** — a new module that
   opens one SSH session per physical endpoint, runs exactly two frozen Clish
   commands, parses their output into attestation records, and returns them.
   No store write, no encryption, no artifact.
2. **`utils/recovery_collect.run_recovery_attestation`** — a second entry point
   alongside `run_recovery_collection`, reusing `select_recovery_targets` and
   the same caller-supplied `run_under_admission` hook. Vendor-neutral: it does
   target selection and admission routing, never Clish.
3. **`data/state/recovery_attestations.json`** — a new evidence-plane state
   file (`securityexpert-recovery-attestations-v1`), read by
   `--restore-readiness-check` and passed straight into
   `compute_restore_readiness(attestations=)`.
4. **`main.py --recovery-attest`** — thin dispatch only, mirroring
   `--recovery-collect`. Reuses `--recovery-gateways` for selective targeting.
5. **`--restore-readiness-check` reads the attestation file when present**,
   and still works identically when it is absent.
6. **Contract amendments** to `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §5 and
   §10.3 (below), and correction of one stale `main.py` help string.

### Explicitly out of scope

- `add backup local`, SCP fetch, deletion — RB.3b, `operational-write`, `D3`.
- Management export / consistency groups — RB.3c.
- **Scheduling.** `"recovery-attest-cp"` deliberately does **not** join
  `ALLOWLISTED_WORKFLOWS`. See design decision A9.
- Any change to the recovery store, manifest schema, or `RMA_GRADE_BY_CLASS`.
- The Recovery UI module (RB.5). This build changes no payload builder,
  `templates/`, `static/app.js` or `static/style.css`, so the render harness is
  not triggered by it — but the full suite still runs it.

## Design decisions

### A1 — An attestation is not an artifact, and must not travel through the recovery store

Contract §5 frozen rule 1: `held_artifacts` and `attested_not_held` are
**never merged**. The cheapest way to violate that rule would be to write an
attestation into the recovery store as a zero-byte artifact so it "shows up"
in the same place. This contract forbids it.

Attestations live in the **evidence plane** (`data/state/`), like
`restore_readiness.json` itself and for the same reason: derived metadata, no
payload, must render before any recovery volume exists.

Corroborating evidence that the existing code already assumes this:
`RMA_GRADE_BY_CLASS` in `utils/recovery_manifest.py` has entries for
`cp_gaia_backup`, `cp_mgmt_export`, `cp_mds_backup` and the two PAN classes —
and **no** `cp_gaia_snapshot`. The snapshot class exists only in the
attestation vocabulary (§5's example), because architecture §3.1 decided the
platform never pulls snapshots. Adding it to the manifest table would be the
first step of exactly the merge rule 1 forbids.

### A2 — A separate entry point, not a `RecoveryCollector`

`RecoveryCollector.collect()` returns `(plaintext_bytes, artifact_meta)`, and
`run_recovery_collection` unconditionally calls `write_artifact` on every
success. An attestation has no plaintext. Forcing it through that protocol
would mean either fabricating bytes or special-casing the vendor-neutral
orchestrator on vendor behaviour — both defeat the §10.2 separation.

So: a sibling function `run_recovery_attestation(request, *, unified_devices,
attester, run_under_admission=None) -> RecoveryAttestationResult`, sharing
`select_recovery_targets`, `RecoveryCollectionTarget`,
`RecoveryCollectionError` and the batch-failure semantics (one endpoint's
failure never aborts the rest), but with its own `RecoveryAttester` protocol:

```
RecoveryAttester.attest(target) -> list[dict]   # [{class, age_days, source}, ...]
```

### A3 — Attestation is per physical endpoint; a VSX virtual system inherits nothing

`show backups` and `show snapshots` are Gaia-host-level. Contract §7.3 point 3
already establishes that this command family "is not valid inside a VSX
virtual-system context".

Therefore: targets are **deduplicated to physical endpoints** before any device
is contacted, and the resulting attestation is recorded against the physical
endpoint's `entity_id` only. A virtual-system entity (`<device>__vsid_<vs_id>`)
receives **no** attestation record and stays `UNPROTECTED`.

This is deliberate and must not be "improved" later without a decision: a Gaia
snapshot of a VSX host is not a per-virtual-system recovery artifact, and
crediting the VS with the host's snapshot would be precisely the invented
certainty `AGENTS.md` forbids. The honest report is that the VS has no recovery
artifact of its own — which is true.

### A4 — A frozen two-command tuple, not a prefix test

`configuration/checkpoint_config_collector._is_allowed_read_command` admits any
command starting with `show ` plus one literal exception. That is appropriate
for a collector that runs a broad, evolving read set. It is **not** appropriate
here.

The attestation module carries its own guard:

```python
_ATTESTATION_COMMANDS = ("show backups", "show snapshots")   # frozen; §7.5
```

Anything else raises before the wire. A prefix test is how an ungated command
arrives in a later edit without anyone noticing; an explicit tuple makes
widening it a visible diff that trips the gate.

### A5 — Filenames never leave the module

Gaia backup and snapshot names embed hostnames and dates (§7.5 point 3;
architecture §10 rule 6 treats a backup filename as an operational identity).

The module parses a name into `(class, age_days)` and **discards the name**.
`recovery_attestations.json` and `restore_readiness.json` carry
`{class, age_days, source}` and nothing else. Any diagnostic line goes through
`utils/logger.py` redaction. A test asserts no parsed name appears in either
state file.

### A6 — `age_days` is nullable; an undatable artifact is still evidence

Gaia's `show backups` / `show snapshots` output format varies across releases.
The parser is bounded and fail-closed: it emits `age_days` **only** when a date
parsed unambiguously into a UTC calendar date.

When the device clearly reports an artifact but its date does not parse, the
record is emitted as `{"class": "...", "age_days": null, "source":
"device_reported"}` rather than dropped. Dropping it would report `UNPROTECTED`
for a device that demonstrably holds something; inventing an age would be worse.

`utils/restore_readiness._classify_device` reads only `if attested:` and never
touches `age_days`, so this is behaviourally safe today. It **is** a change to
the §5 shape and is recorded as amendment C1 below.

When *nothing* parses — the command errored, returned an unrecognised format,
or the session failed — the endpoint gets **no** attestation entry and the run
report records the reason. The device then reports `UNPROTECTED` (or `UNKNOWN`
on inventory grounds), never a guess.

### A7 — Session and frequency bounds come from §7.5, not from convenience

- One SSH session per physical endpoint runs **both** commands (§7.5 point 2,
  session reuse).
- Timeout 60 s per command; 1 retry; ceiling 1 per endpoint per hour.
- Admission via the existing `execute_admitted_collection` hook. **The per-vendor
  concurrency budget stays at 1** — `CURRENT_STATE.md` standing priority 1/2
  are untouched by this build, and nothing here justifies raising them.

### A8 — Platform gating uses the lifecycle classification, never shell behaviour

Spark / Gaia Embedded is `UNSUPPORTED` and receives no command at all. The
determination comes from the discovery-lifecycle platform classification
(propagated by the `cp_unknown_platform` build), **not** from whether the
device landed directly in Clish — `AGENTS.md`: "Do not infer Spark/Gaia
Embedded solely from direct-Clish behavior."

An endpoint whose platform is unknown is attested normally; an unknown platform
is not a reason to skip a `read`-class command, and the parser already
fail-closes on unrecognised output.

### A9 — No scheduler entry in this build

`"recovery-attest-cp"` does **not** join
`utils.collection_executor.ALLOWLISTED_WORKFLOWS`.

`CURRENT_STATE.md` standing priority 2: do not increase recurring polling
frequency or concurrency. Allowlisting the workflow would let a policy file
schedule fleet-wide SSH at any interval ≥ 10 minutes, which is a different
decision from "may this command run at all". RB.3a delivers the on-demand CLI
path; scheduling is a separate, explicit ask with its own review. The allowlist
comment records why the name is absent, in the same style as the existing
`"recovery-cp"` note.

### A10 — Stale help-string correction

`main.py`'s `--recovery-vendor` help currently reads: *"'checkpoint' (CP Gaia
backup) is a blocked stub — P0 cp_device_interaction_safety audit + open
decision D3, neither resolved."*

The audit closed 2026-08-25. The string is corrected to name `D3` alone. This
is the same stale claim `project/backlog.json` and the design docs already
corrected on 2026-08-30; `main.py --help` was missed.

## Correctness contract

1. `run_recovery_attestation` contacts a device only after
   `run_under_admission` admits it, and never for a target excluded by A3 or A8.
2. An unresolvable `entity_id` in an explicit `--recovery-gateways` list is a
   request-time error before any device is contacted — identical to
   `select_recovery_targets`' existing behaviour, because it is the same
   function.
3. One endpoint's failure is recorded and the batch continues.
4. `compute_restore_readiness` with attestations and no held artifacts yields
   `PARTIAL` / `only_device_attested_artifact_no_held_copy` /
   `evidence_basis: "device_attestation"`. **It never yields `READY`** — §5
   frozen rule 2 requires a held artifact at ≥ V3.
5. `recovery_attestations.json` is rewritten whole on each run; a corrupt or
   unreadable file degrades `--restore-readiness-check` to "no attestations",
   never to an error (the same fail-safe posture as
   `utils/compliance_history.py`).
6. No command outside `_ATTESTATION_COMMANDS` reaches the wire.

## Privacy and safety invariants

Tested obligations, per architecture §10 / contracts §9:

- No backup or snapshot **name** appears in `recovery_attestations.json`,
  `restore_readiness.json`, `output/index.html`, the support bundle, or any log
  line (A5).
- No recovery payload byte is read, transferred or stored — the commands return
  a listing, not an archive.
- The repository privacy gate stays PASS / 0.
- No new credential and no new authentication transport: the existing CP SSH
  identity and `utils/cp_ssh_trust.apply_strict_host_key_policy` are reused
  verbatim.
- The attestation state file contains no network identity — `entity_id`,
  `class`, `age_days`, `source` only.

## Contract amendments required (design docs)

- **C1 — `BACKUP_RECOVERY_CONTRACTS.md` §5:** `attested_not_held[].age_days` is
  **nullable**. `null` means "the device reports an artifact whose date did not
  parse", and is distinct from the key being absent. Readiness classification is
  unaffected (it tests presence, not age).
- **C2 — §10.3 collector-availability table** gains a row: CP attestation
  (`show backups` / `show snapshots`, §7.5) — status, and the fact that it is a
  `RecoveryAttester`, not a `RecoveryCollector`, with A2's reasoning.
- **C3 — §7.5** gains the frozen command tuple (A4), the platform-gating rule
  (A8) and the per-physical-endpoint rule (A3) as explicit gate text.
  **Landed 2026-08-31 as §7.5 points 5-7, and covered by the sign-off.**

## Implementation plan

1. Amendments C1–C3 to the two design docs. No code yet.
2. `utils/recovery_collect.py`: `RecoveryAttester` protocol,
   `RecoveryAttestationOutcome` / `RecoveryAttestationResult`,
   `run_recovery_attestation`. Pure orchestration; no Clish.
3. `checkpoint/checkpoint_recovery_attestation.py`: session open (reusing the
   established CP SSH connect + strict-host-key preflight pattern), the frozen
   command tuple, the bounded parser, name discard.
4. `utils/restore_readiness.py`: unchanged. (This is the point — the parameter
   already exists.)
5. `main.py`: `--recovery-attest`, `--recovery-gateways` reuse, the
   attestation-file read in `--restore-readiness-check`, and the A10 help fix.
6. Tests (below).
7. `CURRENT_STATE.md`, `project/backlog.json` (`native_backup`),
   `project/roadmap.json`, `project/build_history.json`.

## Acceptance criteria

- **AC-1** Parser: fixture outputs covering at least two Gaia listing formats,
  an empty listing, an error/permission-denied response, and an undatable
  entry → correct `(class, age_days)` tuples, `age_days: null` for the
  undatable one, no entries for the error case.
- **AC-2** VSX: a fixture fleet with a VSX host and two virtual systems
  contacts the host exactly **once** and produces an attestation for the host
  entity only; both VS entities remain `UNPROTECTED`.
- **AC-3** Store isolation: a full attestation run leaves the recovery store
  byte-identical (no new artifact dir, no ledger entry, no manifest).
- **AC-4** Readiness: attestations in, `PARTIAL` /
  `only_device_attested_artifact_no_held_copy` out; never `READY`; a held
  validated artifact still outranks an attestation for the same entity.
- **AC-5** Name redaction: no parsed name appears in either state file or in
  captured log output.
- **AC-6** Command guard: attempting any command outside `_ATTESTATION_COMMANDS`
  raises before the wire.
- **AC-7** Platform: a Spark / Gaia Embedded endpoint is recorded `UNSUPPORTED`
  with **zero** commands sent.
- **AC-8** Batch: with three endpoints where the middle one fails admission,
  the other two are attested and the failure is reported per endpoint.
- **AC-9** Fail-safe: a corrupt `recovery_attestations.json` degrades
  `--restore-readiness-check` to "no attestations", exit 0.
- **AC-10** `"recovery-attest-cp"` is **not** allowlisted: a scheduler policy
  naming it raises `SchedulerPolicyError` at load time.

## Validation and merge gate

- Full suite one-shot, file-backed: `py -m pytest -q > pytest_result.log 2>&1`.
  Baseline to beat on `main`: **788 passed / 3 skipped / 2 failed** with a live
  PostgreSQL, **763 / 11 / 2** without. The 2 failures are the documented
  pre-existing unrelated ones; zero new failures.
- Repository privacy gate **PASS / 0** (delete gitignored `data/` and `logs/`
  first).
- Render harness: not triggered by this build's changes (no `templates/`,
  `static/`, or payload-builder edit) but must stay green in the full suite.
- **Real-environment validation is owed and is not satisfied here.** No live CP
  device is reachable from this workspace; automated tests exercise fixtures
  only. Per `AGENTS.md` this build can reach `AUTOMATED_VALIDATED`, never
  `DONE`. Add it to `on_hardware_real_env_validation`.

## Risks

- **Parser drift across Gaia releases.** The listing format is not a stable
  contract. Mitigated by fail-closed parsing (A6) — an unrecognised format
  produces no attestation, never a wrong age. Real-env validation will likely
  add a format.
- **An attestation is weak evidence and reads as reassurance.** A device
  claiming a 41-day-old snapshot has told us a snapshot exists, not that it is
  restorable, not that it is current, and not that anyone can reach the box to
  use it. §5 rule 1 and RB.5's UI must keep held and attested visually distinct;
  this contract deliberately never promotes an attestation above `PARTIAL`.
- **Fleet-wide SSH is still fleet-wide SSH.** Two 60-second reads per endpoint
  under a concurrency budget of 1 is a long serial walk on a large estate. The
  ceiling is 1/hour and there is no scheduler entry (A9), so exposure is bounded
  to explicit operator runs.
- **A VSX-heavy estate will look worse, not better, after this build** — VS
  entities stay `UNPROTECTED` while their hosts move to `PARTIAL`. That is the
  honest reading (A3) and should be stated when the numbers are reported, not
  discovered later.

## Rollback

Delete `checkpoint/checkpoint_recovery_attestation.py`, the
`run_recovery_attestation` block in `utils/recovery_collect.py`, the
`--recovery-attest` flag and the attestation read in
`--restore-readiness-check`. `utils/restore_readiness.py` is unchanged by this
build, so readiness reverts to its current inventory-only behaviour with no
migration. `data/state/recovery_attestations.json` is runtime state and may be
deleted.

## Definition of done

1. ~~§7.5 command gate signed off (including C3's added text).~~ **Done
   2026-08-31.**
2. AC-1 … AC-10 green.
3. Full suite at or above baseline; privacy gate PASS / 0.
4. Design-doc amendments C1–C3 landed.
5. Project metadata updated (`CURRENT_STATE.md`, roadmap, backlog,
   build_history).
6. Status recorded as `AUTOMATED_VALIDATED`; real-environment validation
   explicitly listed as owed.

## Next movement / model

`IMPLEMENTATION` at **Sonnet 5, normal**. The orchestration, target selection,
admission routing and readiness consumer all already exist; the genuinely new
surface is one module with two commands and a bounded parser. Extended thinking
is not warranted — the architecture decisions are made above, and the only
judgement left at implementation time is parser tolerance, which A6 already
resolves in the fail-closed direction.
