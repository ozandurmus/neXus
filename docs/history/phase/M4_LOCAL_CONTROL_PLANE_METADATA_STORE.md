# `M4` — Local control-plane metadata store (approved Option A)

## Status

**IMPLEMENTED — AUTOMATED_VALIDATED PENDING PR CI.** Storage infrastructure
only. No job behaviour, target resolution, runner, scheduler, enrollment,
HTTP route, UI or device contact — those remain `M5`…`M12` and are absent
here by design.

| | |
| --- | --- |
| **Movement** | `IMPLEMENTATION`, serving roadmap `now_next` build `local_control_plane_metadata_store` |
| **Primary contract** | `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §6.4 (ownership boundary), §6.5 (engine contract), `AC-ST-1`…`AC-ST-8` |
| **Capability-projection contract** | `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` §8.2.1, `AC-CS-70`…`AC-CS-73`, `AC-CS-97` |
| **Parent** | `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §10/§19 |
| **Preserves unchanged** | `PCP.1` Device Registry (filesystem JSON, not migrated), `CON.2` job engine and its lifecycle vocabulary, `OP.2` action authority, the admission coordinator, `utils/action_taxonomy.py` |

This document is the deferred implementation contract §13.2 assigns to `M4`:
the exact schema and migration mechanics inside the already-frozen direction.
It amends no frozen contract.

---

## 1. Placement and file identity

| Concern | Decision |
| --- | --- |
| Path | `<data_root>/state/control_plane.db` (`utils/runtime_paths.RuntimePaths.data_root`) |
| Sidecars | `control_plane.db-wal`, `control_plane.db-shm` |
| Never | the repository, inside the repository, `output_root`, or a network share |
| Filesystem | local only — WAL needs shared memory and is unsafe on SMB/NFS; a UNC path is refused before any open, and a `journal_mode` that does not come back `wal` is a refusal, not a downgrade |

`.db` is a deliberate choice, not a default: `utils/repository_privacy.py`
already classifies `.db`, `.db-wal` and `.db-shm` as `DATABASE_ARTIFACT`, and
`.gitignore` already carries all three. The existing privacy mechanisms were
extended by *placement and registration*, not by a parallel system.

Support-bundle exclusion is **structural, not a denylist**:
`utils/support_bundle.run_support_bundle` enumerates only
`data_root/runs/<run_id>` and nine self-generated payload names. Anything under
`data/state/` is out of scope by construction — the same mechanism that already
excludes the Device Registry and its lock file. No `support_bundle.py` change
was needed or made; a regression test proves the absence of enumeration.

---

## 2. SQLite runtime settings

| Setting | Value | Justification |
| --- | --- | --- |
| `journal_mode` | `WAL` | one writer, concurrent readers, better crash behaviour (§6.5) |
| `busy_timeout` | `5000` ms, explicit and configurable | contention waits, then fails closed — never an unbounded block |
| `foreign_keys` | `ON` | §6.5 |
| Table strictness | `STRICT` on every table | §6.5 (3.37+; 3.45.3 recorded locally) |
| `synchronous` | **`FULL`** | see below |

**Why `FULL` and not WAL's usual `NORMAL`.** Under WAL, `NORMAL` does not
fsync at commit: database integrity still survives a crash, but a *committed*
record can be lost to power loss or a kernel panic. `CON.0` §7.9 requires a job
record to be durable **before** the runner may start it, so that guarantee is
not ours to trade away. The cost is one fsync per commit on a local,
low-write control plane — negligible here. `NORMAL` was considered and
rejected on exactly that ground.

Settings are read back from the engine in `runtime_settings()` and asserted
against the engine's own report, not against the constants that set them.

---

## 3. Schema — version 1

`schema_migrations` is explicit, monotonic and transactional: one transaction
per migration, so a failure rolls its own version back entirely and leaves
every earlier version applied.

### 3.1 Ledger validation — an exact known prefix, not `max(version)`

On open, the recorded ledger must be an **exact prefix** of `MIGRATIONS`:
entry *i* must equal `(version, name)` of migration *i*, and the ledger may
not be longer than `MIGRATIONS`.

`max(version)` alone is insufficient and was corrected: a ledger can report
exactly the supported maximum while carrying a foreign migration *name* at
that version, or a gap, an unknown extra entry, or a non-prefix sequence.
Each is a different schema history than this build can reason about, so the
store refuses rather than assuming its own migrations produced what is on
disk. The refusal names the encountered ledger and its highest version, and
the supported ledger and version range.

Because the ledger is a validated exact prefix, the pending set is simply
`MIGRATIONS[len(ledger):]` — no version arithmetic.

Two cases are worth stating precisely, because their testability differs.
In **this build's own ledger** `version` is an `INTEGER PRIMARY KEY`, so rowid
*is* the version: a duplicate version cannot be inserted at all, and SQLite
stores rows in version order, so an out-of-order application history cannot be
recorded. Both become representable only in a **foreign ledger table** written
by another build without that key — where rows are read back in real insertion
order and the prefix check refuses them. Tests cover both shapes.

A ledger table this build cannot even read (a foreign column set) is likewise
a schema-version refusal, not a transient error.

| Table | Owns | Notes |
| --- | --- | --- |
| `schema_migrations` | `version` (PK), `name`, `applied_at_utc` | monotonic; `max(version)` is the current version |
| `control_plane_metadata` | `key` (PK), `value`, `updated_at_utc` | control-plane runtime metadata; opaque, no secrets |
| `job_definitions` | `definition_id` (PK), `job_type`, `target_ref`, `target_ref_kind`, `enabled`, `created_at_utc` | `target_ref_kind` is constrained to `device_id` \| `logical_entity_id` |
| `job_submissions` | `idempotency_key` (**PK**), `definition_id` → FK, `provenance`, `submitted_at_utc` | uniqueness is structural, not a checked convention; `provenance` ∈ `manual`/`scheduled`/`console`/`system` |
| `job_runs` | `run_id` (PK), `definition_id` → FK, `idempotency_key` → FK, `state`, timestamps, `error_code` | `state` `CHECK`-constrained to the frozen vocabulary |
| `schedules` | `schedule_id` (PK), `definition_id` → FK, `interval_seconds`, `enabled`, `created_at_utc` | storage only; the ≥ 10 min floor and default-disabled cadence policy stay `M12`'s |
| `capability_projections` | see §5 | |

**No third identifier was invented.** `console/jobs.py` already carries
`job_id` + `idempotency_key`; making the idempotency key the submission
primary key avoids adding a competing "submission id".

### Enforced invariants

```sql
-- at most one active run per durable job definition
CREATE UNIQUE INDEX ux_job_runs_one_active_per_definition
    ON job_runs (definition_id)
    WHERE state IN ('queued', 'running');
```

- unique idempotency key per job submission — primary key;
- at most one active run per definition — partial unique index, an engine
  invariant rather than a race-prone read-then-write check;
- foreign-key integrity — `PRAGMA foreign_keys=ON` plus real `REFERENCES`;
- transactional rollback on failure — every write runs inside an explicit
  `BEGIN IMMEDIATE` … `COMMIT`/`ROLLBACK`.

---

## 4. Lifecycle vocabulary — bound, not extended

`queued`, `running`, `succeeded`, `failed`, `blocked`, `skipped` — the frozen
`CON.2` vocabulary (`console/jobs.py`), bound as `X1` by the `M3` contract,
which may gain no member. It is declared as a literal in
`utils/control_plane_store.py` so `utils/` keeps no dependency on `console/`,
and a test asserts the two agree so a competing lifecycle cannot drift in
unnoticed. The schema `CHECK` accepts these six and nothing else.

---

## 5. Capability projections — the persisted object only

Per `M3` §8.2.1's two-object boundary, the table holds the **capability
projection**, never the **presentation resolution**.

**Persisted:** `subject_ref` + `subject_ref_kind`, `capability`, `vendor`,
`platform`, `entity_kind` (semantic identity); `dimension_values` (`D2`–`D6`);
`basis_run_ref` (basis/run information); `producer_version`,
`support_rule_version`, `authority_generations` (producer/support-rule
context); `identity_mapping_proven`; `computed_at_utc`.

**Absent by construction, and test-enforced:** `primary_status`,
`capability_qualifiers`, `evidence_presentation`, shell composition,
`action_affordance`, action declarations, taxonomy class, authorization state.
A projection carrying those would silently become an authorization or
admission record.

- `authority_generations` retains **every** consumed authority generation, and
  `producer_version`/`support_rule_version` the rule versions, so reuse can be
  *affirmatively* validated (`AC-CS-71`). Missed invalidation is assumed.
- `identity_mapping_proven` is `0` wherever the canonical join would need an
  unproven translation, which forbids reuse across that mapping (`AC-CS-97`).
- `UNIQUE (subject_ref, capability, vendor, platform, entity_kind)` binds a
  projection to the exact identity it was computed under.
- A cached projection is never endpoint, authorization, admission or execution
  authority (`AC-CS-73`). Nothing here can become one: there is no endpoint
  and no authorization column to read.

---

## 6. Typed fail-closed outcomes

All derive from `ControlPlaneStoreError(RuntimeError)`, following the
per-module convention (`DeviceRegistryError`, `EvidenceBackendError`); the
repository has no shared base exception to extend.

| Error | Raised when |
| --- | --- |
| `ControlPlaneStorePlacementError` | repository, `output_root`, network share, or WAL refused |
| `ControlPlaneSchemaVersionError` | database version exceeds this build's; **names both** encountered and supported |
| `ControlPlaneCorruptionError` | corrupt, or not a database at all |
| `ControlPlaneMigrationError` | a migration failed and was rolled back |
| `ControlPlaneContentionError` | the bounded `busy_timeout` elapsed |
| `ControlPlaneIntegrityError` | uniqueness or foreign-key refusal |

**Corruption and unsupported-version posture.** Both refuse and stop. The
store never auto-repairs, silently recreates, downgrades or discards state —
the file is left byte-for-byte untouched, which is asserted directly.

`sqlite3.connect` is lazy, so a garbage file would otherwise open cleanly and
fail on first use; an explicit probe at open makes "is this a database?" an
open-time question with a typed answer.

### 6.1 Open order — refusal precedes every persistent mutation

Open is ordered so that everything which can refuse the store runs **before**
anything that changes it on disk:

```
connect  →  connection-local settings (busy_timeout, foreign_keys)
         →  corruption probe
         →  ledger read + exact-prefix validation      ← refusals end here
         ------------------------------------------------ store accepted
         →  PRAGMA journal_mode = WAL   (persistent)
         →  PRAGMA synchronous = FULL
         →  create ledger table, apply pending migrations
```

`busy_timeout` and `foreign_keys` are connection-local and write nothing, so
they are safe before the decision. `journal_mode` is a **persistent database
property**, so WAL activation was moved after validation: refusing a store
must not leave its journal mode changed. Creating the ledger table likewise
moved after validation — it is a schema mutation. Tests prove a refused
database keeps both its journal mode and its ledger, gains no table, and grows
no `-wal`/`-shm` sidecars.

**Deterministic connection cleanup.** Every connection the store opens is
closed on any failure, whatever the cause: `_connect` closes its own handle if
configuration fails (the only place that handle is reachable), and the open
sequence closes the connection on `BaseException` before re-raising, so a
failed open never leaves a live connection or a half-open store. `open_reader`
follows the same pattern. Tests assert this for a configuration failure, a
corruption refusal and a ledger refusal, by tracking every connection the
module creates and checking each is closed.

---

## 7. Ownership boundary — what stays out

Proven against the **real schema** rather than a hand-maintained list: a test
walks every column of every table and fails on any name containing an
endpoint, address, hostname, credential, password, secret, token, passphrase,
private-key, trust, raw-configuration, backup-bytes or CAS fragment.

The Device Registry is not migrated, mirrored or read. Tests assert that
opening the store creates no registry file, and that registry enrollment and
listing behave identically alongside an open store.

**SQLite is not the production engine.** `pcp_storage_engine` stays open and
production-scoped (`AC-ST-7`). `select_control_plane_metadata_backend` refuses
`SECURITYEXPERT_EVIDENCE_BACKEND=postgres` explicitly rather than silently
falling back, naming the open decision — the same posture as
`select_device_registry_backend`. Runtime auto-creation is a pre-production
local convenience; production applies migrations through a
deployment-controlled step (§6.5, `DEV.4.6`).

---

## 8. Validation

`tests/test_m4_control_plane_metadata_store.py` — 74 focused tests covering
RuntimeRoot placement and per-test isolation; WAL/foreign-keys/STRICT/timeout/
synchronous read back from the engine; creation and monotonic migrations;
migration rollback; corruption refusal without repair or recreation;
uniqueness and one-active-run; concurrent reads and bounded writer contention;
Device Registry non-migration and unchanged behaviour; absence of copied
endpoints and forbidden data; support-bundle exclusion, DLP classification and
gitignore coverage; capability-projection persistence boundaries.

Ledger validation and cleanup (the PO correction round) add: supported version
under a wrong migration name; unknown lower and extra versions; non-prefix and
gapped sequences; out-of-order and duplicated *foreign* ledgers, plus the
structural impossibility of a duplicate in our own; a valid shorter prefix
still being accepted and completed; refusal naming both ledgers; refusal
applying and discarding nothing; connection closure on configuration failure,
corruption refusal and ledger refusal; and journal-mode plus ledger
preservation across refusal.

Contention is proven non-vacuously: the writer genuinely waits the configured
bound and then raises the typed refusal.

**Automated tests do not prove production readiness or real-environment
validation.** Nothing here contacted a device, and no production deployment
model is claimed.
