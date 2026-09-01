# SecurityExpert — Current State

Hot-path checkpoint only. **Predecessor build detail is not here** — it is in
`project/build_history.json` (structured, newest-first, the authority on what
shipped when) and its linked documents under `docs/history/`.
`docs/history/INDEX.md` is the generated one-line timeline.

- **Checkpoint:** 2026-09-01, branch `main`.
- **Product baseline:** `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
- **Engineering baseline:** `DEV.3.3` — AUTOMATED_VALIDATED. `DEV.1` complete.
- **Product evidence baseline:** `0.6.1B.1.2` interactive Check Point
  configuration collection is REAL_ENV_VALIDATED.

## Reading this file

`project/roadmap.json` owns NOW / NEXT / AFTER / BLOCKED / DEFERRED.
`project/feature_registry.json` owns feature delivery state.
`project/backlog.json` owns debt. `project/build_history.json` owns history.
This file owns only the hot checkpoint above and the sections below, and it
must not contradict them — `utils/project_plan._cross_authority_warnings` plus
`tests/test_architecture_convergence.py` fail the build if it does.

---

## Safety status — the action taxonomy

`utils/action_taxonomy.py` is the single source of truth; `AI_START_HERE.md`
carries the full table.

| Class | Permitted? | Where |
| --- | --- | --- |
| 0 — read | yes | everywhere; most of the product |
| 1 — controlled recovery write | yes, **only** under the `RB.x` contracts | CLI only; never console-submittable |
| 2 — operational state change (failover) | **no member exists** | hard-gated, `FAILOVER_ENGINE_ARCHITECTURE.md` §10/§10.1 |
| 3 — configuration write | prohibited | — |
| 4 — policy / deployment / remediation | prohibited | — |

**"The product is read-only" is no longer true and must not be restored** — it
stopped being true when `RB.x` shipped `add backup local` and a bounded backup
deletion. `"operational-write"` in existing code and durable job records is the
legacy name for **class 1 only**; the RB.3b ledger's own `command_class` column
is a different concept again (an *artifact* class, `"cp_gaia_backup"`).

Standing boundaries that hold today:

- No Browser → device path. The console submits typed intent (`job_type` +
  `entity_id`) against a closed module-level registry; no command, argv
  fragment, path or API route ever originates in the browser.
- `utils/failover/` contains `assessment.py` only. The absence of a plan,
  executor or vendor adapter is test-enforced, not merely current.
- `OP.0a` cannot emit `SAFE_TO_FAILOVER` or `DEGRADED_PROCEED_WITH_RISK`,
  enforced over a generated matrix.
- Corporate Git push/merge remains human-controlled.

## Active build

**`architecture_convergence` — AUTOMATED_VALIDATED 2026-09-01.** Source audit of
the repository against its own documentation, plus the repairs the audit found
necessary. No device contact, no new device command, no dependency change.

What it changed, and why each mattered:

- **The read-only claim was false in four canonical documents** (`README.md`,
  `AI_START_HERE.md`, `docs/ARCHITECTURE.md`'s "Read-only invariant",
  `docs/AI_DEVELOPMENT_PROTOCOL.md`'s "No new write command"). Replaced by the
  five-class taxonomy above, in code at `utils/action_taxonomy.py`.
- **The console could not tell a backup from a failover.** Its vocabulary was
  `read | operational-write`; a Gaia backup (class 1) and a cluster failover
  (class 2) resolved identically. `console/registry.py` now derives its class
  from the taxonomy and the refusal names the class.
- **Six authorities each claimed the current build and disagreed.**
  `roadmap.json` said `0.7.4` (completed 2026-08-29) while the newest build
  record was `OP.0a` (2026-09-01) and `feature_registry.json` still called that
  same work `planned`. `utils/project_plan._cross_authority_warnings` now
  compares the files *against each other* (six rules); the pre-existing
  `metadata_warnings == []` assertion became a real gate rather than a
  vocabulary check.
- **`docs/history/INDEX.md` claimed to be generated and was not** — it had
  drifted to a newest row of `0.7.4`. `scripts/build_history_index.py` now
  generates it; `--check` fails when the checked-in copy is stale.
- **The two long-standing "order-dependent" test failures were neither
  order-dependent nor flaky.** Root cause: `scripts/render_uitest.py` rebound
  three `utils.html_export` payload builders and never restored them — a bare
  module assignment, not `monkeypatch.setattr`. Two test files call `render()`
  in-process, so every later `run_html_export()` in that worker silently
  returned uitest *fixture* payloads. Under `-n auto` the polluter usually
  landed on a different worker, which is why the suite could report zero
  failures while the defect was still there. Fixed with a `try/finally`
  restore; `tests/test_frontend_rendering_boundary.py` now asserts the
  restoration directly.
- **This file was 764 lines**, carrying a narrative for eleven predecessor
  builds that each already had a `build_history.json` record and a phase doc —
  the exact thing `AGENTS.md` "Handover economy" forbids. Now capped at 200
  lines by test.

## Exact next build

**`OP.0c` — failover readiness UI module.** The
`FAILOVER_ENGINE_ARCHITECTURE.md` §9 surface over the `ha_readiness.json`
`OP.0a` already produces: fleet view, readiness light, blocking reasons,
history. Read-only, no new device command, buildable now.

It is NEXT because it is simultaneously the next Operator Console surface and
the next step on the failover path, which is the sequencing this convergence
established: **Operator Console → Failover**, with no unrelated feature track
allowed to preempt it.

Two things it must get right:

1. **Carry `OP.0a`'s framing.** Every unit reports `INSUFFICIENT_EVIDENCE` or
   `NOT_A_FAILOVER_UNIT` today, by design. The CLI prints the framing itself
   ("`INSUFFICIENT_EVIDENCE` means 'not asked yet', not 'unhealthy'"). Without
   the same line in the UI, an empty-looking dashboard reads as a broken
   feature.
2. It touches `templates/` / `static/` / a payload builder, so it triggers the
   HTML render harness **and** a `tests/fixtures/uitest/` growth step
   (`AGENTS.md` project-state update rule).

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| `OP.0b` preflight battery (~16 read commands) | its command gate is drafted in the `OP.0a` contract but **not approved** — a product-owner/security call | decision |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6` **and** `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run — hardware, not engineering | hardware |
| `OP.2` controlled failover execution | every `FAILOVER_ENGINE_ARCHITECTURE.md` §10 prerequisite, incl. `DEPLOY.1A` OIDC + an RBAC `OPERATE` role | multiple |
| `DEPLOY.1` gates | server availability (external) | external |
| `inventory_exclusions_management_ui_backend` | stays `in_progress` **by design** — do not wire its write functions into any HTTP-reachable surface before `DEPLOY.1A`'s OIDC/RBAC boundary exists | design |

Concurrency budget stays at 1 per vendor pending its own real-environment
evidence. Any recurring-scheduling or budget-increase build needs that evidence
first.

## Real-environment validation owed

- **`CON.2`** — trigger a `read`-class job from the console against a real
  device. No new code; closes it to DONE.
- **`OP.0a`** — one real-device confirmation that `ha_cluster_mode` resolves
  rather than falling back to `"unknown"`. The mode fixtures are constructed,
  not captured. Fixture-drift check, not a safety gate.
- **`RB.3b`** — the watched single-gateway run (above).
- **`DEV.3.2`** — real multi-container-against-a-real-MDS evidence for the
  Postgres advisory-lock path. Server-blocked.

## Automated test baseline

```
988 passed / 27 skipped / 0 failed  (2026-09-01, serial, after architecture_convergence)
  Prior: 971 / 27 / 2 serial — the 2 were the render_uitest rebind leak, now fixed.
Repository privacy gate: PASS / 0 findings, 415 files scanned, clean checkout.
Project-state consistency: metadata_warnings == [] under all six cross-authority rules.
```

Run one-shot and read from file: `py -m pytest -q > pytest_result.log 2>&1`.

**Run the suite serially at least once before closing a build.** The leak above
was invisible under `-n auto --dist worksteal` roughly two runs in three. A
green parallel run is not evidence of an isolated suite.

The gate also flags the gitignored `data/` + `logs/` that a test run creates —
delete them before running it. That tests write into the repository-root
`data/` at all is known shared-state debt, tracked, not yet fixed.

## Known xfails

- VSX network canonicalization.
- PAN default-route classification.

(Both were converted to passing regressions in 0.6.6A; reconfirm on the next
full regression run.)

## Production posture

Development-ready, **not** production-ready. The container runs as root by
design at this stage. Open before any production claim: OIDC/RBAC, trusted
TLS/SSH in production, database role separation, report-only publication
surface, secret management, off-host recovery custody with a restore drill,
audit retention. `.github/workflows/validation.yml` is the deterministic CI
gate; it runs no device, container or registry step.
