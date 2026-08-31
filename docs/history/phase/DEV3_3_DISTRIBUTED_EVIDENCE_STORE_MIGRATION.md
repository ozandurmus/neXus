# DEV.3.3 — Distributed evidence store migration (CAS metadata index, run manifests, last-known-good, scheduler state)

## Status

**CONTRACT_FROZEN 2026-08-31.** Architecture/audit pass complete; contract
reviewed and accepted by the product owner the same day, including E1 (see
below). Implementation proceeds against this frozen contract.

**E1 resolved: Option 1 — full fidelity.** `device`/`management_ip`/
`entity_id` are stored in the `config_snapshot` table exactly as they exist
in `metadata.json` today; no tokenization layer. The Postgres instance is
documented as a CLASS 2 identity-bearing asset in
`PRIVACY_AND_DATA_HANDLING.md` ("Distributed evidence store (DEV.3.3,
opt-in)"), on the stated assumption — already true for `DEV.3.2` — that the
instance is dedicated to this product, not a shared multi-tenant database.

Product baseline: `0.7.7` / `DEV.3.2 AUTOMATED_VALIDATED`. Backlog id:
`distributed_evidence_store_migration` (P0, `planned`), split from
`distributed_endpoint_lock_and_job_store` per the DEV.3.2 contract's scope
split — this is the evidence-integrity half; it does not gate device safety
or `per_vendor_worker_split` (DEV.3.2 already unblocks that).

## Objective

`DEV.3.1` (Linux worker image + Compose) and `DEV.3.2` (distributed
per-endpoint lock) together make multi-container/multi-process collection
safe. They do not make the **evidence produced by that collection**
consistent across containers: four state stores are still per-container local
files, each with its own way of quietly diverging or racing the moment more
than one container's local disk is involved:

| Store | File(s) today | Module | Divergence hazard under >1 container |
| --- | --- | --- | --- |
| CAS metadata index | `data/configs/<source>/<entity>/<snapshot>/{metadata.json,sha256.txt,*.ref.json}` | `utils/config_evidence.py`, read by `utils/config_storage.py`, `utils/config_history.py` | history/"latest" queries only see snapshots written by *that* container; `_latest_metadata`'s `change_state` computation ("first"/"same"/"changed") goes wrong the moment two containers write the same entity |
| Run manifests | `data/runs/<run_id>/manifest.json` | `utils/run_context.py` | fine per-run today (one run owns one manifest) but nothing ties a fleet's runs together for cross-container observability; a future ops/UI view of "recent runs across the fleet" cannot exist without walking every container's disk |
| Last-known-good | `data/state/last_known_good.json` | `utils/snapshot.py` | **the sharpest hazard**: a full read-modify-write of one monolithic JSON document per run. Two containers each running a subset of the fleet overwrite each other's entities' LKG state — the losing container's devices silently regress to `no_data`/`unknown` on the next merge even though they were successfully collected moments earlier |
| Scheduler state | `data/state/scheduler_state.json` | `utils/collection_executor.py` (`load_scheduler_state`/`write_scheduler_state`) | `DEV.3.2`'s scheduler advisory lock already serializes the read-evaluate-write *cycle* across processes, but the state it protects is still a per-container file — two containers each holding the lock at different times still see two different `scheduler_state.json`s unless they share a filesystem |

Move all four to PostgreSQL as an **opt-in** backend, byte-compatible with
today's file-based reader/writer contracts. **Content-addressed payload
blobs stay on the volume** (`data/artifacts/config/sha256/**`) — this
contract only moves the small metadata/index/state documents that point at
those blobs, per the backlog item's own scope line.

## Scope

### In scope

- **`utils/config_evidence.py`** (`ConfigEvidenceStore`) — `_write_snapshot`'s
  metadata/sha256.txt/ref-file triple becomes one row insert on the Postgres
  path; `_latest_metadata` becomes one indexed query. Blob write (`_ensure_blob`)
  is untouched — it is explicitly excluded from this migration.
- **`utils/config_history.py`** (`ConfigHistoryService`) — timeline build
  (`_build_timeline`) and specific-snapshot lookup (`_read_snap_metadata`) read
  through the same backend `config_evidence.py` writes through, instead of
  `entity_dir.iterdir()` / `metadata_path.read_text()`.
- **`utils/config_storage.py`** — `analyze_configuration_storage` /
  `deduplicate_legacy_storage` are migration/cleanup tools for a *filesystem-only*
  problem (a legacy per-snapshot payload copy sitting next to the CAS object).
  On the Postgres metadata backend that problem cannot exist (a Postgres row
  never embeds a payload copy). Scope here is narrow: both functions gain a
  backend check and return a clear `"not_applicable_on_postgres_backend"`
  result instead of silently reporting `0` — see "Explicitly out of scope".
- **`utils/snapshot.py`** (`build_failure_aware_snapshot`) — replace the
  monolithic `last_known_good.json` read-modify-write with per-`(source,
  entity_key)` row reads/writes. This is the one sub-migration that is a
  **correctness fix**, not just a location change (see "Design decisions" D2).
- **`utils/run_context.py`** (`RunContext`) — `write_manifest` persists the
  same payload dict it builds today, to a row instead of a file, on the
  Postgres path.
- **`utils/collection_executor.py`** — `load_scheduler_state` /
  `write_scheduler_state` gain a Postgres-backed pair with the identical
  `dict[str, datetime]` contract; the scheduler advisory lock added in
  `DEV.3.2` is the concurrency control this relies on (no new locking here).
- **Backend selection** — new, independent env var
  `SECURITYEXPERT_EVIDENCE_BACKEND` = `filesystem` (default) | `postgres`,
  plus `SECURITYEXPERT_EVIDENCE_POSTGRES_DSN`. Deliberately **not** reusing
  `SECURITYEXPERT_COORDINATOR_BACKEND` — the backlog split these into
  independent contracts/risk classes on purpose; an operator may run the
  lock plane on Postgres, the evidence plane on Postgres, both, or neither,
  independently. They may point at the same physical instance if the
  operator chooses; this contract does not require or assume that.
- **Schema** — four new tables (`config_snapshot`, `run_manifest`,
  `last_known_good_entity`, `scheduler_state`); see D3.
- **Startup preflight** — connectivity + `CREATE TABLE IF NOT EXISTS`
  (mirrors `PostgresCoordinatorBackend._ensure_schema`). No advisory-lock /
  pooling preflight is needed here — see D6, this backend does not use
  session-held locks.

### Explicitly out of scope

- **Content-addressed payload blobs** (`data/artifacts/config/sha256/**`) —
  stay on the volume, unchanged, per the backlog item's own text.
- **Backfilling existing filesystem history into Postgres.** A fresh
  Postgres-backed deployment starts with empty tables; pre-existing
  `data/configs/**` / `data/runs/**` / `data/state/*.json` content remains
  readable only via the filesystem backend. A one-shot import tool is a
  candidate future item, not part of this contract's Definition of Done
  (mirrors `DEV.3.2`'s equivalent no-backfill decision for coordinator state).
- **`config_storage.py`'s dedup/analysis becoming Postgres-aware.** See
  in-scope note above — it becomes backend-gated, not reimplemented.
- Any collector, transport, command, timeout, retry, cooldown, or admission
  change — this contract touches storage only. `DEV.3.2`'s coordinator and
  lock behavior are untouched and not a dependency beyond reusing its
  scheduler advisory lock for the scheduler-state race (D5).
- Any change to the concurrency budget, the network-device command gate, or
  write/config-changing capability.
- A `docker-compose.yml` Postgres service. `DEV.3.2` shipped without one
  (validated against a session-provided local instance); this contract
  follows the same precedent — opt-in via env var, no compose wiring.
- LISTEN/NOTIFY, replication, or any HA topology for the Postgres instance
  itself — deployment-level concern, not this contract's.

## Design decisions

### D1 — Four narrow backends, not one generic key-value protocol

**Decision:** each of the four stores gets its own small backend interface
(`ConfigSnapshotBackend`, `RunManifestBackend`, `LastKnownGoodBackend`,
`SchedulerStateBackend`), each with a `Filesystem*` (today's exact behavior,
default) and `Postgres*` implementation, living in one new module
`utils/evidence_backend.py` (mirrors `utils/coordinator_backend.py`'s
one-file, multiple-backend-classes shape). **Rejected:** a single generic
`get(namespace, key)`/`put(namespace, key, value)` document-store abstraction.

Reason: the four stores have genuinely different query shapes today —
`config_snapshot` needs "latest successful row for `(source, entity_id,
artifact_type)`" and full timeline scans; `run_manifest` is a whole-document
upsert keyed by `run_id`; `last_known_good_entity` is per-row upsert keyed by
`(source, entity_key)`; `scheduler_state` is a small full-dict read + per-key
write. Forcing all four through one KV protocol would mean bolting
query-specific methods onto a "generic" interface anyway, with none of the
benefit (SQL indexes match the real access pattern; a fake-generic layer
would not).

### D2 — Last-known-good becomes per-entity rows; this is a correctness fix, not just a relocation

**Decision:** `last_known_good_entity(source, entity_key, item_json JSONB,
last_successful_collection TIMESTAMPTZ, updated_at TIMESTAMPTZ)`, one row per
device/VS/PAN-serial key, written with a single `INSERT ... ON CONFLICT
(source, entity_key) DO UPDATE` per entity per run.

Today's `_save_lkg` rewrites the **entire** `last_known_good.json` (all
sources, all entities) on every run (`utils/snapshot.py:127-131`). Under two
containers each collecting a disjoint subset of the fleet, container B's
whole-file rewrite silently discards container A's just-written entities that
aren't in B's own in-memory `state` dict for this run (B loaded a stale
snapshot of the file, mutated only its own entities, and wrote the whole
thing back) — the losing devices regress from `live`/`last_known_good` to
`no_data` on the very next merge, which is exactly the failure class
`build_failure_aware_snapshot` exists to prevent, now reintroduced one layer
up. Per-row upsert removes this hazard structurally: container B's write
touches only the rows for entities it actually observed.

**See amendment A1** — per-row storage alone does not deliver this; the
caller's access pattern in `build_failure_aware_snapshot` has to become
per-entity as well, or the same lost-update race simply reappears against the
table instead of the file.

This is the one place in this contract where the Postgres path is not just
"the same behavior on a different store" — it is strictly safer than today's
filesystem behavior under concurrent multi-container runs. The filesystem
backend keeps today's whole-file behavior unchanged (D7); the hazard above is
pre-existing and out of this contract's fix scope for the filesystem path
(flagged, not silently left ambiguous).

### D3 — Schema

```sql
CREATE TABLE IF NOT EXISTS config_snapshot (
    snapshot_id           TEXT PRIMARY KEY,       -- today's directory name, unchanged format
    source                TEXT NOT NULL,
    entity_id             TEXT NOT NULL,
    artifact_type         TEXT NOT NULL,
    device                TEXT,
    management_ip         TEXT,
    collected_at          TIMESTAMPTZ NOT NULL,
    method                TEXT,
    status                TEXT NOT NULL,
    sha256                TEXT,
    size_bytes            BIGINT,
    collector_version     TEXT,
    change_state          TEXT,
    previous_sha256       TEXT,
    previous_snapshot     TEXT,
    object_path           TEXT,                   -- points at the unchanged filesystem blob
    metadata_json         JSONB NOT NULL           -- full document, byte-identical to today's metadata.json
);
CREATE INDEX IF NOT EXISTS config_snapshot_latest_idx
    ON config_snapshot (source, entity_id, artifact_type, snapshot_id DESC);
    -- snapshot_id, NOT collected_at — see amendment A3.
CREATE INDEX IF NOT EXISTS config_snapshot_sha256_idx ON config_snapshot (sha256);

CREATE TABLE IF NOT EXISTS run_manifest (
    run_id      TEXT PRIMARY KEY,
    status      TEXT NOT NULL,
    job_id      TEXT,                              -- cross-references DEV.3.2's collection_job.job_id
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    manifest_json JSONB NOT NULL                    -- full document, byte-identical to today's manifest.json
);
CREATE INDEX IF NOT EXISTS run_manifest_status_idx ON run_manifest (status, updated_at DESC);

CREATE TABLE IF NOT EXISTS last_known_good_entity (
    source                     TEXT NOT NULL,
    entity_key                 TEXT NOT NULL,       -- _cp_key / _vsx_key / _pan_key, unchanged
    item_json                  JSONB NOT NULL,
    last_successful_collection TIMESTAMPTZ,
    updated_at                 TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source, entity_key)
);

CREATE TABLE IF NOT EXISTS scheduler_state (
    workflow          TEXT PRIMARY KEY,
    last_completed_at TIMESTAMPTZ NOT NULL
);
```

`metadata_json`/`manifest_json` keep the **full** existing dict as JSONB
alongside promoted columns used for indexed queries — this is what makes
every existing reader (`config_history.py`, `config_storage.py`,
`RunContext`) get back an identical dict regardless of backend, per D4,
without hand-mapping every field the promoted columns don't cover
(`validation`, `extra_metadata`, `storage.*` sub-object, etc.).

### D4 — Byte-compatible reader contract across backends

**Decision:** every existing function signature and every dict/JSON shape
returned to a caller stays identical regardless of `SECURITYEXPERT_EVIDENCE_BACKEND`.
`ConfigEvidenceStore.write_xml_snapshot(...)` still returns a `SnapshotResult`;
`ConfigHistoryService.get_device_history(...)` still returns a `DeviceHistory`;
`RunContext.write_manifest(...)` still writes the same manifest shape (just to
a row); `load_scheduler_state`/`write_scheduler_state` still trade in
`dict[str, datetime]`. No caller outside these five modules needs to know
which backend is active. This mirrors `DEV.3.2`'s "public API byte-compatible"
rule exactly and is what makes the existing test suites for these modules
double as backend-parity tests (D7).

### D5 — Scheduler-state concurrency rides on the existing DEV.3.2 lock; no new locking here

`load_scheduler_state`/`write_scheduler_state` are always called from inside
`main._run_scheduler_once`'s read-evaluate-write cycle, which `DEV.3.2`
already gates behind a non-blocking scheduler-wide advisory lock when the
*coordinator* backend is Postgres. This contract does not add a second lock:
the Postgres `scheduler_state` table is written with simple per-workflow
`INSERT ... ON CONFLICT (workflow) DO UPDATE` statements, safe under
concurrent access because the caller-side serialization already exists.
**Note:** this only removes the race if a deployment also runs
`SECURITYEXPERT_COORDINATOR_BACKEND=postgres`. Running
`SECURITYEXPERT_EVIDENCE_BACKEND=postgres` alone (coordinator still
`memory`) removes the *file-divergence* hazard (every container now reads/
writes the same table) but does not add cross-process mutual exclusion for
the scheduler's due-workflow decision — that guarantee is `DEV.3.2`'s alone.
This is stated explicitly in the operator-facing `.env.example` comment (see
Implementation plan) so the two knobs are never assumed to imply each other.

### D6 — No session-held locks, so no pooler hazard like DEV.3.2's D6

Every write in this contract is a single independent SQL statement inside
its own short transaction (row insert, upsert, or update) — nothing holds a
lock across a connection's idle time the way `DEV.3.2`'s per-endpoint
advisory lock does for a job's entire duration. This means, unlike the
coordinator backend, **this backend works correctly behind a
transaction-pooling proxy** (pgbouncer `pool_mode=transaction` or similar) —
there is no equivalent of `DEV.3.2`'s D6 hazard to guard against. The startup
preflight here only needs to prove connectivity and successfully run the
`CREATE TABLE IF NOT EXISTS` migration, not the advisory-lock survival check.

### D7 — Filesystem backend behavior is bit-for-bit unchanged; existing suites double as parity proof

`FilesystemConfigSnapshotBackend` etc. are today's exact file operations,
moved behind the new interface with no logic change — the same "provably
inert refactor" step `DEV.3.2` used. Every existing test for
`config_evidence.py`, `config_history.py`, `snapshot.py`, `run_context.py`,
`collection_executor.py`'s scheduler-state functions must pass unmodified
against the filesystem backend (default), proving the extraction is inert
before any Postgres-specific test is added.

## Open decision — RESOLVED 2026-08-31 (Option 1, full fidelity)

### E1 — Does CAS metadata belong in Postgres with full identity fidelity, or does it need tokenization first?

This is the one decision in this contract that is a genuine security/privacy
boundary call, not an engineering detail — flagging it explicitly per
`AGENTS.md`'s routing rule rather than deciding it unilaterally.

Today's `metadata.json` already contains **raw** `device` and
`management_ip` fields (`utils/config_evidence.py:300-301`), and
`utils/config_ui.py:412,739` reads exactly those two fields out of stored
metadata to render the Configuration module. `entity_id` itself (the
`config_snapshot` table's own index key) is also an identity, not an opaque
token. This is a different trust class from `DEV.3.2`'s coordinator rows,
which were deliberately designed to hold **zero** device identity (HMAC
lock keys only) because that data plane genuinely never needs to display a
device name — this one does.

Moving this table to a shared Postgres instance changes *where* that
identity-bearing data can be read from and backed up from — today it is
"whatever container's local disk holds this snapshot"; on Postgres it
becomes "whatever can reach the database." Three options:

1. **Full fidelity (recommended).** Store `device`/`management_ip`/
   `entity_id` in Postgres exactly as they exist in `metadata.json` today.
   No functional change, no UI regression, no new lookup/join layer. New
   requirement: the Postgres instance becomes an identity-bearing asset and
   needs the same operational protections the product already assumes for
   "not shared, not in git" data — a dedicated (not multi-tenant-shared)
   instance, encryption at rest for its volume, TLS on the DSN in
   production, and a restricted DB role — and `PRIVACY_AND_DATA_HANDLING.md`
   gets a short new section naming Postgres as a second identity-bearing
   store alongside local disk. This treats "this server's Postgres" as
   architecturally equivalent to "this server's other local disk," which
   matches how `DEV.3.1`/`DEV.3.2` already assume a dedicated instance for
   this product, not a shared cluster.
2. **Tokenized identity, resolved locally.** Store only an HMAC token for
   `entity_id`/`device`/`management_ip` (reusing `data/.support_hmac.key`,
   the same material `DEV.3.2` and the support bundle already use), plus
   every non-identity field, in Postgres. A local (per-node, filesystem,
   never-shared) token→raw-identity map, populated by the same collector run
   that knows the raw values, resolves tokens back for `config_ui.py`. Keeps
   Postgres identity-free like `DEV.3.2`'s tables, at the cost of a new
   join layer and a new local-consistency failure mode (a missing map entry
   degrades the UI to an unresolved token, not a security failure, but a new
   failure class this contract would be introducing).
3. **Split writes.** Keep `device`/`management_ip` in a small local
   filesystem sidecar per snapshot; put only the non-identity index fields in
   Postgres. Not recommended — this reintroduces the "many small per-snapshot
   files" problem the backlog item exists to remove, just smaller ones, and
   the "index" is no longer self-sufficient without a matching local file.

Recommendation: **Option 1**, on the stated assumption (already true for
`DEV.3.2`) that the Postgres instance this product uses is dedicated to it,
not a shared multi-tenant database. If that assumption does not hold for the
intended deployment, Option 2 is the fallback, at the cost noted above.

## Contract amendments — implementation-time findings (2026-08-31)

Recorded explicitly rather than silently absorbed into the D-sections above,
per `AGENTS.md` ("Do not silently rewrite historical outcomes"). Each of
these was found while building `utils/evidence_backend.py` against the frozen
contract; A1 is the one that would have made the build *not do what it exists
to do* had it gone unnoticed.

### A1 — D2's fix is only real if the caller's access pattern changes too

The frozen D2 says last-known-good moves to per-entity rows. That is
necessary but **not sufficient**: if `build_failure_aware_snapshot` keeps its
current shape (load the whole entity map → mutate some keys in memory → write
the whole map back), then moving that map into Postgres reproduces the exact
same lost-update race one layer down — container A's write landing between
container B's read and B's whole-map write is still clobbered by B's stale
copy of A's entities. Moving the storage does nothing on its own.

The fix requires the **call pattern** itself to become per-entity on the
Postgres path: `get_entity(source, key)` at each lookup site, and a
`put_entity(...)` issued and committed at each mutation site, so a container
only ever writes rows for entities it actually observed and never rewrites
rows it merely read.

To keep the filesystem path bit-for-bit unchanged under that same call
pattern (D7 — it must keep doing exactly one whole-file write per run, not
one per device), `LastKnownGoodBackend` carries an explicit `commit()`:
the filesystem backend buffers every `put_entity` in memory and performs the
single atomic whole-file write on `commit()`; the Postgres backend writes
each entity immediately and independently and `commit()` is a no-op. This is
what makes one call pattern serve both semantics honestly.

### A2 — Backends are dumb storage primitives; all business logic stays with the callers

The frozen D1/D4 implied backend methods carrying domain semantics (e.g. a
`latest_success(...)` that knows about `status == "success"`, `sha256`
presence and artifact-type filtering). Implementation showed that is the
wrong seam: re-expressing those rules in SQL is exactly how the two backends
would drift out of D4's byte-compatibility over time.

Final shape: `ConfigSnapshotBackend` exposes only `write()`,
`list_snapshots()` (all snapshot ids for an entity + their raw metadata dict,
or `None` where the stored record was unreadable) and `get_snapshot()`.
Artifact-type filtering, the `status == "success"`/`sha256` "latest" rule,
required-field validation, malformed counting, `collected_at` parsing,
timeline sorting and truncation **all stay in `utils/config_evidence.py` and
`utils/config_history.py`**, so the identical business logic runs regardless
of backend. Same principle for scheduler state — see A5.

### A3 — "Latest" ordering is by `snapshot_id`, not `collected_at`

Today's filesystem "latest" is `sorted(..., key=lambda p: p.parent.name,
reverse=True)` — the snapshot **directory name**, i.e. the `snapshot_id`.
`snapshot_id` (`<utc_stamp>_<uuid8>`) and the `collected_at` field inside the
metadata are produced by two separate clock reads microseconds apart, so
ordering by `collected_at` would agree almost always and disagree
occasionally — precisely the kind of rare, invisible divergence D4 exists to
prevent. Both backends therefore order by `snapshot_id DESC`, and the D3
index above is corrected to match.

### A4 — `metadata_json` is the only read path; promoted columns are for ops only

The Postgres backend stores the **complete** metadata dict verbatim as JSONB
and every reader reconstructs from that column alone. The promoted columns
(`device`, `sha256`, `change_state`, …) exist purely for indexing and
operator SQL, and **no application read path may ever be built on them** —
doing so would reintroduce per-field mapping drift between backends. Freezing
this as an invariant because the code cannot express it.

### A5 — Scheduler-state validation stays with the caller (also a circular-import constraint)

`load_scheduler_state`'s allowlist validation depends on
`ALLOWLISTED_WORKFLOWS` and `SchedulerPolicyError`, both defined in
`utils/collection_executor.py`, which itself imports the backend — so a
backend that validated would close an import cycle. `SchedulerStateBackend`
is therefore raw-document-only (`load_raw`/`save_raw`), with all validation
unchanged in `collection_executor.py`. This lands in the same place A2 does
for its own reasons, which is a good sign about the seam.

The same constraint applies module-wide: `utils/evidence_backend.py` must not
import from `utils/config_evidence.py` (which imports the backend). The two
small pure helpers it needs (`_safe_component`, atomic write/replace-retry)
are therefore duplicated locally rather than shared — deliberate, and not to
be "cleaned up" into a shared import later without breaking the cycle
differently (e.g. by extracting them into a third, dependency-free module).

### A6 — `SnapshotResult.directory` has no Postgres equivalent

Not anticipated by the contract: `SnapshotResult` exposes filesystem paths,
and `configuration/panorama_config_collector.py:1096-1099` consumes
`snap.directory` to build a display string. On the Postgres backend there is
no snapshot directory. Resolution: return a synthetic, non-existent
`Path("postgres") / "config_snapshot" / <snapshot_id>`, which that caller
renders as `postgres/config_snapshot/<id>` — its `relative_to(BASE_DIR)` call
is already wrapped in `try/except ValueError`, so this is safe today, and the
value reads as an honest "this lives in Postgres" pointer rather than a path
that looks real but is not. `artifact_path` stays a genuine filesystem path
on both backends, because blobs never move.

### A9 — `CREATE TABLE IF NOT EXISTS` is not concurrency-safe; schema creation is locked

Found by the AC-3 two-process test, which is the reason that criterion
demanded real subprocesses rather than threads. PostgreSQL's `CREATE TABLE IF
NOT EXISTS` does **not** serialize against a concurrent identical `CREATE`:
the racing session fails with `duplicate key value violates unique constraint
"pg_type_typname_nsp_index"`. Two worker containers starting together against
a fresh database — the exact deployment shape this build exists for — would
have had one crash at startup.

All schema creation is therefore serialized behind a single
**transaction-level** advisory lock (`pg_advisory_xact_lock`), released at
commit. Being transaction-scoped, it does not reintroduce the coordinator's
D6 pooling hazard: it never outlives its transaction, so it remains safe
behind a transaction-pooling proxy.

### A8 — `utils/compliance_trend_reconstruction.py` is a sixth affected module

The contract's module list (`config_evidence`, `config_storage`,
`config_history`, `snapshot`, `run_context`) missed it. It mines the on-disk
CAS tree directly and consumed `config_history._read_metadata`. On a
non-filesystem backend it would have silently reconstructed **zero** buckets
and reported "no history to reconstruct" rather than "wrong backend" — the
same misleading-zero failure the contract already identified for
`config_storage.py`, which is why it gets the same treatment: it now refuses
to run on a non-filesystem backend instead of returning an empty result.

### A7 — Snapshot writes are idempotent-safe

`config_snapshot` inserts use `ON CONFLICT (snapshot_id) DO NOTHING`.
`snapshot_id` is already globally unique by construction (timestamp + uuid4
fragment), so this never merges two distinct snapshots; it only makes a
retried write after a partial failure a no-op instead of an error.

## Correctness contract

1. With `SECURITYEXPERT_EVIDENCE_BACKEND` unset/`filesystem` (default), every
   existing test in `tests/` for `config_evidence.py`, `config_history.py`,
   `config_storage.py`, `snapshot.py`, `run_context.py`, and the
   scheduler-state functions in `collection_executor.py` passes unmodified —
   proving the backend extraction is behaviorally inert (D7).
2. With the Postgres backend, every reader listed in D4 returns dict/JSON
   shapes identical to the filesystem backend for equivalent input, verified
   by running the *same* test bodies against both backends where practical
   (parametrized fixture, mirroring how `DEV.3.2` ran identical assertions
   against both `InMemoryCoordinatorBackend` and `PostgresCoordinatorBackend`).
3. `build_failure_aware_snapshot` on the Postgres backend never loses one
   container's just-written entity state to another container's concurrent
   run (D2) — verified with two real concurrent processes, not threads.
4. Backend failure is fail-closed: an unreachable/misconfigured Postgres
   instance raises rather than silently falling back to (or silently mixing
   with) filesystem state.
5. No content-addressed blob is duplicated, moved, or deleted by this
   contract; `_ensure_blob`'s behavior and the artifact volume layout are
   untouched.

## Privacy and safety invariants

1. Whichever option E1 resolves to is implemented consistently across all
   four stores that carry any identity-adjacent field (only
   `config_snapshot` does; `run_manifest`, `last_known_good_entity`'s
   `item_json`, and `scheduler_state` carry no raw device identity today —
   `RunContext.to_manifest_dict()`-equivalent fields and LKG `item_json` are
   already the same secrets-free shapes these modules produce for the
   filesystem today, unchanged by this contract).
2. No credential, transport transcript, or raw configuration payload is ever
   written to Postgres by this contract — only the same metadata fields
   already written to `metadata.json`/`manifest.json`/`last_known_good.json`/
   `scheduler_state.json` today.
3. The repository privacy gate stays PASS / 0 — connection strings and any
   new identity-handling policy live in env/deployment config and
   `PRIVACY_AND_DATA_HANDLING.md`, never in repository text with real values.
4. No new device command, no command-gate work, no write capability.

## Implementation plan

1. `utils/evidence_backend.py`: four backend protocols + `Filesystem*`
   implementations that are today's exact logic moved verbatim (no behavior
   change). Full suite green here proves the refactor is inert (mirrors
   `DEV.3.2` step 1).
2. Wire each of the five in-scope modules to call through its backend
   instead of direct file I/O, still defaulting to the filesystem backend.
3. Add the four `Postgres*` implementations + schema migration + connectivity
   preflight (D6 — no pooling check needed).
4. `select_evidence_backend()` factory reading
   `SECURITYEXPERT_EVIDENCE_BACKEND` / `SECURITYEXPERT_EVIDENCE_POSTGRES_DSN`,
   wired at the same startup points `select_coordinator_backend()` already is
   in `main.py`, mapped to a clean `parser.error` on failure.
5. `config_storage.py`'s backend-gated `"not_applicable_on_postgres_backend"`
   result for `analyze_configuration_storage`/`deduplicate_legacy_storage`.
6. Resolve E1 in code (whichever option is approved) before any Postgres
   test writes identity fields.
7. Tests: filesystem-backend regression (existing suites, unmodified) +
   new Postgres-backend suite (`tests/test_dev3_3_evidence_store_migration.py`),
   `skipif` when `psycopg`/a reachable instance is absent, mirroring the
   `DEV.3.2` precedent; a real-subprocess concurrent test for D2/D3's
   correctness contract item 3.
8. Documentation: `docs/ARCHITECTURE.md` §2, `CURRENT_STATE.md`,
   `backlog.json`, `feature_registry.json`, `build_history.json`,
   `.env.example` (new vars, plus the D5 cross-reference note), and
   `PRIVACY_AND_DATA_HANDLING.md` if E1 resolves to Option 1 or 2.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | Filesystem backend (default): full existing suite passes unmodified; zero behavior change. |
| AC-2 | Postgres backend: `config_snapshot`/`config_history` reads return byte-identical dict shapes to the filesystem backend for equivalent stored data. |
| AC-3 | Postgres backend: two real concurrent processes each writing last-known-good state for disjoint entity sets never lose either process's entities (D2). |
| AC-4 | Postgres backend: `run_manifest` round-trips the exact manifest dict `RunContext.write_manifest` builds today, including `job_id`/`provenance`/`coordinator_decision` fields when set. |
| AC-5 | Postgres backend: `scheduler_state` round-trips `dict[str, datetime]` through the same `is_workflow_due` logic with no behavior change. |
| AC-6 | Unreachable/misconfigured Postgres → evidence writes fail closed, never silently degrade to filesystem or partial state. |
| AC-7 | No content-addressed blob touched; `_ensure_blob`/blob volume layout unchanged; `config_storage.py`'s dedup/analyze tools correctly report not-applicable on the Postgres backend rather than a misleading zero-work result. |
| AC-8 | Privacy gate PASS / 0; E1's resolved option implemented consistently; no credential/transcript/raw-config content in any new table. |

## Validation and merge gate

**Automated** — Postgres integration tests via a real local instance,
`skipif` when absent (same precedent as `DEV.3.2`'s render-harness/Postgres
`skipif` pattern). The concurrent-write test for AC-3 must use real OS
subprocesses, not threads, for the same reason `DEV.3.2`'s AC-1 did.

**Real-environment (before `DONE`, not necessarily before
`AUTOMATED_VALIDATED`)** — a multi-container run against the Postgres
backend, evidence required: last-known-good state for a fleet split across
two containers matches what a single-container run over the same fleet
would have produced (proves D2 in the real deployment shape, not just the
subprocess test). This is evidence-integrity risk, not the device-safety
risk `DEV.3.2`'s real-environment gate exists for, so — unlike `DEV.3.2` —
`AGENTS.md`'s mandatory real-environment rule for *network-facing/safety*
behavior does not strictly apply here; this contract still requires it before
`DONE` because silent evidence corruption is exactly the failure class this
build exists to close, and an automated test alone cannot fully rule out a
production-shaped surprise (real connection pooler in front of Postgres,
real container scheduler timing).

## Risks

- **E1 (resolved: Option 1)** — full-fidelity identity in Postgres means a
  compromised or over-broadly-shared Postgres instance now carries the same
  identity exposure as a compromised container's local disk today, across
  the whole fleet's metadata index at once instead of one container's slice
  of it. Mitigated by the `PRIVACY_AND_DATA_HANDLING.md` requirements this
  decision now carries (dedicated instance, TLS DSN, restricted role,
  encryption at rest) rather than by the schema.
- **Silent backend mismatch** — a deployment accidentally running some
  containers with `SECURITYEXPERT_EVIDENCE_BACKEND=filesystem` and others
  with `postgres` would silently fragment evidence across two stores with no
  error. Mitigated by documenting this loudly in `.env.example` and treating
  it as an operator-configuration invariant, the same way `DEV.3.2` treats
  consistent coordinator-backend configuration across a fleet.
- **D5's scope note being missed** — an operator enabling
  `SECURITYEXPERT_EVIDENCE_BACKEND=postgres` without also enabling
  `SECURITYEXPERT_COORDINATOR_BACKEND=postgres` gets shared scheduler state
  but not scheduler mutual exclusion. Mitigated by the explicit `.env.example`
  cross-reference in the implementation plan.
- **No backfill** — switching an existing fleet's evidence backend from
  filesystem to Postgres mid-life "loses" old history from the new reader's
  point of view (it is not deleted, just not visible through the Postgres
  path). Same accepted trade-off `DEV.3.2` made for coordinator state.

## Rollback

`SECURITYEXPERT_EVIDENCE_BACKEND=filesystem` (the default) restores today's
validated per-container file behavior with no schema or code revert needed.
A full revert is a single-commit revert of an additive change, identical in
shape to `DEV.3.2`'s rollback story.

## Definition of done

`DONE` when AC-1..AC-8 pass, E1 (Option 1, full fidelity) is implemented
consistently, the filesystem default is unchanged, the multi-container
real-environment evidence for AC-3 is recorded, and
`backlog.json`/`CURRENT_STATE.md` reflect the new status.

## Next movement / model

- **Contract frozen, E1 resolved (Option 1).** Implementation proceeds.
- All implementation steps (1–8): **Sonnet 5, normal** — E1 resolved to the
  mechanical option (column mapping against the already-frozen D3 schema,
  no new token/lookup layer), so every remaining step is deterministic
  implementation against a frozen contract, same as `DEV.3.2`'s equivalent
  steps.
- Opus/extended thinking is not needed for implementation. This contract
  itself used extended reasoning because the store-shape analysis (D1) and
  the identity-boundary call (E1) were the expensive parts; both are now
  settled.
