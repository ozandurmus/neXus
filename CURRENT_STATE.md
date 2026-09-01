# SecurityExpert — Current State

Hot-path state only. Historical build detail lives in
`project/build_history.json` (structured index) and `docs/history/` (archived
agreements and validation reports). `docs/history/INDEX.md` is the one-line
timeline.

- **Authoritative checkpoint:** 2026-09-01 (branch `main`, clean — RB.3b prep
  sign-off + steps 2–7 on `main`, real-environment run owed;
  `frontend_rendering_boundary` implemented; `codebase_modularization`
  (frontend + backend, including the real-browser follow-up) `DONE`; `CON.1`
  (read-only console, including its own real-browser follow-up) `DONE`;
  `CON.2` (job engine + `read`-class actions) `AUTOMATED_VALIDATED`,
  real-environment run owed — see "Active build" below)
- **Product baseline:** `0.7.7 — Compliance trend retro-fill (PAN baseline
  reconstruction)` — AUTOMATED_VALIDATED (0.7.x VERIFY track)
- **Previous:** `DEV.3.1 — Linux worker image + Compose` — AUTOMATED_VALIDATED
- **Previous:** `0.7.6a — Render harness + uitest topology matrix` — AUTOMATED_VALIDATED
- **Hotfix `0.7.4a`** (2026-08-30) — **REAL_ENV_VALIDATED**. The report's inline
  `<script>` was broken by a placeholder-substitution collision (a `project/*.json`
  note contains `__CRYPTO_JSON_PLACEHOLDER__`), which killed every module-nav
  button. Fixed by a single-pass template fill; product owner confirmed a full
  checkpoint render on the corporate laptop — all tabs work.
  `docs/history/phase/0_7_4A_HTML_EXPORT_RENDER_HOTFIX.md`.
- **Engineering baseline:** `DEV.1` complete; `DEV.2.1` (non-interactive runtime
  config) — AUTOMATED_VALIDATED; `DEV.3.1` (Linux worker image + Compose) —
  AUTOMATED_VALIDATED.
- **Product evidence baseline:** `0.6.1B.1.2` interactive Check Point configuration
  collection is REAL-ENVIRONMENT VALIDATED.

**Note on this file:** between the `671fd6c` merge (2026-08-30) and this
checkpoint, twelve further builds landed on `origin/main` — several
sessions ran without rewriting this file / `AI_HANDOVER.md` at close, so they
were only ever recorded in `project/build_history.json`. This checkpoint
re-syncs both files against `origin/main` HEAD. If this file's "Active build"
section and `project/build_history.json`'s newest entry ever disagree again,
`build_history.json` is authoritative — treat a disagreement as a docs-sync
gap to close, not a reason to trust this file over it.

---

## Architecture direction - local now, server later

**Productization review recorded 2026-08-31; no runtime behavior changed.** The
local product remains a single worker producing a portable static report. The
server target is deliberately a hardened Ubuntu + Compose deployment first,
not a premature Kubernetes, generic API, browser-command, or multi-worker
rewrite. The full decision record and module split sequence are in
`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`.

Local work may now remove dormant write-capable cleanup code, harden the report
rendering boundary, and split large source modules without changing behavior.
Server-only gates remain OIDC/RBAC, strict CP/PAN trust, report-only viewer
storage, least-privilege containers, migration/role separation, release
assurance, and off-host recovery artifact/key custody with a restore drill.

**Amended 2026-08-31 — the operator console (`CON.x`).** That review's "not a
premature generic API or browser-command rewrite" still holds and is not
relaxed. What changed is that the operational need it was conditioned on is now
stated: the BackBox exit needs an operator *surface*, not only a capability.
The sanctioned form is a **second delivery surface**, not a dynamic report —
`output/index.html` keeps its portable, shareable, action-free form, and a
separate authenticated loopback console serves the same UI modules and the same
payloads with an action layer on top. The browser sends intent against a closed
server-side job registry; it never sends a device command or an argv fragment,
and the console never becomes part of the nginx report viewer. Architecture:
`docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md`; the server doc carries the
reconciliation as its new §7.

## Active build

**`operator_console` (`CON.x`) — ARCHITECTURE FROZEN 2026-08-31, nothing
implemented.** `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (`CON.0`) plus all
five phase contracts, written in one pass and ready for separate implementation
sessions:

| Phase | Contract (`docs/history/phase/`) | Blocked on |
| --- | --- | --- |
| `CON.1` read-only console | `CON_1_OPERATOR_CONSOLE_READ_ONLY.md` | `codebase_modularization` (frontend) — **DONE**; `C-D1`, `C-D2` — **approved 2026-09-01, both consumed as-is**. `CON.1` itself: **DONE 2026-09-01** (implemented, then closed the same session by a human real-browser open — see below). |
| `CON.2` job engine + `read` actions | `CON_2_CONSOLE_JOB_ENGINE_READ_ACTIONS.md` | `CON.1` — **DONE**; `C-D3` — **resolved 2026-09-01 (add `'console'`)**. `CON.2` itself: **AUTOMATED_VALIDATED 2026-09-01**, real-environment run owed — see below. |
| `CON.3` `operational-write` actions | `CON_3_CONSOLE_OPERATIONAL_WRITE_ACTIONS.md` | `CON.2`; **`RB.3b` REAL_ENV_VALIDATED**; `C-D4`, `C-D6` |
| `CON.4` Recovery module (`RB.5`) | `CON_4_CONSOLE_RECOVERY_MODULE.md` | `CON.2` |
| `CON.5` scheduler surface | `CON_5_CONSOLE_SCHEDULER_SURFACE.md` | `CON.2`; `C-D7` |

The architecture doc is the spec; this file does not restate it. Eight open
decisions (`C-D1`…`C-D8`, in `project/roadmap.json` `open_decisions`) are
product-owner / security calls, not engineering work. `C-D1`/`C-D2` were
answered 2026-09-01 (both per their documented recommendation: optional
`fastapi`+`uvicorn` dependency; cookieless per-launch bearer token in the URL
fragment), which unblocked and completed `CON.1` the same session — see below.
`C-D3` was answered the same day (add `Provenance.CONSOLE = "console"`, per
its documented recommendation), which unblocked and shipped `CON.2` the same
session — see below. `C-D4`…`C-D8` remain open, blocking `CON.3` onward.

**`CON.2` (job engine + `read`-class actions) — AUTOMATED_VALIDATED
2026-09-01 (`Sonnet 5, normal`).** `docs/history/phase/CON_2_CONSOLE_JOB_ENGINE_READ_ACTIONS.md`.
`utils/collection_executor.py` gained `workflow_argv()` (C2-2), promoted out
of `application/workflows/maintenance.py`'s `_scheduler_workflow_argv` (now a
one-line wrapper) so the scheduler and the console share one argv
construction path. `utils/coordinator_backend.py` gained
`Provenance.CONSOLE = "console"` (C2-3); the required consumer audit found
every consumer already stores/echoes `provenance` as an unvalidated free
string (`Job`, `RunContext`, both coordinator backends' Postgres `TEXT`
column, the discovery/coordinator UI payload) — additive, no schema change.
`utils/evidence_backend.py` gained a sixth storage concern,
`ConsoleJobBackend` (filesystem default + opt-in PostgreSQL, same shape as
`DEV.3.3`'s five), for durable job records under
`data_root/state/console_jobs`. New `console/registry.py` (`JOB_REGISTRY` —
seven closed job types, `cp_gaia_backup` declared but refused per C2-6),
`console/jobs.py` (`ConsoleJobStore`: C2-9 idempotency, C2-5 crash recovery,
forbidden fields excluded by construction), `console/runner.py`
(single-worker FIFO executor, C2-7 — calls `main.main()` only, AC-2, never a
collector or vendor module). `console/app.py` gained `POST /api/jobs`,
`GET /api/jobs`, `GET /api/jobs/{job_id}`, `GET /api/jobs/{job_id}/events`
(SSE, C2-10), `GET /api/job-types`; `create_app()`'s new `job_store`/`runner`
params are optional (auto-constructed) so `CON.1`'s existing route-table test
and any read-only-only caller keep working unmodified. `application/cli.py`'s
`--console` branch now also builds `ctx.services` before `run_console`, held
for the console process's lifetime so admission state stays consistent
across every job — the same continuity the scheduler already has across its
own dispatch loop. `static/console_actions.js` gained the action surface
(job-type buttons — an `operational-write` type always renders `BLOCKED`,
C2-6; a recent-jobs table; a manual SSE reader via `fetch()`+`ReadableStream`,
since native `EventSource` cannot carry the `Authorization` bearer header
CON.1's auth model requires) in `templates/console.html`'s Discovery module.
New `tests/test_con2_console_job_engine.py` (AC-1…AC-11, 26 tests, every test
patches `main.main` so no test contacts a device); `CON.1`'s own AC-2
route-table test updated in place to allow the one deliberate mutating route
this phase adds (`POST /api/jobs`). Full suite **933 / 27 / 2** vs pre-build
baseline **907 / 27 / 2** — same two pre-existing pollution failures, zero
regressions, `+26 passed`. Privacy gate PASS/0. **Owed before `DONE`:** a
watched real-environment run where a console-triggered `read`-class job
reaches a real device and produces the same artifact an equivalent CLI run
would — hardware-gated, not engineering.

**`CON.1` (operator console, read-only surface) — DONE
2026-09-01 (`Sonnet 5, normal`).** `docs/history/phase/CON_1_OPERATOR_CONSOLE_READ_ONLY.md`.
New `console/` package (`auth.py` token/origin checks, `payloads.py`,
`app.py` FastAPI routes + CSP header, `server.py` fail-closed preflight +
uvicorn bootstrap), `templates/console.html`, `static/console_actions.js`,
`main.py --console [--console-port N]` (mutually exclusive maintenance-class
mode, no credential), `requirements-console.txt`. `utils/html_export.py`
gained `MODULE_ORDER`/`compose_modules()` and `build_report_payloads()` — the
console and the exporter now call the identical payload builder (C1-2/C1-4:
drift between them is impossible by construction, not by discipline).
`app_bootstrap.js`'s initialization became `initializeReport(payloads)`
(C1-3); static mode (`templates/index.html`) calls it with the inline JSON
constants, console mode calls it after an authenticated `/api/payloads`
fetch. AC-8 (console imports no vendor module, transitively) surfaced three
pre-existing eager `configuration.*` imports reachable from
`utils/html_export.py` (`utils/config_ui.py`, `utils/config_history.py`,
`utils/crypto_posture.py`) — made lazy at point of use, same
`DEV.3.3`-established pattern, behavior-preserving for every other caller.
New `tests/test_con1_operator_console_read_only.py` (AC-1…AC-11). Full suite
**907 / 27 / 2** vs the pre-build baseline **896 / 26 / 2** — same two
pre-existing pollution failures (reproduced on an unmodified checkout of the
same batch), zero regressions, `+11 passed / +1 skipped`. Privacy gate
PASS/0. Render harness green. Live end-to-end smoke: `main.py --console`
served real HTTP on `127.0.0.1`, verified over curl. AC-1's own real-Chromium
Playwright test skips cleanly in this sandbox (Chromium binary not
downloaded) — same as `tests/test_frontend_rendering_boundary.py`'s
Playwright tests — but the human real-browser open itself (a real Chromium
tab via the Browser pane, same session) **was** performed: `main.py --console`
launched against the `tests/fixtures/uitest` bundle, driven live through all
seven modules. It found two real regressions neither pytest nor the
bun/happy-dom render harness caught — (1) three JS-set inline `style=""`
attributes were blocked outright by the console's stricter CSP (`style-src
'self'`, no `unsafe-inline`) — fixed with a `w-pct-0`…`w-pct-100` CSS class
set (`static/style.css`); (2) `inventory_ui.js`/`configuration_ui.js`/
`compliance_ui.js` each computed a derived collection once at module-load
time against the still-empty default payload, before `initializeReport`
assigns the real one — **this broke Network Inventory / Configuration /
Compliance in the exported static report too, not only the console** — fixed
with `rebuildX()` functions called both at load and from `initializeReport`.
Re-verified after both fixes: fresh-tab walk through all seven modules, zero
console errors, real data rendered and visually correct. Full suite unchanged
at **907 / 27 / 2**. `CON.1` is now **DONE**.


**`codebase_modularization` (frontend half) — IMPLEMENTED 2026-09-01
(`Sonnet 5, normal`).** `static/app.js` (flat 4,905 lines / 173 top-level
functions) split into the eight D-MOD5 files — `app_core`, `inventory_ui`,
`configuration_ui`, `compliance_ui`, `discovery_ui`, `project_plan_ui`,
`overview_ui`, `app_bootstrap` — concatenated by `utils/html_export.py` in
fixed dependency order into the same single inline `<script>` (no bundler, no
ES modules). New `compose_report_script()` helper; 16 source-string UI tests
repointed to it; new `tests/test_frontend_module_composition.py` (AC-3 static
dependency-order check). Zero code lines lost (rendered-report line-multiset
diff before/after: only 8 header comments + a 5-line relocation note added).
Render harness (bun) green on the uitest and empty-state renders; privacy gate
PASS/0. Full suite **887 / 26 / 2** vs `main` **882 / 27 / 2** — same two
pre-existing pollution failures, zero regressions. `docs/history/phase/CODEBASE_MODULARIZATION_FRONTEND.md`.
The human interactive real-browser open of this split is now **CLOSED
2026-09-01**: the `tests/fixtures/uitest` topology-matrix bundle was rendered
(`scripts/render_uitest.py`) and driven live in a real browser through all
seven modules (Overview, Network Inventory, Configuration, Compliance,
Discovery, Exclusions, Project Plan) — zero console errors/warnings, every
module populated correctly with real fixture data. Both halves of
`codebase_modularization` are `done` per the backend half's own entry below.

**`codebase_modularization` (backend half) — IMPLEMENTED 2026-09-01
(`Sonnet 5, normal`), same day as the contract freeze.**
`docs/history/phase/CODEBASE_MODULARIZATION_BACKEND.md`. `main.py` (2,089
lines; `main()` alone ~1,690) is now a 47-line thin entry that delegates to
`application.cli.run` and re-exports the F4 test-coupled surface. New
`application/` package (`cli.py` / `services.py` / `context.py` /
`workflows/{maintenance,recovery,checkpoint}.py`) per
`SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md` §5, moved verbatim
apart from de-closuring locals into an `ApplicationContext` dataclass. New
`tests/test_application_package.py` makes the lazy vendor-import boundary a
tested invariant (AC-3 static + AC-5 runtime `sys.modules` check via clean
subprocess) and pins `main.main`'s frozen signature (AC-6); an AC-4 before/after
CLI transcript diff over the offline mode matrix (git worktree vs
`main@c4a7b6f`) was empty except run-scoped noise. Full suite **896 / 26 / 2**
(same two pre-existing pollution failures; +9 new tests), zero regressions.
Privacy gate PASS/0. One real pre-existing gap the new AC-3 check surfaced
(not introduced by this build): `main.py`'s top-level `utils.config_storage`
import was already pulling in `lxml` transitively on every invocation,
contradicting the documented lazy-import contract — closed by moving it into
`storage_analyze()`/`storage_deduplicate()` (first use), zero behavior change.
Two test files' source-string/name-patch assumptions about code living in
`main.py`'s namespace could not survive AC-1's ≤120-line `main.py`; repointed
to the new `application/*.py` locations (same class of mechanical repoint the
frontend half applied to 16 tests) — see the phase doc's "Implementation
deviations". The **vendor-collector split** (`configuration/pan/`,
`configuration/checkpoint/`) stays explicitly **out of scope** — §5 moves
those only when a bounded feature touches them; it opens as its own
feature-scoped backlog entry if/when that happens. `project/backlog.json`
`codebase_modularization` is now `done` for both halves (the frontend half's
human interactive real-browser open remains a cheap, non-blocking follow-up).


**`distributed_evidence_store_migration` (DEV.3.3) — AUTOMATED_VALIDATED
2026-08-31, merged to `main` (`ae10bf7`).** The evidence-integrity half split from DEV.3.2.
Contract frozen after product-owner review, then implemented:
`docs/history/phase/DEV3_3_DISTRIBUTED_EVIDENCE_STORE_MIGRATION.md`.

New `utils/evidence_backend.py` puts four stores behind backends — CAS
metadata index, run manifests, last-known-good, scheduler state — each with a
filesystem implementation carrying today's exact behavior (the default,
unchanged) and an opt-in PostgreSQL one selected by
`SECURITYEXPERT_EVIDENCE_BACKEND` / `SECURITYEXPERT_EVIDENCE_POSTGRES_DSN`.
Deliberately independent of DEV.3.2's `SECURITYEXPERT_COORDINATOR_BACKEND`:
either may be enabled without the other. **Content-addressed payload blobs
never move** — they stay on the runtime volume on both backends.

The contract's one open decision (**E1**) was put to the product owner and
resolved to **full identity fidelity**: the Postgres index carries
`device`/`management_ip`/`entity_id` exactly as `metadata.json` does today,
and that instance is now documented as a **CLASS 2 identity-bearing asset**
in `PRIVACY_AND_DATA_HANDLING.md` (dedicated instance, TLS DSN, restricted
role, encryption at rest).

Nine implementation-time findings are recorded as explicit contract
amendments (A1–A9) rather than silently absorbed. Two were substantive:

- **A1** — moving last-known-good to per-entity rows does *not* by itself fix
  the lost-update race the build exists to close; the caller's
  load-mutate-save-whole-map pattern reproduces it against the table. So
  `build_failure_aware_snapshot` now reads and writes each entity
  individually, while the filesystem backend buffers those writes and still
  performs exactly one whole-file write per run.
- **A9** — found by the two-real-subprocess test: PostgreSQL's `CREATE TABLE
  IF NOT EXISTS` does not serialize against a concurrent identical `CREATE`,
  so two worker containers starting together against a fresh database could
  crash one of them. Schema creation now runs under a transaction-level
  advisory lock (pooler-safe, unlike DEV.3.2's session-level locks).

17 new tests (`tests/test_dev3_3_evidence_store_migration.py`) against a real
local PostgreSQL 16. Full suite **788 passed / 3 skipped / 2 failed** — the
same two pre-existing unrelated failures, zero regressions (the skip count
fell from 11 because DEV.3.2's Postgres tests also run when an instance is
available). Privacy gate PASS / 0. `main.py` gains a fail-closed startup
preflight; a misconfigured Postgres backend stops at a clean `parser.error`.

**Owed before `DONE`:** a multi-container real-environment run proving
last-known-good state for a fleet split across containers matches a
single-container run (server-blocked, DEPLOY.1). Backfilling existing
filesystem history into Postgres is deliberately out of scope (same
no-backfill precedent DEV.3.2 set).

**Active build: `RB.3b` — CP Gaia system backup collection** (`add backup local`
+ SCP fetch, class `operational-write`). Contract frozen and signed off
2026-08-31 (`D3` resolved — scoped to the fail-closed
`SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES` pilot allowlist, scheduling not
approved; `D4` / §7.3 p14 / §7.7 / §7.8 gates signed off; ledger design + §3
rule 5 accepted). Full contract, decisions B1–B11, AC-1…AC-14, and the
step-by-step plan are in
**`docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md`** — that is the spec;
this file does not restate it.

- **Steps 2–7 IMPLEMENTED 2026-08-31** (offline layer, device core + C6,
  `main.py` wiring, project-metadata sync). Detail is in
  `project/build_history.json` (`RB.3b-impl-step5`/`-step6`/`-step7`, plus
  the earlier `-steps-2-4`) and the phase doc's own "Definition of done" —
  not restated here. Full suite **879 / 23 / 2** (the 2 are the known
  pre-existing pollution), zero regressions. Privacy gate PASS/0.
- **Status stays `in_progress` — not `IMPLEMENTED`** — until the mandatory
  watched real R81.10/R81.20 single-gateway run has happened (that run also
  resolves the §7.7/§7.8 command-string + `add backup local` output-format
  confirm-on-hardware questions). That run is the only thing left; it is
  external/hardware-gated, not an engineering task.
- **`RB.3a`** — CP Gaia backup/snapshot attestation (`show backups` /
  `show snapshots`, `read`) — AUTOMATED_VALIDATED 2026-08-31, real-env owed.
  `docs/history/phase/RB_3A_CP_GAIA_BACKUP_ATTESTATION.md`.
- **`RB.3c`** — CP management export + consistency groups — blocked on `D5`
  (storage budget) and `E1` (§7.6 `operational-write` classification
  unverified). Sequenced after RB.3b's first watched real-environment run.

**`remove_dormant_remote_cleanup` — DONE 2026-08-31** (local security
hardening, no device contact). `utils/cleanup.py` — unreferenced, but
capable of issuing unaudited `rm -f` over SSH with the collection credential
— deleted; `tests/test_remove_dormant_remote_cleanup.py` regression-guards
its absence. `project/backlog.json` entry closed.

**`frontend_rendering_boundary` — AUTOMATED_VALIDATED 2026-08-31, implemented
in the same session the contract was frozen in.**
`docs/history/phase/FRONTEND_RENDERING_BOUNDARY.md` — CSP `<meta>` tag added
to `templates/index.html` (`default-src 'none'`, `'unsafe-inline'` only for
the inlined script/style; `frame-ancestors` dropped mid-implementation once
found to be spec-unsupported via `<meta>` delivery — see the doc's
"Implementation findings"). AC-2's exhaustive sink audit reviewed all 97
`static/app.js` `.innerHTML` sinks individually (not the earlier 28-of-97
heuristic sample) and found **zero gaps** — no fix required, a real negative
result. New `tests/test_frontend_rendering_boundary.py` (5 tests: CSP
exact-match, static + real-Chromium hostile-label checks, `_script_json`
breakout neutralization) plus two hostile-label devices added to
`tests/fixtures/uitest/unified.json`. Evidence: full suite 888 passed / 23
skipped / 0 failed; privacy gate PASS/0; render harness green including the
real-Chromium path. The "manual browser check" was performed via real
Chromium driven by Playwright (zero console errors across all seven
modules, screenshots captured) rather than literal human interaction — this
sandbox has no display — which is why this stays AUTOMATED_VALIDATED rather
than DONE; a human interactive open on a real workstation is a cheap,
non-blocking follow-up, not a known defect.

**`codebase_modularization` (frontend half) — CONTRACT FROZEN 2026-08-31,
not yet implemented.** `docs/history/phase/CODEBASE_MODULARIZATION_FRONTEND.md`
— splits `static/app.js` (169 top-level functions, one flat file) into eight
responsibility-owned source files composed back into the same single inline
portable `<script>` (no bundler, no ES modules, no build step). Grounded in
the same session's `frontend_rendering_boundary` full read of the file. An
8-file module ownership table (amends the architecture doc's 7-file
proposal with a new `overview_ui.js`), two justified deviations from that
doc's literal suggestion (no `window.SecurityExpert` namespace; no
shared-state bucket in `app_core.js`), a new static dependency-order
regression test design, and a 7-step implementation plan are frozen for a
fresh session at `Sonnet 5, normal`. No source touched this session — scope/
audit/design only. The backend half (`main.py` / vendor-collector
splitting) stays unscoped, under the same backlog id.

The P0 `cp_device_interaction_safety` audit **closed 2026-08-25**; do not
re-cite it as open. The earlier seed prompt
`docs/history/handover/RB3_NEXT_CHAT_PROMPT.md` is superseded by the three
contracts above.

**Previously (before this session): no build open.** This checkpoint reconciles two independent sessions that
landed in parallel on separate branches: this session's RECOVER track
(`recovery_collect_rb2_rb4` + predecessors, PR #15) and a separate session's
`compliance_trend_reconstruction` (`0.7.7`) + `distributed_endpoint_lock`
(`DEV.3.2`) + a stale-doc correction, already on `origin/main`. Both are
below, most recent first per branch; neither superseded the other.

**CORRECTION (fixed in this merge):** this session's own RECOVER-track work
(architecture doc, contracts, `checkpoint/checkpoint_recovery_collector.py`,
`project/backlog.json`'s `native_backup` note) repeatedly cited the CP
device-interaction-safety audit (P0) as "not started" — that was stale. Per
`project/backlog.json`'s `cp_device_interaction_safety` (see "Standing
priorities and blockers" below), **it closed 2026-08-25**, before this
session began. `RB.3`'s real remaining blocker is `D3` alone (the
product-owner decision on the `operational-write` command class) plus
`add backup local`'s own command-gate sign-off (drafted, not yet approved,
in contract §7.3) — not an unstarted audit. Corrected everywhere this merge
touches; **do not re-cite the audit as open.**

Last landed on this session's branch: `recovery_collect_rb2_rb4`
(2026-08-30, this session). **`D2` RESOLVED** (product owner approval,
recorded in
`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §13): the PAN service
account may hold superuser for device-state export only. **`RB.2` —
IMPLEMENTED, real-environment validation owed** (no device reachability in
this sandbox). **`RB.4` — AUTOMATED_VALIDATED.**

Explicit product direction this build follows: recovery collection must not
be logic inlined in `main.py`; must be selective per gateway; must be
scheduler-integrated from day one. New `utils/recovery_collect.py` is the one
orchestration layer — target selection (`"all"` or an explicit `entity_id`
list, VSX `__vsid_` addressing included; an unresolvable id fails before any
device is touched), a `RecoveryCollector` protocol for vendor dispatch, and
admission-coordinated execution where one gateway's failure never aborts the
batch. `main.py --recovery-collect --recovery-vendor {panorama|checkpoint}
[--recovery-gateways ...]` is a thin CLI that builds a request and dispatches
— the same call a future UI action or the scheduler makes.
`panorama/panorama_recovery_collector.py` implements PAN device-state export
(contract §7.1, `read` class, gate-documented before this build) — session
reuse, no 403 retry. `checkpoint/checkpoint_recovery_collector.py` is a typed
blocked stub (`D3` unresolved — the P0 audit itself is closed, see the
CORRECTION above) so the orchestration/store/admission wiring is already
correct for CP once `D3` is decided and the command gate signs off.
`utils/collection_executor.py` gains
`"recovery-pan"` in `ALLOWLISTED_WORKFLOWS` (not `"recovery-cp"`) and an
additive optional per-schedule `targets` field — every existing scheduler
policy file's meaning is unchanged. **Correction recorded in the architecture
doc:** scheduled recovery collection does *not* need to wait for
`distributed_endpoint_lock_and_job_store` under the current single-container
deployment — verified end-to-end with a real `--scheduler-once` run.

Separately, `utils/recovery_validation.py` (RB.4) implements the V1–V3
battery (contract §4); `main.py --recovery-validate` rewrites each held
artifact's `manifest.validation`. A real bug was caught and fixed here: the
initial gate only checked the top-line verdict, but a V2-only failure still
reports `verdict=INTACT` (V1 passed) — the gate now scans every individual
check for a `FAIL`, not just the summary verdict.

85 new tests (`tests/test_rb2_recovery_collect.py`,
`tests/test_rb4_recovery_validation.py`); one pre-existing test
(`test_allowlisted_workflows_are_read_only`) updated in place for the
intentional allowlist expansion. `py -m pytest -q`: 741 passed, 3 skipped, 2
pre-existing unrelated failures unchanged. Privacy gate PASS/0.
`project/build_history.json` entry `recovery_collect_rb2_rb4`.

**Next:** `RB.3` (CP) remains blocked on `D3` alone (P0 audit closed — see
CORRECTION above). PAN
configuration-XML export (contract §7.2, secondary artifact) not yet built.
**Known gap:** PAN artifact `software_version` is recorded as the honest
`"unknown"` sentinel — `unified.json` carries no PAN version field, and
inventing an undocumented device command to fetch one was deliberately
avoided; this should become its own gate-reviewed item. **`D1` is still a
product-owner action, not engineering** — vendor scope is frozen to CP+PAN.

Prior: `recovery_store_rb1` — **AUTOMATED_VALIDATED** (2026-08-30).
Recovery-plane store: encryption, manifest, retention, validator, no
collection. `utils/recovery_crypto.py`, `recovery_manifest.py`,
`recovery_store.py`, `recovery_retention.py`; `resolve_recovery_root`; `main.py
--recovery-store-check`; `docker-compose.yml` `securityexpert-recovery` volume
on `worker` only. 41 tests.

Prior: `restore_readiness_rb0` — **AUTOMATED_VALIDATED** (2026-08-30). First
implementation against the frozen contracts (§5): `utils/restore_readiness.py`
+ `main.py --restore-readiness-check`. 16 tests. Manually verified against the
uitest fixture: 15 devices → 14 `UNPROTECTED` + 1 `UNKNOWN` — the first real
number for the `D1` BackBox-replacement decision.

Prior: `backup_recovery_architecture` — **DESIGN FROZEN** (2026-08-30).
ARCHITECTURE movement, no code. Rebases the deferred `original 0.6.0B`
native-backup milestone. Driver: BackBox is not being renewed in 2027.
`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` (three-plane model,
per-vendor analysis, phasing `RB.0`–`RB.6`, seven open decisions `D1`–`D7`) +
`docs/design/BACKUP_RECOVERY_CONTRACTS.md` (frozen shapes, the 10/14-point
command gate entries — drafts for review, not approvals — retention, twelve
security invariants). Central boundary: configuration evidence is deliberately
redacted (`secrets_redacted: True`) and therefore **non-restorable by
design** — today's Configuration module makes it easy to assume otherwise.

Prior: `deploy_persistent_secret_material — persistent
runtime volume contract` (DEV.2.2) — **AUTOMATED_VALIDATED** (2026-08-30, this
session). `data/.support_hmac.key` persistence across a container restart was
already structurally correct via `runtime_paths.data_root`; new
`utils/persistent_secret_material.py` + `main.py --persistent-secret-material-check`
make that contract explicit and offline-checkable (value-free, reuses
`utils.cp_ssh_trust` / `utils.pan_tls_trust` preflight code verbatim). New
`docker-compose.prod.yml` overlay mounts `deploy/secrets/known_hosts` +
`pan-ca-bundle.pem` read-only and sets `SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY=1`
/ `SECURITYEXPERT_PAN_CA_BUNDLE`, moving CP/PAN trust from opt-in to
mounted-and-required on the server while `docker-compose.yml` keeps
compatibility mode as the base default. `docs/history/phase/DEV2_2_PERSISTENT_SECRET_MATERIAL.md`;
`project/build_history.json` entry `deploy_persistent_secret_material`.

Last landed on the parallel `main`-side session's branch:
`compliance_trend_reconstruction — 0.7.7 Compliance trend retro-fill` —
**AUTOMATED_VALIDATED** (2026-08-30). Follow-up to `0.7.5`'s deliberate
no-backfill decision. Feasibility check found most of
`build_compliance_posture`'s inputs (alignment, CP config,
assignment/waiver policy, CE.1 checks) are not versioned per historical CAS
snapshot — put the scope trade-off to the product owner directly, who chose
narrow/labeled reconstruction over dropping the build or a broader
unlabeled approximation. New `utils/compliance_trend_reconstruction.py`
mines stored PAN effective-running snapshots, time-clusters them into
synthetic checkpoints (CAS carries no `run_id`), and evaluates the ten
deterministic `DEFAULT_RULE_PACK` baseline controls per entity through the
exact same live evaluator dispatch a real checkpoint uses. Every record is
stamped `reconstructed: true` /
`reconstruction_scope: "pan_baseline_rule_pack_only"` and the trend delta
never uses one as `prev`. New offline `main.py --compliance-trend-reconstruct`
maintenance mode (no network, no credentials — merged into this branch's
`main.py` alongside `--recovery-collect`/`--recovery-validate`, all three
cross-guarded against each other during conflict resolution).
`project/build_history.json` entry `compliance_trend_reconstruction`;
contract + impl record `docs/history/phase/0_7_7_COMPLIANCE_TREND_RECONSTRUCTION.md`.

Landed just before it, on a separate session/branch
(`claude/cp-device-interaction-markdown-ret13v`, merged to `main` in
`eb6cd81`, now folded into this branch by this merge): `DEV.3.2 —
distributed per-endpoint lock` (`distributed_endpoint_lock`) —
**AUTOMATED_VALIDATED** (2026-08-30). `CollectionCoordinator` now delegates
to a `CoordinatorBackend`; the new `PostgresCoordinatorBackend`
(`SECURITYEXPERT_COORDINATOR_BACKEND=postgres`) gives the single-process
per-endpoint lock and per-vendor budget a cross-process equivalent via
session-level `pg_advisory_lock`, opt-in and off by default. Real
cross-process exclusion, crash reclamation, and preflight pooler-rejection
were each verified against an actual local PostgreSQL 16 instance (real
subprocess `SIGKILL`, a real `pgbouncer` in transaction mode).
`docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md`. That same
branch also corrected a standing documentation-staleness bug: the CP
device-interaction-safety audit (P0) actually closed 2026-08-25 (with its
`collection_execution_coordinator` follow-on REAL_ENV_VALIDATED 2026-08-27),
but `CURRENT_STATE.md`, the old `AI_HANDOVER.md`, `docs/ARCHITECTURE.md` and
`docs/design/COMPLIANCE_CHECK_ENGINE.md` all kept citing it as an open P0
blocker — see "Standing priorities and blockers" below, now corrected.

Prior: `immutable_store_permission — evidence-store snapshot-publish retry`
— **AUTOMATED_VALIDATED** (2026-08-30). `ConfigEvidenceStore._write_snapshot`'s
directory-publish `os.replace(tmp_dir, final_dir)` now retries on transient
lock the same way `_ensure_blob`'s blob write already did
(`_replace_with_retry`, 3 attempts, 0.1s exponential backoff) — closes the
standing P1 intermittent `PermissionError`. `project/build_history.json`
entry `immutable_store_permission`.

Twelve further predecessor builds this cycle (all landed on `origin/main` between
`671fd6c` and `101f75b`, detail in `project/build_history.json`, newest
first):

- `linux_container_image` (`101f75b`, `DEV.3.1`) — Linux worker image +
  Compose: `python:3.12-slim` worker (idle by default), `docker-compose.yml`
  pairing it with `nginx:1.27-alpine` over a shared loopback-only volume. No
  collector/transport/retry/concurrency semantic change.
- `pytest_feature_area_markers` (`5b49aa6`) — 7 pytest markers
  (`inventory`/`configuration`/`compliance`/`discovery`/`render`/
  `runtime_platform`/`security`) so `pytest -m <area>` runs a feature slice;
  purely additive, zero test logic touched.
- `inventory_exclusions_management_ui_backend` (`3463b71`) — write-path
  backend only (`add_exclusion`/`restore_exclusion` + fail-closed audit
  ledger), deliberately not wired into any HTTP-reachable surface pending
  DEPLOY.1A auth. **Stays `in_progress`** in `project/backlog.json` by
  design — the UI and OIDC/RBAC wiring are still owed.
- `playwright_render_harness_fallback` (`996aeca`) — `tools/render-harness/
  check_render_playwright.py`, a real-Chromium alternative to the bun+
  happy-dom DOM-execution check for when that toolchain's script-eval shim
  breaks against a newer bun/happy-dom pairing (as it does in this sandbox —
  used directly by this session's own 0.7.7 validation).
- `dev_python_env_tooling_friction` fix (`a593761`) — POSIX runtime-root
  default + `pytest_one_shot.ps1` interpreter pin.
- `uitest-fixture discovery_ui fix` (`2a18a3d`) — `discovery_ui.json` fixture
  corrected to match the real builder shape.
- `html_render_performance` (`e761c9d`) — opt-in stage timing + measured
  profiling report.
- `overview_device_lifecycle_enrichment` (`6dd82b7`) — fleet-composition
  card, increment 1.
- `inventory_exclusions_ui` (`ab5a9a5`) — read-only Exclusions module, phase 1.
- `cp-identity-edges` review (`3e8af0e`) — CP identity-gate edge-case review,
  no defect found.
- `cp-unknown-platform` (`8b3fc28`) — CP platform classification propagated
  into discovery lifecycle.
- `cp-ha-runtime` (`7e25391`) — per-VS HA role probe + explicit direct-Clish
  capability-gap signal.
- `immutable_store_permission` (`cb2f6f5`) — evidence-store
  snapshot-directory publish retry (this was "not yet pushed" in the prior
  version of this file; it is pushed and landed as of this checkpoint).

Predecessors before that (all AUTOMATED_VALIDATED 2026-08-30; detail in
`project/build_history.json`):

- `0.7.6a — Render harness + uitest topology matrix` —
  `docs/history/phase/0_7_6_RENDER_HARNESS.md` §4. `tests/fixtures/uitest/`
  expanded to a full topology matrix (CP standalone/ClusterXL/VSX
  host+cluster/UNAVAILABLE gateway; PAN single/HA/multi-vsys/multi-vsys HA);
  new `test_all_topologies_present`.
- `0.7.6 — HTML render harness` — `docs/history/phase/0_7_6_RENDER_HARNESS.md`.
  `tools/render-harness/check-render.mjs` (bun + happy-dom) parse-checks the
  inline `<script>`, clicks every nav module + tab, asserts no console errors.
- `0.7.5 — Compliance trend layer` — `docs/history/phase/0_7_5_COMPLIANCE_TREND.md`.
  Append-only ledger `data/state/compliance_history.json`;
  `compliance_overview.history[]` + `.trend`; deliberately **no backfill**
  (closed by `0.7.7` above).
- `0.7.4a — HTML export render hotfix (P0)` — see header. **REAL_ENV_VALIDATED.**
- `0.7.4 — framework_mappings: Requirement-Level Coverage` —
  `docs/history/phase/0_7_4_FRAMEWORK_REQUIREMENTS.md`.

Earlier 0.7.x / 0.6.x predecessors: see `project/build_history.json` /
`docs/history/INDEX.md` for the full timeline (0.7.3 CE.1 check engine, 0.7.2
compliance follow-ups, 0.7.1b assignment/waivers, 0.7.1a control catalog,
0.7.0 crypto-agility/PQC).

**Deferred:** a signed / user-authored framework pack (custom frameworks + a
UI mapping editor) — `DEPLOY.1A`-gated. CP-side trend reconstruction —
blocked on a structured CP config projection existing at all (CP currently
stores only redacted Gaia text; see `0_7_7` §2/§6).

**Product trajectory (owner, 2026-08-29):** the end-state is a **write-capable
device administration platform**; read-only now is a staging phase. Every
VERIFY-plane design must keep a future enforce/remediate capability additive.

---

## Next builds (frozen contracts)

- `DEPLOY.1 — Ubuntu + Docker Server Migration & Git Repository Foundation` —
  **CONTRACT_FROZEN** (2026-08-27). No runtime behavior change before server
  arrival. `DEV.3.1` (this cycle) is the first container-migration slice
  under this contract; the OIDC viewer boundary, evidence egress policy, CP
  strict host-key R2 validation and PAN TLS corporate-CA validation gates are
  still owed on server arrival.
  Handover: `docs/history/handover/DEPLOY_1_CONTRACT_FREEZE_HANDOVER_2026_08_27.md`
- After the engineering-readiness checkpoint, product architecture proceeds
  toward `0.6.1C` follow-ups already validated in the 0.6.x track.
- `OP.x — Controlled Failover` (new track, OPERATE theme): design frozen in
  `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`. Write-free parts (OP.0 HA
  readiness assessment + SCC dashboard, OP.1 dry-run plan compiler) are
  buildable post-`DEPLOY.1`; OP.2 controlled execution is hard-gated (see the
  doc's §10 and `roadmap_notes`).

---

## Standing priorities and blockers

1. **CP device-interaction-safety audit (P0)** — CLOSED (`backlog.json`
   `cp_device_interaction_safety`, AUTOMATED_VALIDATED 2026-08-25;
   `collection_execution_coordinator` REAL_ENV_VALIDATED 2026-08-27). This
   line stayed stale here after both closed — corrected 2026-08-30. Any
   recurring-scheduling / concurrency-budget-increase build still needs its
   own real-environment evidence (not a reopened audit).
   The single-process coordinator's admission model now has a cross-process
   equivalent: `distributed_endpoint_lock` (P0) reached AUTOMATED_VALIDATED
   2026-08-30 (`docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md`) —
   `SECURITYEXPERT_COORDINATOR_BACKEND=postgres` opts a `CollectionCoordinator`
   into session-level Postgres advisory locks for cross-process endpoint
   exclusion and budget admission; default (`memory`) is unchanged. Real
   multi-container-against-a-real-MDS evidence remains owed before DONE —
   server-blocked (DEPLOY.1, external). The CAS metadata index / run
   manifests / last-known-good half is split out as
   `distributed_evidence_store_migration` (P0, `planned`, its own contract).
2. The admission coordinator concurrency budget stays at 1 per vendor pending
   its own real-environment evidence — unaffected by the above.
3. DEPLOY.1 gates are blocked on server availability (external, ~1 week).
4. Corporate Git push/merge remains **human-controlled**.
5. `inventory_exclusions_management_ui_backend` stays `in_progress` by design
   — do not wire its write functions into any HTTP-reachable surface before
   DEPLOY.1A's OIDC/RBAC boundary exists.

## Known xfails

- VSX network canonicalization.
- PAN default-route classification.

(Both were converted to passing regressions in 0.6.6A; reconfirm on the next
full regression run.)

## Automated test baseline

```
933 passed / 27 skipped / 2 failed (2026-09-01, after CON.2; +26
tests/test_con2_console_job_engine.py, zero new failures)
Prior: 907 passed / 27 skipped / 2 failed (2026-09-01, after CON.1 including
its real-browser close; +11 tests/test_con1_operator_console_read_only.py)
Prior: 896 passed / 26 skipped / 2 failed (2026-09-01, after codebase_modularization
backend; +9 tests/test_application_package.py, zero new failures)
Prior: 804 passed / 20 skipped / 2 failed (2026-08-31, after RB.3a, no live
PostgreSQL; +33 RB.3a tests, zero new failures)
Prior: 788 passed / 3 skipped / 2 failed (with a live PostgreSQL 16;
763 / 11 / 2 without one)
The 2 failures are pre-existing and unrelated to any build in this cycle
(both pass in isolation — test-order pollution):
  tests/test_phase0_6_1c_discovery_capability_ui.py::
    test_run_html_export_embeds_discovery_payload_without_leftover_placeholder
  tests/test_phase0_7_5_compliance_trend.py::test_checkpoint_render_appends_one_record
Repository privacy gate: PASS / 0 on a clean checkout. Locally it flags the
gitignored `data/` + `logs/` + `data/.support_hmac.key` that a test run
creates — delete them before running the gate.
```

Run one-shot and read from file (see `docs/AI_DEVELOPMENT_PROTOCOL.md`):
`py -m pytest -q > pytest_result.log 2>&1`

Render harness: `bun tools/render-harness/check-render.mjs <index.html>` is
the primary check; when the bun+happy-dom `window.eval` shim breaks against
the installed bun version (observed in the cloud sandbox this session), fall
back to `python tools/render-harness/check_render_playwright.py <index.html>`
(real Chromium via Playwright — `playwright_render_harness_fallback` build).
Both are wired into `tests/test_html_render_harness.py`.

---

## Engineering foundation completed before DEV.1

`DEV.0` repository readiness is complete except the intentionally deferred
pre-server storage checkpoint:

- `DEV.0.1` runtime management endpoint decoupling — DONE / real-env validated.
- `DEV.0.2` repository sanitization — DONE.
- `DEV.0.3A/B/B.1` runtime path foundation + artifact migration + direct-SSH
  closure — DONE / real-env validated.
- `DEV.0.3C` History/CAS runtime boundary — DEFERRED / pre-server; not a
  Corporate Git blocker. (Config-evidence CAS still lives at repo-root
  `data/configs` — `utils/config_evidence.py`'s `CONFIG_ROOT` default — not
  under `RuntimeRoot`; the compliance-trend ledger already lives under
  `RuntimeRoot`, so this deferral is CAS-specific, not repo-wide.)
- `DEV.0.4 / 0.4.1` local repository privacy gate + runtime inventory exclusion
  policy — DONE; clean candidate, 0 findings.
- `DEV.0.5A/B/B.1/B.2` authentication boundary + canonical config + repository-wide
  DLP closure — DONE.

## Copilot audit follow-up debt

- Environment authentication overrides remain explicit operational compatibility
  paths; do not remove implicitly.
- PAN authentication transport behavior is not fully converged across old/new
  paths; track under explicit security hardening.
- Production CP SSH host-key trust and PAN TLS corporate-CA trust remain
  production gates.
