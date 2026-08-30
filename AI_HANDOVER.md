# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Product baseline: `0.7.7 — Compliance trend retro-fill (PAN baseline
  reconstruction)` — AUTOMATED_VALIDATED (2026-08-30).
- Engineering baseline: `DEV.1` complete; `DEV.2.1` — AUTOMATED_VALIDATED;
  `DEV.3.1` (Linux worker image + Compose) — AUTOMATED_VALIDATED; `DEV.3.2`
  (distributed per-endpoint lock, Postgres-backed) — AUTOMATED_VALIDATED.
- Date: 2026-08-30.
- **This checkpoint is a merge of two independent sessions' work that landed
  in parallel:** this session's `0.7.7` (branch
  `claude/docs-setup-credits-1i5phx`) and a separate session's `DEV.3.2` +
  a documentation-staleness correction (branch
  `claude/cp-device-interaction-markdown-ret13v`, already merged to `main`
  in `eb6cd81` before this merge). Both sessions independently rewrote this
  file and `CURRENT_STATE.md` at their own close, which produced a real git
  conflict on both files — resolved by hand here, keeping both builds'
  content rather than picking one side. **If you are the next session,
  trust this reconciled version over either predecessor's; if something here
  still looks inconsistent with `project/build_history.json`'s newest
  entries, `build_history.json` is authoritative.**
- **Corrected here (inherited from the `DEV.3.2` session, do not re-break
  this):** the CP device-interaction-safety audit (P0) closed 2026-08-25
  (`backlog.json` `cp_device_interaction_safety`, AUTOMATED_VALIDATED) with
  its `collection_execution_coordinator` follow-on REAL_ENV_VALIDATED
  2026-08-27. `CURRENT_STATE.md`, the pre-merge `AI_HANDOVER.md`,
  `docs/ARCHITECTURE.md` and `docs/design/COMPLIANCE_CHECK_ENGINE.md` had
  all kept citing it as an open P0 blocker for three days after it actually
  closed — this session's own prior turn repeated that stale claim
  (recommending the audit as "highest-leverage next pick") before
  discovering the correction during this merge. **The audit is closed; do
  not reopen it or recommend it as next work.** Any future
  recurring-scheduling / concurrency-budget-increase build still needs its
  own real-environment evidence, which is a different, still-open item.
- Full suite after this merge (Linux cloud sandbox, Python 3.12):
  `python3 -m pytest -q` → **657 passed, 10 skipped, 2 failed** (645/2/2
  pre-merge from `0.7.7` alone + `DEV.3.2`'s +20 tests, most of which
  `skipif` cleanly without a live Postgres — same 2 pre-existing/unrelated
  failures both predecessor sessions already documented:
  `test_run_html_export_embeds_discovery_payload_without_leftover_placeholder`,
  `test_checkpoint_render_appends_one_record`). Zero regressions from the
  merge itself.
- Repository privacy gate: **PASS / 0 on a clean checkout** — verified after
  this merge. A test run writes gitignored `data/` + `logs/` +
  `data/.support_hmac.key` into the repo dir; delete them before the gate
  (an `rm -rf data logs` may be blocked by an auto-mode permission
  classifier in a sandboxed session on the first attempt — retry once before
  asking the user; it succeeded on retry this session).
- **New from `DEV.3.2` — optional dependency.** `requirements-postgres.txt`
  (`psycopg[binary]>=3.1`) is opt-in, not in base `requirements.txt`. Only
  needed when `SECURITYEXPERT_COORDINATOR_BACKEND=postgres` is set; default
  (`memory`, unset) needs nothing new. See `.env.example` for
  `SECURITYEXPERT_COORDINATOR_BACKEND` / `SECURITYEXPERT_COORDINATOR_POSTGRES_DSN`.
- **HTML render harness — two implementations, use whichever works:**
  `py -V:3.12 scripts/render_uitest.py --out D` then EITHER
  `bun tools/render-harness/check-render.mjs D/output/index.html` (bun +
  happy-dom) OR, if that fails with `window.eval is not a function` (a known
  bun/happy-dom version-compatibility gap — hit in this session's cloud
  sandbox), `python tools/render-harness/check_render_playwright.py
  D/output/index.html` (real Chromium via Playwright).
  `tests/test_html_render_harness.py` wires both, `skipif`-ing whichever
  toolchain is absent. **Mandatory for any `templates/` / `app.js` /
  `style.css` / payload-builder change** — this session's `0.7.7` touched
  `static/app.js` and ran the Playwright path; `DEV.3.2` touched neither and
  skipped it correctly.
- **Never label a version as four dot-separated numbers** — `_IPV4_RE` in the
  privacy gate flags an `A.B.C.D` label as `PRIVATE_ENDPOINT_LITERAL`. Use a
  letter suffix (`0.7.4a`).
- **`bun install` in a sandbox with a newer bun binary than the committed
  `tools/render-harness/bun.lock` will rewrite the lockfile** — `git
  checkout -- tools/render-harness/bun.lock` afterward so that incidental,
  environment-specific change doesn't get committed.

## 2. Recent builds — landed on `main` via this merge

Detail is in `project/build_history.json`; phase docs under
`docs/history/phase/`. Newest first.

- **`compliance_trend_reconstruction` (0.7.7)** — offline retro-fill for
  `compliance_overview.history[]`. Follow-up to `0.7.5`'s deliberate
  no-backfill decision. Feasibility finding put to the product owner before
  writing code: most of `build_compliance_posture`'s inputs (alignment, CP
  structured config, control-assignment/waiver policy, CE.1 user checks) are
  not versioned per historical CAS snapshot, so a faithful full re-run is
  impossible. Owner chose narrow/labeled reconstruction over dropping the
  build or a broader silent approximation. New
  `utils/compliance_trend_reconstruction.py`
  (`reconstruct_pan_baseline_records`) mines stored PAN effective-running
  CAS snapshots, single-linkage time-clusters them into synthetic checkpoints
  (CAS has no `run_id`), and evaluates the ten deterministic
  `DEFAULT_RULE_PACK` baseline controls per entity through the exact same
  live evaluator dispatch a real checkpoint uses — PAN only, no alignment,
  no CP, no assignment/waiver replay, no CE.1. Every record carries
  `reconstructed: true` / `reconstruction_scope: "pan_baseline_rule_pack_only"`.
  `utils/compliance_history.py` gained `append_reconstructed` (idempotent on
  `run_id`) and `history_view`'s trend delta now always compares against the
  newest **live** record, never a reconstructed one. New offline `main.py
  --compliance-trend-reconstruct` maintenance mode. `static/app.js`'s
  `complianceSparkline` renders reconstructed runs dashed/hollow/reduced
  opacity. `docs/history/phase/0_7_7_COMPLIANCE_TREND_RECONSTRUCTION.md`.
- **`distributed_endpoint_lock` (DEV.3.2)** — `CollectionCoordinator`'s
  per-endpoint exclusion and per-vendor concurrency budget were
  single-process only; `DEV.3.1`'s container image means a second worker
  would silently lose that guarantee. New `utils/coordinator_backend.py`:
  `CoordinatorBackend` protocol, `InMemoryCoordinatorBackend` (prior logic
  moved verbatim + two incidental bug fixes), `PostgresCoordinatorBackend`
  (session-level `pg_advisory_lock` per endpoint, HMAC-derived lock keys so
  no device identity reaches Postgres), `verify_postgres_backend_ready`
  (fail-closed startup preflight rejecting a transaction-pooling proxy).
  Opt-in via `SECURITYEXPERT_COORDINATOR_BACKEND=postgres`; default
  (`memory`) unchanged. Verified against a real local PostgreSQL 16: cross-
  process coalescing, `SIGKILL` reclamation with no TTL/heartbeat, a real
  `pgbouncer 1.22` transaction-pooling proxy correctly rejected by the
  preflight. `docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md`.
  Backlog split: `distributed_endpoint_lock_and_job_store` →
  `distributed_endpoint_lock` (this build, done) +
  `distributed_evidence_store_migration` (DEV.3.3, still `planned`).
- **Stale-documentation correction** — see the "Corrected here" note in §1.
  `CURRENT_STATE.md`, the previous `AI_HANDOVER.md`, `docs/ARCHITECTURE.md`
  and `docs/design/COMPLIANCE_CHECK_ENGINE.md` all corrected to reflect the
  CP device-interaction-safety audit's actual 2026-08-25 closure.

## 3. Next work

**No active build contract is open.**

- **`distributed_evidence_store_migration` (DEV.3.3)** — CAS metadata index /
  run manifests / last-known-good → Postgres. Its own contract, deliberately
  not bundled with `DEV.3.2`. Not urgent — doesn't gate device safety.
- **`DEV.3.2` real-environment validation** (owed before `DONE`) — an actual
  multi-container deployment, both workers hitting a real Check Point MDS
  with `SECURITYEXPERT_COORDINATOR_BACKEND=postgres`. Server-blocked
  (`DEPLOY.1`, external).
- **CE.2** (`compliance_check_engine_primitives`) — curated read-only
  command-primitive registry, opt-in `--compliance-probe`. **The CP
  device-interaction-safety audit is closed, so CE.2 is unblocked on that
  front** — it still needs its own contract, each primitive through the
  10-point network-device command gate, and a real-environment validation
  gate (`docs/design/COMPLIANCE_CHECK_ENGINE.md` §5).
- **CE.3** / **CE.4** — `DEPLOY.1A` / OP.2-gated, unchanged.
- **CP-side compliance-trend reconstruction** — explicitly out of scope for
  `0.7.7` (see its §2/§6): blocked on a structured CP config projection
  existing at all (CP currently stores only redacted Gaia text in CAS). Its
  own, larger build.
- **`inventory_exclusions_management_ui`** — stays `in_progress` by design
  (backend-only landed in an earlier build this cycle,
  `inventory_exclusions_management_ui_backend`). Owed: the actual UI
  (add/restore buttons + audit view) and `DEPLOY.1A` OIDC/RBAC wiring. Do
  not wire the write functions into any HTTP-reachable surface before that
  boundary exists.
- **OP.x — Controlled Failover:** design done, approval pending
  (`docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`; `roadmap.json`
  `open_decisions`). Its OP.2 gate list includes the CP device-interaction
  audit (closed) and the distributed lock (now AUTOMATED_VALIDATED) among
  several still-open items (DEPLOY.1A OIDC/RBAC, mature TRACE/RECOVER,
  command gate for write primitives, signed change-management review) —
  don't read either closure as OP.2 being close to unblocked.
- **Other standing backlog items** (P1/P2 UI, performance) — read
  `project/backlog.json` directly rather than trusting a copied bullet list
  here.

## 3b. NOT YET DONE — real-environment / on-hardware validation

- **`DEV.3.2`** — the automated/local-Postgres evidence is real but not a
  substitute for an actual multi-container-against-a-real-MDS run.
- **`0.7.7`** — no live-device dependency (reads only already-stored CAS
  blobs); what's owed is running `--compliance-trend-reconstruct` against a
  real fleet's accumulated CAS history once a server exists.
- **`0.7.5`** — the trend sparkline/chip appears only after a *second* real
  full `py .\main.py` checkpoint (ledger starts empty, no backfill); `0.7.7`
  gives an alternative path for a fleet with existing PAN config history.
- Everything already listed under `on_hardware_real_env_validation` in
  `project/backlog.json` remains owed (needs an MDS/Panorama-reachable
  laptop or the `DEPLOY.1` server).

## 4. Open risks / debt carried forward

- CP device-interaction-safety audit is **closed** (2026-08-25) — do not
  reopen it; the remaining CE.2 prerequisite is the per-primitive command
  gate + a real-environment validation gate.
- `DEV.3.2`'s Postgres backend is opt-in and off by default — normal
  single-process operation is unchanged.
- `DEV.3.2` preflight (`verify_postgres_backend_ready`) is the load-bearing
  safety check for the Postgres backend — if a future deployment puts a
  transaction-pooling proxy in front of the database, session-level advisory
  locks silently stop working; the preflight is designed to catch this at
  startup, but if it's ever bypassed or weakened, this is the failure mode
  to remember.
- `DEV.3.2` lock-key stability depends on `data/.support_hmac.key` — a
  regenerated key silently changes every derived lock key (not unsafe, just
  confusing: things serialize or fail to coalesce, never share a lock across
  distinct endpoints). Makes `deploy_persistent_secret_material` (P1,
  `planned`) a hard dependency once `DEV.3.2` is used in production.
- `0.7.7` reconstruction bucketing is a time-gap heuristic (default 15
  minutes), not a real run correlation — CAS metadata carries no `run_id`.
  Reconfirm the default once real CAS history exists.
- `0.7.7`'s `catalog_version` on a reconstructed record is always *today's*
  catalog (rules aren't versioned historically either) — read a reconstructed
  point as "what today's rule pack would have found," not "what was reported
  at the time."
- `unified.interfaces` / `unified.routes` join is an exact normalised-identity
  match on the config-UI device name.
- `0.7.5` trend ledger: aggregates only, no backfill for *live* records.
- The `uitest` fixture is authored at the payload layer — regenerate it if a
  builder's output shape changes.
- The regex safety linter (`_REDOS_RE` + quantifier count + `.*.*`) is
  best-effort; the eval-time timeout is the real backstop.
- `0.7.4` framework catalog requirement lists are hand-authored.
- The CAS / support-key path writes `data/` + `logs/` into the repo dir
  during a test run (`DEV.0.3C` deferred). Gitignored; delete before the
  privacy gate.
- `scripts/pytest_one_shot.ps1` calls `py` → 3.14 without deps.
- The bun+happy-dom render-harness path can break on a `window.eval` gap
  depending on the installed bun version; the Playwright fallback
  (`tools/render-harness/check_render_playwright.py`) exists for exactly this.

## 5. Exact next action

**Fresh chat recommended.** Two independent, self-contained builds closed in
this merge (`0.7.7` and `DEV.3.2` + doc correction). Cold-start via
`AI_START_HERE.md` → this file → `CURRENT_STATE.md` → `project/roadmap.json`
+ `project/backlog.json`. Pick one §3 objective — `distributed_evidence_store_migration`
(DEV.3.3) or CE.2 are the two unblocked, well-scoped options — and write a
contract for user review **before** implementing.

## 6. main merge decision + Git dispatch

- This merge (`0.7.7`'s branch + `origin/main`, which already carried
  `DEV.3.2`) was done at the user's explicit request this session ("pull and
  merge, but check main first so nothing new gets erased") — the check
  surfaced the conflict on this file and `CURRENT_STATE.md`, resolved by
  hand rather than accepting either side blindly.
- Future builds: branch off `main`, commit, merge or PR per the user's
  standing preference. Human-initiated per standing priority 4 unless a
  session is given an explicit go-ahead, as both sessions were this cycle.
- `bun.lock` + `package.json` under `tools/render-harness/` are committed;
  `node_modules/` is not. A sandbox `bun install` with a newer bun binary can
  rewrite `bun.lock` incidentally — `git checkout` it back before committing.
  Delete gitignored `data/` + `logs/` before the privacy gate.

## 7. Next movement / model

- `IMPLEMENTATION` (**Sonnet 5, normal**) for `distributed_evidence_store_migration`'s
  contract once picked up, and for CE.2's contract once picked up.
- A genuinely new architecture/security-boundary design (DEV.3.3's exact
  Postgres schema/migration approach, CE.2's primitive registry shape, OP.0)
  wants **Sonnet 5 (or Opus), extended thinking** for the contract only, then
  normal reasoning for implementation.

## 8. Continue or fresh chat

**Start a fresh chat.** This merge closes two independent, self-contained
builds; the next objective is distinct and needs its own contract.

## 9. main.py / UI effect

- **`0.7.7`:** `main.py` gains one new opt-in offline CLI mode,
  `--compliance-trend-reconstruct` — nothing changes for an operator who
  never runs it. The Overview/Compliance sparkline renders reconstructed
  points dashed/reduced-opacity versus solid live-checkpoint points; the
  trend delta chip is unaffected.
- **`DEV.3.2`:** none visible in the UI/report. `main.py` gains a coordinator
  backend selection step at startup (silent when
  `SECURITYEXPERT_COORDINATOR_BACKEND` is unset/`memory`) and the scheduler
  gains a cross-process gate that only activates on the Postgres backend. No
  collector, transport, command, timeout, retry, or concurrency-budget
  *value* changed.
- **Stale-doc correction:** none — documentation and `project/*.json` only.
