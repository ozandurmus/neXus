# AI_HANDOVER

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase doc.
Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-01.
- Product baseline `0.7.7`; engineering `DEV.3.3` — both AUTOMATED_VALIDATED,
  unchanged. `RB.3b` `in_progress` (hardware-gated), unchanged.
- `codebase_modularization` (both halves) `DONE`, unchanged this session.
- `CON.x` operator console: `CON.1` (read-only surface) stays `DONE`,
  unchanged this session. `C-D3` (Provenance vocabulary) was put to the
  product owner and resolved to **add `"console"`** per its documented
  recommendation. `CON.2` (job engine + `read`-class actions) was
  implemented and reached **AUTOMATED_VALIDATED** the same session — see §2.
- Branch: `main`, working tree has this session's changes staged but not yet
  committed (see §6). Prior commits (`135a201`, `6e22e0a`, `686dfa5`) remain
  pushed to `origin/main` from the previous session.

## 2. What changed this session

**C-D3 resolved**, then **`CON.2` implemented** against the already-frozen
contract (`docs/history/phase/CON_2_CONSOLE_JOB_ENGINE_READ_ACTIONS.md`):

- `utils/collection_executor.py`: new `workflow_argv(workflow, runtime_root,
  targets=())` (C2-2). `application/workflows/maintenance.py`'s
  `_scheduler_workflow_argv` is now a one-line wrapper over it — the
  scheduler and the console job runner share one argv construction path.
- `utils/coordinator_backend.py`: new `Provenance.CONSOLE = "console"`
  (C2-3). The required consumer audit (recorded in
  `project/build_history.json`'s `operator_console_job_engine` entry) found
  every consumer — `Job.to_manifest_dict`, `RunContext.set_job_metadata`,
  both `InMemoryCoordinatorBackend`/`PostgresCoordinatorBackend` (the
  Postgres column is a plain `TEXT NOT NULL`, no `CHECK` constraint), the
  discovery/coordinator UI payload and `static/discovery_ui.js`'s rendering
  — already stores/echoes `provenance` as an unvalidated free string.
  Additive; no schema or consumer change was needed anywhere.
- `utils/evidence_backend.py`: a sixth storage concern, `ConsoleJobBackend`
  (`FilesystemConsoleJobBackend` default + opt-in
  `PostgresConsoleJobBackend`, same abstraction shape as `DEV.3.3`'s five),
  for durable job records under `data_root/state/console_jobs`. Wired into
  `verify_evidence_backend_ready()`'s startup preflight.
- New `console/registry.py` — `JOB_REGISTRY`, the closed C2-1 table of seven
  job types (`inventory_refresh_cp`, `inventory_refresh_vsx`,
  `config_refresh_pan`, `config_refresh_cp`, `recovery_attest_cp`,
  `report_rebuild`, and `cp_gaia_backup` declared but refused per C2-6).
- New `console/jobs.py` — `ConsoleJobStore`: C2-9 idempotency (a repeated
  `Idempotency-Key` returns the original record, never creates a second
  job), C2-5 crash recovery (`sweep_orphaned_running()`, called once at
  console startup), forbidden fields (credentials, management addresses,
  raw device output, stack traces, etc.) excluded by construction —
  `JobRecord` simply has no such field.
- New `console/runner.py` — `ConsoleJobRunner`: single-worker FIFO executor
  (C2-7). Calls `main.main()` exactly as the scheduler's
  `_evaluate_and_dispatch_due_workflows` does, and nothing else (AC-2 — no
  collector or vendor module is ever imported here).
- `console/app.py`: new `POST /api/jobs`, `GET /api/jobs`,
  `GET /api/jobs/{job_id}`, `GET /api/jobs/{job_id}/events` (SSE, C2-10 —
  job-record state transitions only, never collector output),
  `GET /api/job-types`. `create_app()`'s new `job_store`/`runner` parameters
  are optional (auto-constructed against an ephemeral in-memory coordinator
  when omitted), so `CON.1`'s existing test fixture and any read-only-only
  caller keep working unmodified.
- `application/cli.py`: the `--console` branch now also calls
  `services.build_collection_services()` before `run_console`, so the
  console process holds one `RuntimeCollectionServices` for its whole
  lifetime — passed to every job the runner executes, giving admission-state
  continuity across jobs (the same continuity the scheduler already has
  across its own dispatch loop). `console/server.py`'s `run_console` gained
  a required `services` parameter and now builds/starts the job store and
  runner before serving.
- `static/console_actions.js` / `templates/console.html`: the CON.2 action
  surface in the Discovery module — job-type buttons (an
  `operational-write` type always renders `BLOCKED` per C2-6, never a
  button that would 409 on click), a recent-jobs table, and a manual SSE
  reader built on `fetch()` + `ReadableStream` rather than the native
  `EventSource` API, which cannot carry the `Authorization` bearer header
  CON.1's auth model requires. `static/style.css` gained matching styles.
- New `tests/test_con2_console_job_engine.py` (26 tests, AC-1…AC-11 plus
  C2-1/C2-3/C2-6 coverage; every test patches `main.main`, so no test
  contacts a device). `tests/test_con1_operator_console_read_only.py`'s
  AC-2 route-table test updated in place to allow the one deliberate
  mutating route this phase adds (`POST /api/jobs`).
- `project/roadmap.json` (`C-D3` → decided), `project/backlog.json`
  (`operator_console` entry), `project/feature_registry.json`
  (`operator_console_job_engine` → `done`), `project/build_history.json`
  (new `operator_console_job_engine` entry), `CURRENT_STATE.md`, this file.

**Real-browser verification (same session, no real device):** launched
`main.py --console` against the `tests/fixtures/uitest` bundle (same
monkeypatch technique `scripts/render_uitest.py` / CON.1's own real-browser
close used) and drove it live in a real Chromium tab. The Discovery
module's new job panel rendered all six `read`-class job-type buttons plus
`cp_gaia_backup` correctly dashed/disabled as "BLOCKED"; clicking "Rebuild
report" drove a real `main.main(['--render-only'])` run to completion with
zero browser console errors — confirmed not just by the UI but by the
scratch runtime root's `output/index.html` actually being rewritten on disk
— and the jobs table updated to "succeeded" with a `run_id` live via the
SSE reader, with no manual reload. This is UI/engine verification, not the
hardware-gated real-device run still owed (§3).

One implementation-time fix mid-session: the new `ConsoleJobBackend` section
was initially inserted between `utils/evidence_backend.py`'s existing
operational-write-ledger section and its `Backend selection` header, which
made `tests/test_rb3b_operational_ledger.py`'s forbidden-SQL source scan
(a string-slice between those two markers) sweep up the new section's
legitimate `UPDATE console_job SET ...` statement and fail. Fixed by
relocating the whole new section earlier in the file (before the ledger
section, after "Last-known-good backend") — no behavior change, purely a
source-ordering fix so that test's slice boundary stays correct.

## 3. Exact next action

`CON.2` is AUTOMATED_VALIDATED, not yet `DONE` — the one thing left is a
**real-environment run** (`HUMAN_REAL_ENV` gate per the contract's own
validation section): on the corporate laptop, trigger a `read`-class job
from the console (e.g. `inventory_refresh_cp` or `config_refresh_pan`) and
confirm it reaches a real device and produces the same artifact an
equivalent CLI run would. No new code is needed for this — it needs device
reachability, which this sandbox does not have.

**Start `CON.3`** (`operational-write` actions,
`docs/history/phase/CON_3_CONSOLE_OPERATIONAL_WRITE_ACTIONS.md`) is blocked
on two things per `CURRENT_STATE.md`'s dependency table: `RB.3b` reaching
**REAL_ENV_VALIDATED** (a UI must never be the first thing to run a device
write nobody has run by hand — `RB.3b` itself is hardware-gated, not
engineering), and decisions `C-D4`/`C-D6` (project/roadmap.json
`open_decisions` — still open, product-owner/security calls). Whoever picks
this up next should check `RB.3b`'s status first; if it is still
hardware-gated, `CON.3` cannot start regardless of the decisions.

Other independent, unrelated options (unchanged from before this session):

- `OP.0`/`OP.1` — design-frozen, write-free, buildable now.
- `RB.3b` itself — blocked on the external watched real-device R81.10/R81.20
  run (hardware-gated, not engineering); this is also `CON.3`'s blocker, so
  progress here unblocks two things at once.

## 4. Test delta

Full suite **933 passed / 27 skipped / 2 failed** (`pytest_result.log`),
up from **907 / 27 / 2** after `CON.1`. `+26` from the new
`tests/test_con2_console_job_engine.py`. The 2 failures are the same
pre-existing order-pollution pair as every prior session (confirmed
unchanged, both pass in isolation). Repository privacy gate `PASS/0` —
local runs leave the same known `data/`/`logs/` runtime artifacts every
prior session's evidence also notes; delete them before re-running the gate
if you need a clean-checkout result.

## 5. New risks / debt

- **Real-environment validation owed** for `CON.2` (see §3) — the same
  `HUMAN_REAL_ENV` pattern every hardware-adjacent build in this project
  carries; not a defect, just not yet observed against a real device.
- `static/console_actions.js`'s SSE reader is hand-rolled specifically
  because native `EventSource` cannot carry a bearer header under CON.1's
  cookieless auth model. Worth remembering if a later phase is tempted to
  switch back to `EventSource` "for simplicity" — it would silently break
  auth on that one endpoint.
- Carried over from the previous handover, still true: `fastapi`/`uvicorn`/
  `httpx` are installed in the active dev environment but only captured in
  `requirements-console.txt` (the optional path); `tests/test_con1_*` and
  `tests/test_con2_*` have no top-level skip guard for a missing
  FastAPI/uvicorn — a CI environment that doesn't install
  `requirements-console.txt` would fail both files at collection rather
  than skipping cleanly. Not resolved.
- `C-D4`…`C-D8` remain open, blocking `CON.3` onward (see §3).

## 6. Continue or fresh chat

**Either works**, but this session's changes are **uncommitted** — the
working tree has the full `CON.2` implementation staged as edits, not yet
committed to `main`. Whoever continues (this session or a fresh one) should
commit before doing anything else, per this repo's "Corporate Git push/merge
remains human-controlled" standing priority — confirm with the user before
committing/pushing.

## 7. main.py / UI effect

`application/cli.py`'s `--console` dispatch branch changed (now also builds
`ctx.services`); `console/server.py::run_console`'s signature gained a
required `services` keyword. No other CLI flag, mode, or exit-code path
changed. The exported static report (`output/index.html`) is unaffected —
`console_actions.js` stays outside `utils.html_export.MODULE_ORDER`
(asserted by `tests/test_con2_console_job_engine.py`'s AC-11 test), so the
static report still carries zero action surface.
