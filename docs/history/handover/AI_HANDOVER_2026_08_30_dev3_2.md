# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Product baseline: `0.7.6a — Render harness + uitest topology matrix` —
  AUTOMATED_VALIDATED. Unchanged this session.
- Engineering baseline: `DEV.1` complete; `DEV.2.1` — AUTOMATED_VALIDATED;
  `DEV.3.1 — Linux worker image + Compose` — AUTOMATED_VALIDATED (landed via
  PRs #10–#14 between the last handover and this session — the previous
  `AI_HANDOVER.md` predated it and was not kept in sync; treat this file and
  `CURRENT_STATE.md` as caught up now, but double-check `project/backlog.json`
  directly if something here still looks stale).
- **This session's build: `DEV.3.2 — Distributed per-endpoint lock`
  (`distributed_endpoint_lock`) — AUTOMATED_VALIDATED 2026-08-30.** See §2.
- Date: 2026-08-30.
- **`origin/main` and local `main` are both at `eb6cd81`. Working tree clean,
  nothing pending, nothing unpushed.**
- Full suite this session (Linux cloud sandbox, Python 3.11.15 — **not** the
  Windows corporate laptop the prior handover's `py -V:3.12` guidance
  targets; that guidance is laptop-specific, not a repo convention change):
  `python3 -m pytest -q` → **654 passed, 3 skipped, 2 failed**. The 2 failures
  are pre-existing/unrelated (`test_run_html_export_embeds_discovery_payload_without_leftover_placeholder`,
  `test_checkpoint_render_appends_one_record`) — same two the codebase has
  documented as failing identically on the unmodified baseline across
  multiple prior sessions.
- Repository privacy gate: **FAIL/3 locally** on this dirty checkout only
  (gitignored `data/`, `data/.support_hmac.key`, `logs/` created by test runs
  and by `main.py --scheduler-once` smoke tests this session) — delete them
  before re-running the gate; PASS/0 is expected on a clean checkout.
- **New this session — DEV.3.2 optional dependency.** `requirements-postgres.txt`
  (`psycopg[binary]>=3.1`) is opt-in, not in the base `requirements.txt` or
  the DEV.3.1 Dockerfile. Only needed when
  `SECURITYEXPERT_COORDINATOR_BACKEND=postgres` is set; default (`memory`,
  unset) needs nothing new. See `.env.example` for the two new variables
  (`SECURITYEXPERT_COORDINATOR_BACKEND`, `SECURITYEXPERT_COORDINATOR_POSTGRES_DSN`).
- **Sandbox-only test infra, not part of repo state:** this session installed
  PostgreSQL 16 + pgbouncer locally in the cloud sandbox to get real (not
  mocked) evidence for DEV.3.2 — real subprocess `SIGKILL`, a real
  transaction-pooling pgbouncer to prove the startup preflight rejects it.
  None of that persists to a fresh container; `tests/test_dev3_2_distributed_endpoint_lock.py`'s
  Postgres-requiring tests `skipif` cleanly when no live database is
  reachable (`SECURITYEXPERT_TEST_POSTGRES_DSN`), same posture as the `bun`
  render-harness skipif.
- **HTML render harness (0.7.6):** unchanged this session — no
  `templates/` / `app.js` / `style.css` / payload-builder edits were made.
  Still mandatory for any future change to those.
- **Never label a version as four dot-separated numbers** — `_IPV4_RE` in the
  privacy gate flags an `A.B.C.D` label as `PRIVATE_ENDPOINT_LITERAL`. Use a
  letter suffix (`0.7.4a`).

## 2. Recent builds (this session) — both LANDED on `origin/main`

Detail is in `project/build_history.json`; phase docs under
`docs/history/phase/`. Newest first.

- **Stale-documentation correction** (commit `5bc5d8a` on the feature branch,
  merged in `eb6cd81`) — `project/backlog.json` had `cp_device_interaction_safety`
  (P0) as AUTOMATED_VALIDATED since 2026-08-25 and its
  `collection_execution_coordinator` follow-on as REAL_ENV_VALIDATED since
  2026-08-27, but `CURRENT_STATE.md`, the old `AI_HANDOVER.md`,
  `docs/ARCHITECTURE.md` and `docs/design/COMPLIANCE_CHECK_ENGINE.md` all kept
  citing the audit as an open P0 blocker (traced to a 2026-08-28 commit —
  three days *after* closure, so it was never accurate). All four corrected;
  the one genuinely-still-open sibling (`distributed_endpoint_lock_and_job_store`,
  the multi-process extension) was distinguished from the closed audit.
- **`distributed_endpoint_lock` (DEV.3.2)** (commit `5053ff0`, contract
  `87d8453`) — **AUTOMATED_VALIDATED**. `CollectionCoordinator`'s per-endpoint
  exclusion and per-vendor concurrency budget were single-process only
  (`threading.Lock` + in-memory dicts); DEV.3.1 shipped a container image, so
  a second worker would silently lose that guarantee. New
  `utils/coordinator_backend.py`: `CoordinatorBackend` protocol,
  `InMemoryCoordinatorBackend` (the prior logic moved verbatim, plus two
  incidental bug fixes — dead `_endpoint_locks` removed, `budget_snapshot()`
  undercounting above capacity 1 fixed), `PostgresCoordinatorBackend`
  (session-level `pg_advisory_lock` per endpoint on a dedicated per-job
  connection — released by the server itself if the connection dies, no TTL,
  no heartbeat — plus a counted per-vendor budget gate and HMAC-derived lock
  keys so no device identity reaches Postgres), `verify_postgres_backend_ready`
  (fail-closed startup preflight detecting a transaction-pooling proxy),
  `try_acquire_scheduler_lock` (gates the scheduler's read-evaluate-write
  cycle). `CollectionCoordinator` is now a thin backend-delegating shell;
  `select_coordinator_backend()` reads `SECURITYEXPERT_COORDINATOR_BACKEND`
  (`memory` default / `postgres`), wired into `main.py` with a clean
  `parser.error` on failure. **Evidence is real, not mocked:** a real child OS
  process admitted first, a second independent backend instance coalesced
  onto it (no second session opened); the child was `SIGKILL`ed and the
  endpoint was reclaimed with no TTL/heartbeat, the stale row marked
  `orphaned`; a real `pgbouncer 1.22` in `pool_mode=transaction` was correctly
  rejected by the preflight, a direct connection passed it. One real bug the
  design review missed but real-Postgres testing caught: an earlier draft
  checked budget before coalescing, wrongly rejecting a same-endpoint retry
  once the budget was full — fixed, regression-tested. Full suite: 654 passed
  / 3 skipped / 2 failed (pre-existing/unrelated) — net +20, zero
  regressions. `docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md`.
  Backlog split: `distributed_endpoint_lock_and_job_store` →
  `distributed_endpoint_lock` (this build, AUTOMATED_VALIDATED) +
  `distributed_evidence_store_migration` (DEV.3.3, still `planned` — CAS
  metadata index / run manifests / last-known-good, does not gate device
  safety).

## 3. Next work

**No active build contract is open.** A new build needs a fresh contract, put
to the user for review first.

- **DEV.3.2 real-environment validation** (owed before `DONE`/`REAL_ENV_VALIDATED`) —
  an actual multi-container deployment, both workers hitting a real Check
  Point MDS with `SECURITYEXPERT_COORDINATOR_BACKEND=postgres`, confirming
  exactly one CPRID/SSH session at the device. Server-blocked (DEPLOY.1,
  external) — same gap class as every other `on_hardware_real_env_validation`
  item.
- **`distributed_evidence_store_migration` (DEV.3.3)** — CAS metadata index /
  run manifests / last-known-good → Postgres. Its own contract; deliberately
  not bundled with DEV.3.2 (see the split above). Not urgent — doesn't gate
  device safety or `per_vendor_worker_split`.
- **CE.2** (`compliance_check_engine_primitives`) — curated read-only
  command-primitive registry, opt-in `--compliance-probe`. Unblocked on the
  audit front (closed 2026-08-25); still needs its own contract, each
  primitive through the 10-point network-device command gate, and a
  real-environment validation gate (`docs/design/COMPLIANCE_CHECK_ENGINE.md` §5).
- **CE.3** / **CE.4** — `DEPLOY.1A` / OP.2-gated, unchanged.
- **OP.x — Controlled Failover:** design done, approval pending
  (`docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`; `roadmap.json`
  `open_decisions`). Its OP.2 gate list includes the CP device-interaction
  audit (closed) and the distributed lock (now AUTOMATED_VALIDATED) among
  several still-open items (DEPLOY.1A OIDC/RBAC, mature TRACE/RECOVER,
  command gate for write primitives, signed change-management review) —
  don't read either closure as OP.2 being close to unblocked.
- **Other standing backlog items** (P1/P2 UI, performance, etc.) were not
  reviewed this session — read `project/backlog.json` directly rather than
  trusting a copied bullet list here; the previous handover's list was
  already stale by the time this session started.

## 3b. NOT YET DONE — real-environment / on-hardware validation

- **DEV.3.2** — see §3 above; the automated/local-Postgres evidence is real
  but not a substitute for an actual multi-container-against-a-real-MDS run.
- Everything already listed under `on_hardware_real_env_validation` in
  `project/backlog.json` remains owed and was not touched this session
  (needs an MDS/Panorama-reachable laptop or the DEPLOY.1 server).

## 4. Open risks / debt carried forward

- CP device-interaction-safety audit is closed (2026-08-25) — do not reopen
  it; the remaining CE.2 prerequisite is the per-primitive command gate + a
  real-environment validation gate.
- **DEV.3.2's Postgres backend is opt-in and off by default.** Nothing about
  normal single-process operation changed — `SECURITYEXPERT_COORDINATOR_BACKEND`
  unset still runs the exact prior `InMemoryCoordinatorBackend` path.
- **DEV.3.2 preflight is the load-bearing safety check for the Postgres
  backend.** If a future deployment puts a transaction-pooling proxy (e.g.
  pgbouncer `pool_mode=transaction`) in front of the database, session-level
  advisory locks silently stop working — `verify_postgres_backend_ready` is
  designed to catch this at startup and refuse to run, but if anyone ever
  bypasses or weakens that preflight, this is the failure mode to remember.
- **DEV.3.2 lock-key stability depends on `data/.support_hmac.key`.** A
  regenerated key silently changes every derived lock key and voids
  cross-process exclusion (not unsafe — collisions/mismatches only make
  things serialize or fail to coalesce, never share a lock across distinct
  endpoints — but confusing). This makes `deploy_persistent_secret_material`
  (P1, `planned`) a hard dependency once DEV.3.2 is used in production.
- `unified.interfaces` / `unified.routes` join is an exact normalised-identity
  match on the config-UI device name (carried forward, unrelated to this
  session — see prior handovers / `project/backlog.json` for detail).
- The CAS / support-key path writes `data/` + `logs/` into the repo dir during
  a test run (`DEV.0.3C` deferred). Gitignored; delete before the privacy
  gate.

## 5. Exact next action

**Fresh chat.** Cold-start via `AI_START_HERE.md` → this file →
`CURRENT_STATE.md` → `project/roadmap.json` + `project/backlog.json`. Pick one
§3 objective, write a contract for user review **before** implementing. Run
the render harness for any UI/payload change (unchanged this session).

## 6. main merge decision + Git dispatch

- **Nothing outstanding.** `origin/main` and local `main` both at `eb6cd81`.
  This session's work (stale-doc corrections + DEV.3.2) was developed on
  `claude/cp-device-interaction-markdown-ret13v`, merged with
  `git merge --no-ff`, and pushed directly to `main` **at the user's explicit
  request this session** ("pull and do merge yourself") — standing priority 4
  (human-initiated Git push/merge) was satisfied by that direct instruction,
  not bypassed. Future builds should still default to asking first unless a
  similar explicit go-ahead is given.
- Future builds: branch off `main`, commit, `git merge --no-ff` + `git push
  origin main`, or `gh pr create --fill --base main` → `gh pr merge --merge`.

## 7. Next movement / model

- `IMPLEMENTATION` (**Sonnet 5, normal**) for `distributed_evidence_store_migration`'s
  contract once picked up, and for CE.2's contract once picked up (both are
  deterministic-scope once the design questions are settled — closer to
  "normal" than "extended thinking", but see next line).
- A genuinely new architecture/security-boundary design (DEV.3.3's exact
  Postgres schema/migration approach, CE.2's primitive registry shape, OP.0)
  wants **Sonnet 5 or Opus, extended thinking** for the contract only, then
  normal reasoning for implementation — this session's DEV.3.2 contract was
  written at Opus and implemented at Sonnet 5 normal, which is the pattern to
  repeat.

## 8. Continue or fresh chat

**Start a fresh chat.** This session closed two independent pieces of work
(a documentation-staleness fix and a full DEV.3.2 implementation); the next
objective is distinct and needs its own contract.

## 9. main.py / UI effect

- **Stale-doc correction:** none — documentation and `project/*.json` only.
- **DEV.3.2:** none visible in the UI/report. `main.py` gains a coordinator
  backend selection step at startup (silent when
  `SECURITYEXPERT_COORDINATOR_BACKEND` is unset/`memory`) and the scheduler
  gains a cross-process gate that only activates on the Postgres backend.
  No collector, transport, command, timeout, retry, or concurrency-budget
  *value* changed. A normal single-process run behaves identically to
  before this session.
