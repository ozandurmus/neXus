# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase
doc. Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-06. Branch `claude/left-nav-vertical-redesign-e673q6`,
  incorporates `origin/main` at `9a946fe` (PR #84, M1) via a non-destructive
  `--no-ff` merge. NAV review lineage (`5a5a1f7`..`259874e`) preserved
  unrewritten. PR from this branch into `main` not yet opened at the point
  this file was last written this session — see "Exact next action".
- Build: `nav_1_accessibility_closure` (`M2`) — **AUTOMATED_VALIDATED**:
  `AC-A11Y-1`..`4` closed and validated on this branch; `AC-A11Y-5` confirmed
  unregressed. Predecessors both complete: `M0` (architecture FROZEN,
  reviewed head `ba56d2b`) and `M1` (PCP.1 uuid4 test-defect repair,
  merged via PR #84).
- **Validated ≠ merged.** The NAV.1 prototype is not delivered/mergeable-complete
  until this branch's PR actually lands on `main` — see `project/feature_registry.json`
  and `tests/test_navigation_information_architecture.py`'s guard 2.

## 2. What is frozen (unchanged by M2)

- **`docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md`** — FROZEN — PRODUCT
  OWNER APPROVED. Fifteen `D-NAV` decisions, **no operative row provisional**;
  three-layer IA; the four-predicate capability model; the capability-state
  presentation matrix over existing canonical states; the logical-entity-first
  workspace; the preservation, shell-parity and accessibility contracts; the
  eight `PO-NAV` decisions; and **§19 acceptance criteria**
  (`AC-NAV-*`, `AC-WS-*`, `AC-DIF-*`, `AC-SH-*`, `AC-A11Y-*`).
- **`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md`** — FROZEN.
  Runtime lifecycle, background typed jobs, device-targeted execution, local
  storage **Option A** with its ownership boundary, credential/trust
  references, UI-first enrollment under **seventeen** conditions, the
  `M1`…`M14` sequence with §12.1 clarifications, and **§15 acceptance
  criteria** (`AC-RT-*`, `AC-TGT-*`, `AC-ST-*`, `AC-EN-*`).
- **`docs/design/research/NSPM_NAVIGATION_BENCHMARK.md`** — deliberately **not**
  a frozen contract, unchanged.
- No `D-NAV`/`PO-NAV` decision reopened by M2. No parent-contract amendment
  beyond M0's own (`CON.0` §4.1/§7.11, `PCP.0` §19/§10/§20.1) — M2 touched none
  of them.

## 3. What M2 actually did

- **Integration:** `--no-ff` merge of `origin/main` (`9a946fe`) into this
  branch at pre-integration head `259874e`. Only the four expected
  authority/state files conflicted (`CURRENT_STATE.md`, `project/roadmap.json`,
  `project/build_history.json`, `docs/history/INDEX.md`); every code/doc/test
  file auto-merged cleanly — no product/architecture contradiction.
- **`AC-A11Y-1`** — every rail button (root link, grouped child, group toggle)
  now carries an explicit `aria-label` matching its visible label, independent
  of `.nav-label`'s icon-only-mode visibility; `title` stays a supplementary
  tooltip only.
- **`AC-A11Y-2`** — a `prefers-reduced-motion: reduce` media query zeroes the
  two transitions the prototype actually introduces (`.primary-nav` rail
  width, `.nav-chevron` rotation); nothing else touched.
- **`AC-A11Y-3`** — `switchModule()` gained an opt-in `moveFocus` option. Every
  genuine user-triggered navigation call site passes it, moving focus to the
  activated panel's own `<h1>` (`tabindex="-1"`, so it does not join the
  ordinary tab order). The passive initial render and console payload
  refreshes (both call `switchModule()` via `initializeReport()` with no
  options) never steal focus; neither does a fresh load restoring a route
  from its hash.
- **`AC-A11Y-4`** — each group's `<ul class="nav-children">` carries
  `role="group"` + `aria-labelledby` pointing at its own toggle button,
  surviving both rail states. Proved for Devices, Operations, Administration
  (plus the console-only Jobs group).
- **`AC-A11Y-5`** — confirmed unregressed (not owned by M2, but merge-blocking):
  the interface matrix still overflows only its own `.table-container` at a
  narrow viewport; the page body never scrolls horizontally.
- New `tests/test_m2_nav_accessibility_closure.py` — real-Chromium coverage
  for all five, computed accessible name/role via Playwright's `get_by_role`
  (never a source-string search), covering both the exported-report and
  console shells.

## 4. Exact next action

1. Push this branch; open a pull request into `main`.
2. Inspect CI on the PR (do not assume green); resolve only in-scope failures.
3. Merge once the PR is mergeable and required checks are green.
4. Sync local `main` to the resulting `origin/main`; record the exact merge
   commit in `project/build_history.json`/`CURRENT_STATE.md` if not already
   done pre-merge, and flip `project/feature_registry.json`'s
   `left_vertical_product_navigation.status` from `in_progress` to `done`
   only once the merge has actually happened.
5. Do **not** start `M3` (capability-state vocabulary) in this session — that
   is the correctly-derived next movement, but a new session's own
   authorization.

## 5. Test delta

- Targeted: `tests/test_navigation_information_architecture.py` +
  `tests/test_m2_nav_accessibility_closure.py` +
  `tests/test_architecture_convergence.py` +
  `tests/test_frontend_module_composition.py` +
  `tests/test_frontend_rendering_boundary.py` + both render harnesses +
  `tests/test_con1_operator_console_read_only.py` +
  `tests/test_con2_console_job_engine.py` +
  `tests/test_pcp1_device_registry.py` — **182 passed, 1 skipped, 0 failed**.
- Full parallel suite: `py -m pytest -q -n auto --dist worksteal` (4 workers,
  `nproc`-detected) — **1958 passed, 23 skipped, 0 failed**, wall-clock
  **29.46s**. Completely clean; no serial rerun performed (none needed).
- `metadata_warnings == []`; build-history index current; privacy gate
  PASS / 0 findings; `git diff --check` clean.
- Two pre-existing tests corrected as a direct consequence of this movement's
  own legitimate changes (not unrelated/flaky): the D-NAV9 anti-RBAC guard's
  forbidden-term scan false-positived on the new ARIA `role="group"`
  attribute (narrowed to exclude that one literal accessibility semantic);
  the frontend top-level function-count floor moved 196 → 197 for the new
  `navigationFocusActivePanelHeading` helper. Guard 2
  (`test_no_source_claims_the_navigation_prototype_is_implementation_complete`)
  updated to expect `accessibility_closure: done` while still asserting the
  overall feature `status != "done"` until the PR merges.

## 6. New risks / notes forward

- **Validated ≠ merged.** Everything above is proven on this branch; nothing
  is claimed about `main` until the PR actually merges — see step 4 above.
- **`M5` is still the critical path.** Every collection job type is still
  `target_mode="none"`; plane-wide-then-filter must never be recorded as
  device-targeted. Untouched by M2.
- **The local enrollment permission is conditioned on the loopback binding
  itself.** `M14` does not retroactively validate it; server exposure is a
  separate open decision. Untouched by M2.
- **SQLite is local-only.** `pcp_storage_engine` stays open. Untouched by M2.
- No new root/tab/module/state vocabulary, capability, enrollment, storage,
  job or authorization behavior was introduced. No visual redesign.
