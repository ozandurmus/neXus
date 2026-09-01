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

**`OP.0c` (`failover_readiness_ui`) — Failover readiness UI module —
AUTOMATED_VALIDATED 2026-09-01.**
A read-only Failover module in both the Operator Console and the exported
report: a live projection over `utils.failover.compute_ha_readiness` (fleet
view, per-unit verdict, per-`STOP_CONDITIONS` blocking reasons, the OP.0a
fail-closed framing note carried verbatim). No execution control anywhere in
the shipped markup/JS, no new device command, no CLASS 2 job type.

- New: `utils/failover_readiness_ui.py` (pure UI projection; owns no verdict
  logic, only labels/tones), `static/failover_readiness_ui.js`. `application/
  workflows/failover.py`'s two CLI evidence loaders now delegate to this
  module's extractors instead of duplicating them.
- `utils.html_export.build_report_payloads` gained an eighth key,
  `failoverReadinessData`; `SCRIPT_MODULE_FILENAMES` gained a ninth module.
  Both `templates/console.html` and `templates/index.html` gained a
  `Failover` nav item + panel — the render harness clicks every nav button in
  both, and CON.1's payload-parity test now covers this key too.
- Computed live off the same `unified.json` +
  `cp_config_telemetry.json`/`pan_config_telemetry.json` the console already
  loads for Configuration — no read of the CLI's cached
  `data/state/ha_readiness.json` snapshot, so there is exactly one evidence
  path rather than two that could drift.
- `tests/fixtures/uitest/unified.json` has no matching HA-runtime fixture, so
  the render harness always shows every unit at `INSUFFICIENT_EVIDENCE` /
  `NOT_A_FAILOVER_UNIT` — correct given its inputs; `UNSAFE_DO_NOT_FAILOVER`
  is covered at the unit level instead (`project/backlog.json`
  `op0c_uitest_fixture_verdict_diversity`, P3, not required for DoD).

**Predecessor:** `architecture_convergence` — AUTOMATED_VALIDATED 2026-09-01
(five-class action taxonomy, one project-state authority, `render_uitest.py`
module-rebind leak root-caused and fixed). Detail:
`project/build_history.json`.

## Exact next build

**Failover readiness real-environment closure** (no new build contract —
see `project/roadmap.json` `now_next.next`). Confirm `OP.0a`'s
`ha_cluster_mode` resolution against a real CP/PAN HA pair and eyeball the new
Failover module against that real evidence; a product-owner/security decision
on the `OP.0b` command gate is the other open item on this path. No new code
expected. `OP.0b` (the preflight battery) remains blocked on that gate.

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
- **`OP.0a`/`OP.0c`** — one real-device confirmation that `ha_cluster_mode`
  resolves rather than falling back to `"unknown"`, and a real-evidence
  eyeball of the new Failover module. The mode fixtures are constructed, not
  captured. Fixture-drift check, not a safety gate.
- **`RB.3b`** — the watched single-gateway run (above).
- **`DEV.3.2`** — real multi-container-against-a-real-MDS evidence for the
  Postgres advisory-lock path. Server-blocked.

## Automated test baseline

```
1003 passed / 27 skipped / 0 failed  (2026-09-01, serial, after OP.0c)
  Prior: 988 / 27 / 0 serial (architecture_convergence) — +15 tests, +0 failures.
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
