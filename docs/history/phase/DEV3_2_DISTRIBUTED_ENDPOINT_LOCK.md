# DEV.3.2 — Distributed per-endpoint lock and job store

## Status

**CONTRACT — PROPOSED (not implemented).** Awaiting user review before any
code is written.

Product baseline: `0.7.6a AUTOMATED_VALIDATED`. Backlog id:
`distributed_endpoint_lock_and_job_store` (P0, `planned`).

Prerequisite state: `cp_device_interaction_safety` (P0) closed 2026-08-25;
`collection_execution_coordinator` REAL_ENV_VALIDATED 2026-08-27. This build
extends that validated single-process safety mechanism into the multi-process
world — it does not reopen either.

## Objective

`CollectionCoordinator` is the single admission boundary that keeps the
product from opening two concurrent sessions to the same physical network
device. Today it is `threading.Lock` + in-memory dicts
(`utils/collection_executor.py:151-369`), explicitly scoped to one OS process
by its own docstring. `DEV.3.1` shipped a container image; the moment a second
worker container or process runs a collector, that guarantee is silently gone
— nothing errors, the two workers simply do not see each other's locks, and a
Check Point MDS receives concurrent CPRID/SSH sessions.

Make the endpoint-exclusion and concurrency-budget guarantees hold **across
processes**, backed by PostgreSQL, without changing any collector, transport,
timeout, retry, or the budget *value*.

This contract deliberately covers the **safety plane only**. The evidence-plane
migration (CAS metadata index, run manifests, last-known-good) that the backlog
item currently bundles is split out — see "Scope split" below.

## Scope split (proposed change to the backlog item)

`distributed_endpoint_lock_and_job_store` currently names two targets
(`DEV.3.2 / DEV.3.3`) and bundles two different risk classes. Proposed split:

| Build | Content | Risk class |
| --- | --- | --- |
| **DEV.3.2** (this contract) | Distributed endpoint lock, cross-process concurrency budget, job store, scheduler-state locking | **Device safety.** Gates `per_vendor_worker_split`. |
| **DEV.3.3** (separate contract) | CAS metadata index, run manifests, last-known-good → Postgres; blobs stay on the volume | Evidence integrity. Does not gate worker split. |

Rationale: `AGENTS.md` build-size rule (one coherent objective; avoid bundling
unrelated architecture and storage work). DEV.3.2 is buildable, testable and
independently valuable without touching the evidence store — and it is the
half that is P0 for device safety.

## Scope

### In scope

- **`utils/collection_executor.py`** — extract the coordinator's admission
  decisions behind a backend interface; add a PostgreSQL backend beside the
  existing in-memory one. The public API (`admit`, `admit_request`, `release`,
  `fail`, `cancel`, `get_job`, `wait_for_terminal`, `budget_snapshot`,
  `execute_admitted_collection`) and every existing return contract stay
  byte-compatible.
- **Backend selection** — `SECURITYEXPERT_COORDINATOR_BACKEND` =
  `memory` (default) | `postgres`. A laptop/single-container run keeps today's
  validated behavior with no Postgres dependency. Postgres is opt-in.
- **Schema** — one new table (`collection_job`) plus the advisory-lock protocol
  below. Migration lives with the DEV.3.x deployment work.
- **Cross-process coalesce wait** — replace the `threading.Event` in
  `wait_for_terminal` with a backend-provided bounded wait (polling on the
  Postgres backend).
- **Scheduler state** (`data/state/scheduler_state.json`, written by
  `write_scheduler_state`) — currently a single-writer atomic
  `tmp.replace(path)`. Under two workers, two schedulers can both find a
  workflow due and both dispatch. Gate the read-evaluate-write cycle with a
  scheduler advisory lock on the Postgres backend.
- **Startup preflight** — verify the deployment actually supports session-level
  advisory locks (see "Pooling" below) and fail closed if it does not.
- Two incidental fixes found while auditing:
  `CollectionCoordinator._endpoint_locks` (line 161) is initialized and never
  read — dead code; and `budget_snapshot()` hardcodes `available = 1`
  (line 366), which silently misreports for any capacity above 1.

### Explicitly out of scope

- **Any change to the concurrency budget value.** It stays **1** per vendor
  key. Raising it remains a separate build needing its own real-environment
  evidence.
- Any collector, transport, command, timeout, retry, cooldown, or parallelism
  change. No device-facing behavior moves.
- The network-device command gate — no new or changed device command here.
- CAS metadata index / run manifests / last-known-good migration (**DEV.3.3**).
- `per_vendor_worker_split` (**DEV.3.4**) — this build unblocks it, it does not
  perform it.
- LISTEN/NOTIFY, a durable work queue, multi-node HA scheduler election,
  priority/fairness. Polling is sufficient at fleet scale and adds no new
  failure mode.
- Any write/config-changing capability. Read-only maturity is unchanged.

## Design decisions

### D1 — Mutual exclusion is a session-level advisory lock, not a TTL lease

**Decision:** `pg_try_advisory_lock(bigint)` held on a dedicated session for
the life of the job. **Rejected:** a `lease(expires_at)` table with a TTL and
heartbeat.

A TTL forces us to guess a maximum collection duration. CP VSX collection is
minutes long with an unbounded tail (nested SSH, per-VS `vsenv` iteration). If
the TTL expires while the holder is still inside an SSH session, a second
worker is admitted and opens a concurrent session to the same device — the
exact P0 hazard this build exists to prevent. An advisory lock is released by
the *server* when the holding connection dies, so there is no duration to
guess, and the residual failure mode is "lock held a little too long"
(degrades to slower collection) rather than "lock released too early"
(degrades to unsafe device interaction). It also removes clock skew between
workers from the safety argument entirely.

### D2 — The job row is observability and coalescing; the lock is the truth

Ordering is always: acquire lock → insert job row; release row → release lock.
A divergence (lock acquirable but a row still says `running`) can only mean the
holder process died, and is reconciled to `orphaned` by the next admitter. The
job row is never consulted to decide whether a device session may open.

### D3 — Lock keys are keyed hashes, never canonical ids

`Job.to_manifest_dict()` deliberately omits `canonical_ids` because they are
device names (line 114). A shared lock table must not reintroduce them.

`lock_key = int64(HMAC-SHA256(deployment_key, canonical_id)[:8])`, reusing the
existing `data/.support_hmac.key` tokenization material (see
`deploy_persistent_secret_material` — the key must be on a persistent volume,
or lock keys drift across restarts and exclusion silently breaks; that item
becomes a hard dependency of this one).

Collisions are **fail-safe**: two distinct endpoints mapping to one key
serialize unnecessarily (slower), never share a lock (unsafe). At 64 bits and
fleet scale the probability is negligible, and the direction of failure is the
safe one.

### D4 — All acquisition is non-blocking, sorted, and all-or-nothing

`pg_try_advisory_lock` mirrors today's `sem.acquire(blocking=False)` and
`REJECTED_LOCKED` semantics exactly. A job spanning several `canonical_ids`
acquires keys in sorted order and releases any partial set on the first
failure. Non-blocking acquisition makes cross-worker deadlock structurally
impossible rather than merely unlikely.

### D5 — Budget is a counted check under a short-lived per-vendor gate lock

Under a per-vendor **gate** advisory lock (held for milliseconds, not for the
job): reconcile orphans → `count(*) where budget_key = ? and status='running'`
→ admit if `count < capacity` → insert row → release the gate. Endpoint locks
stay held for the job's duration; the gate does not.

Reconciliation needs no TTL: a dead worker's connection drop released **all**
its advisory locks together, so a row whose endpoint keys are all acquirable
has a dead holder. Test, mark `orphaned`, release, continue.

This generalizes past capacity 1 so a future budget increase is a config
change plus real-environment evidence, not a re-architecture. The value
shipped stays 1.

### D6 — Pooling is a hard requirement, verified at startup

Session-level advisory locks are **silently broken** by a transaction-pooling
connection pooler (pgbouncer `pool_mode = transaction`): the lock outlives the
transaction, the connection returns to the pool, and the lock is then held by
whatever client next borrows that connection. This is a correctness failure
that no test against a direct connection will catch.

Requirements: a **dedicated, non-pooled connection per in-flight job**; session
pooling or direct connection only; `idle_session_timeout = 0` for the worker
role (note: `idle_in_transaction_session_timeout` is irrelevant — the session
is idle *between* transactions, not inside one); TCP keepalives /
`tcp_user_timeout` configured so a killed container's locks are reclaimed
within a **declared, bounded** window. The startup preflight asserts advisory
locks survive across two transactions on the same connection and fails closed
otherwise.

With capacity 1 across three vendor keys, the steady-state cost is ≤ ~4 held
connections.

## Correctness contract

1. At most one active collection job exists per physical endpoint **across all
   processes** — the invariant `utils/collection_executor.py:10-12` states for
   one process.
2. Every existing decision value (`ADMITTED`, `COALESCED`, `REJECTED_BUDGET`,
   `REJECTED_LOCKED`) is produced under the same conditions as today, and
   `execute_admitted_collection` still raises `CollectionAdmissionError`
   **before** `operation()` for any non-admitted decision.
3. Backend failure is fail-closed: an unreachable or misconfigured Postgres
   rejects admission; it never degrades to unsynchronized local locking.
4. A crashed worker's endpoint locks are reclaimed within the declared bounded
   window, with no operator action and no stuck endpoint.
5. `memory` backend behavior is bit-for-bit today's behavior; the existing
   `test_phase0_6_1c_collection_executor.py` and
   `test_phase0_6_1c_r06_coalesce_probe.py` pass unmodified against it.
6. The scheduler cannot dispatch the same due workflow from two workers in one
   interval.

## Privacy and safety invariants

1. No `canonical_id`, device name, address, credential or transport transcript
   is written to Postgres. Only keyed hashes, job ids, vendor/scope labels,
   status and timestamps — the same field set `to_manifest_dict()` already
   permits.
2. No new device command, no command-gate work, no write capability.
3. The concurrency budget value does not change.
4. The repository privacy gate must stay PASS / 0 — connection strings and
   schema live in env/deployment config, never in repository text.

## Implementation plan

1. Extract a `CoordinatorBackend` protocol from `CollectionCoordinator`;
   move today's dicts/semaphores into `InMemoryBackend` with no behavior
   change. Full suite green here proves the refactor is inert.
2. Add `PostgresBackend`: schema + migration, key derivation, non-blocking
   sorted acquisition, budget gate with orphan reconciliation, polled
   `wait_for_terminal`.
3. Startup preflight (advisory-lock survival, `idle_session_timeout`) with
   fail-closed behavior and a safe, value-free diagnostic.
4. Gate the scheduler read-evaluate-write cycle behind a scheduler advisory
   lock on the Postgres backend.
5. Fold in the two incidental fixes (dead `_endpoint_locks`, `budget_snapshot`
   availability).
6. Compose: add the Postgres service and worker wiring, default still `memory`.
7. Tests (below), then documentation:
   `docs/ARCHITECTURE.md` §2, `CURRENT_STATE.md`, `backlog.json` (including the
   DEV.3.2/3.3 split), `feature_registry.json`, `build_history.json`.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | With the Postgres backend and **two OS processes**, two concurrent admissions for the same `canonical_id` yield exactly one `ADMITTED`; the second is `COALESCED` or `REJECTED_LOCKED`, and opens no session. |
| AC-2 | Per-vendor budget of 1 holds across processes; the second process gets `REJECTED_BUDGET`. |
| AC-3 | `SIGKILL` of a worker mid-job releases its endpoint locks within the declared window; the next admission for that endpoint succeeds and the stale row reconciles to `orphaned`. |
| AC-4 | Unreachable/misconfigured Postgres → admission rejected, never silent local-only locking (fail-closed). |
| AC-5 | The startup preflight detects a transaction-pooling pooler and refuses to start. |
| AC-6 | `memory` backend is behaviorally unchanged: existing coordinator/scheduler tests pass unmodified, full regression shows zero new failures. |
| AC-7 | No collector, command, timeout, retry, cooldown or budget-value change; no device identity in Postgres; privacy gate PASS / 0. |
| AC-8 | Cross-process coalescing: the waiter observes the active job reaching a terminal state and reports `coalesced_completed` exactly as the single-process path does today (`main.py:308-320`). |

## Validation and merge gate

**Automated** — Postgres integration tests via a compose/testcontainer service,
`skipif` when absent (the `bun` render-harness precedent). Multi-process tests
must use real subprocesses; threads would not prove the property under test.
Fault injection (`SIGKILL`, connection drop, Postgres restart mid-job) is
required, not optional.

**Real-environment (mandatory before `DONE`)** — this is safety-critical device
interaction and automated tests alone cannot close it, per `AGENTS.md`:

1. Two worker containers, Postgres backend, both dispatched at the same
   real Check Point MDS. Evidence required: exactly one CPRID/SSH session
   observed at the device, and the second worker's decision recorded.
2. A worker killed mid-collection; the endpoint recovers within the declared
   window on the next run.

Status may reach `AUTOMATED_VALIDATED` on the automated evidence, and
`REAL_ENV_VALIDATED` / `DONE` only on the two-worker device evidence. Server
availability is the gating dependency (DEPLOY.1, external).

## Risks

- **Silent pooler misconfiguration** — the highest-severity risk; mitigated by
  AC-5's preflight rather than documentation alone.
- **HMAC key drift** — a regenerated `data/.support_hmac.key` changes every
  lock key and silently voids exclusion. Hard dependency on
  `deploy_persistent_secret_material`; the preflight should also assert key
  stability.
- **Long-held connections** — bounded and small at capacity 1; revisit if the
  budget is ever raised.
- **Refactor blast radius** — `execute_admitted_collection` is on every
  collection path. Mitigated by step 1 landing as a provably inert refactor.

## Rollback

`SECURITYEXPERT_COORDINATOR_BACKEND=memory` (the default) restores today's
validated single-process behavior with no schema or code revert. A full revert
is a single-commit revert of an additive change.

## Definition of done

`DONE` when the endpoint-exclusion and budget guarantees are demonstrably held
across processes on the Postgres backend, the `memory` default is unchanged,
AC-1..AC-8 pass, the two-worker real-environment evidence is recorded, and
`backlog.json` reflects both the new status and the DEV.3.2/3.3 split.

## Next movement / model

- **This contract → user review.** No implementation until accepted.
- Step 1 (backend extraction) and step 5: **Sonnet 5, normal** — deterministic
  refactor against a frozen contract.
- Steps 2–4 (Postgres backend, preflight, scheduler gate): **Sonnet 5,
  extended thinking** — concurrency and failure-mode reasoning, but the
  architecture is settled by this document.
- Opus is not needed for the implementation; it was used for this contract
  because the safety argument and the rejected-alternative analysis are the
  expensive part.
