# AI_HANDOVER

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase doc.
Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-01. Branch `main`, **uncommitted** — this session's changes only
  (architecture_convergence is already merged, PR #27).
- Product baseline `0.7.7`; engineering `DEV.3.3` — both unchanged.
- New this session: **`OP.0c` — Failover readiness UI — AUTOMATED_VALIDATED**
  — `UI` movement.

## 2. What changed this session

Built the Operator Console + report **Failover module**: a read-only fleet
view over `utils.failover.compute_ha_readiness` (OP.0a's domain logic,
untouched). No execution control, no new device command, no CLASS 2 job type.

- `utils/failover_readiness_ui.py` (new) — pure UI projection: extracts
  `cp_ha_runtime`/`pan_ha_runtime`/peers from already-loaded config-telemetry
  dicts, calls `compute_ha_readiness`, adds fixed verdict/check labels+tones
  and the OP.0a framing note (`SAFE_TO_FAILOVER` unreachable, `INSUFFICIENT_
  EVIDENCE` means "not asked yet"). No I/O, no verdict computation of its own.
- `application/workflows/failover.py`'s two CLI evidence loaders
  (`_load_cp_ha_runtime`/`_load_pan_ha_runtime`) now delegate to that module's
  `extract_cp_ha_runtime`/`extract_pan_ha_runtime` instead of duplicating the
  parsing — the CLI snapshot and the console's live projection can no longer
  disagree about what a telemetry file means.
- `utils/html_export.py` — `build_report_payloads` gained an eighth key,
  `failoverReadinessData`, computed live off the same `unified.json` +
  telemetry dicts already loaded for Configuration (no read of the CLI's
  cached `data/state/ha_readiness.json`, so there's one evidence path, not
  two). `SCRIPT_MODULE_FILENAMES` gained `failover_readiness_ui.js` (ninth
  module); `run_html_export` fills the matching placeholder.
- `static/failover_readiness_ui.js` (new) — renders the payload verbatim, no
  computation. `static/app_core.js` (global `failoverReadinessData`),
  `static/app_bootstrap.js` (module switch/init wiring) updated.
- `templates/console.html` + `templates/index.html` — both gained a
  `Failover` nav item + panel (kept in parity because both share the same JS
  bundle and the render harness clicks every nav button in both).
- Tests updated for the new payload key/module count:
  `tests/test_html_render_harness.py` (`_PAYLOAD_CONSTS`),
  `tests/test_frontend_module_composition.py` (`PAGE_LEVEL_CONSTS`, function
  floor 178→181), `tests/test_con1_operator_console_read_only.py` (AC-4
  payload-parity key list + `generated_at` strip).
- `tests/test_op0c_failover_readiness_ui.py` (new, 15 tests) — fail-closed
  verdict/check preservation (never `SAFE_TO_FAILOVER`, split-brain →
  `UNSAFE_DO_NOT_FAILOVER` with the specific reason, load-sharing →
  `NOT_A_FAILOVER_UNIT`), framing note present, extractor purity/parity with
  the CLI loader, no execution-control markup or network call in the shipped
  JS/HTML, no CLASS 2 job type registered.

**Design call made without asking, reversible:** the console computes
readiness live from evidence files rather than reading the CLI's cached
`ha_readiness.json` snapshot — matches CON.1's "always fresh, no in-memory
state" posture already established for every other console payload.

## 3. Exact next action

**Failover readiness real-environment closure** (`project/roadmap.json`
`now_next.next`) — confirm `OP.0a`'s `ha_cluster_mode` resolves against a
real CP/PAN HA pair and eyeball the new Failover module against that
evidence. No new code expected. The other open item on this path is a
product-owner/security decision on the `OP.0b` command-gate draft (blocks
`OP.0b`, not `OP.0c`).

## 4. Test delta

Full suite **1003 passed / 27 skipped / 0 failed**, serial (`pytest_result.log`).
From 988/27/0. `+15` `tests/test_op0c_failover_readiness_ui.py`.

Render harness green (happy-dom nav-click-through ran and passed; Playwright
variant skipped — no Chromium in this environment, same as baseline).
Privacy gate PASS (part of the full suite). `metadata_warnings == []`
(`tests/test_architecture_convergence.py`, run targeted + in full suite).
`git diff --check` clean (only CRLF-normalization warnings, no actual
whitespace errors).

## 5. New risks / debt

- **`tests/fixtures/uitest/` has no HA-runtime fixture**, so the render
  harness always shows every unit at `INSUFFICIENT_EVIDENCE`/
  `NOT_A_FAILOVER_UNIT` — correct given its inputs, but it means no human
  eyeballing an `UNSAFE_DO_NOT_FAILOVER` row without running the unit tests.
  Tracked: `project/backlog.json` `op0c_uitest_fixture_verdict_diversity`
  (P3, not required for OP.0c's own DoD).
- Carried over, unchanged: CLASS 2 stays empty
  (`test_no_console_job_type_is_class_2_or_above`, `utils/failover/` absence
  test — both still green). Tests still write into the gitignored repo-root
  `data/`. `C-D4`…`C-D8` remain open.

## 6. Continue or fresh chat

**Fresh chat.** OP.0c is closed and its own next step (real-environment
closure) needs no code context from this session — `AI_START_HERE.md` →
`CURRENT_STATE.md` → this file is sufficient.

## 7. main.py / UI effect

**New nav item "Failover" in both the Operator Console and the exported
report**, between Discovery and Exclusions. Opens to a fleet table of every
HA cluster/pair with a verdict pill (`INSUFFICIENT_EVIDENCE` / `NOT_A_
FAILOVER_UNIT` today, by design — no fixture or real environment yet reaches
`UNSAFE_DO_NOT_FAILOVER` or better), a framing banner explaining why
`SAFE_TO_FAILOVER` is unreachable, and an expandable per-unit stop-condition
breakdown. No button, no execute affordance — this is a read view. No CLI
flag or exit-code path changed.
