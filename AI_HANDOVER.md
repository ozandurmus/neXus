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

- Date: 2026-09-06. `main` at merge commit `081a976a3e1cc16fb57af7f0c9aa0e0501a7625b`
  (PR #85, `M2`), onto `9a946fe` (PR #84, `M1`)'s head. NAV review lineage
  (`5a5a1f7`..`259874e`) preserved unrewritten.
- Build: `nav_1_accessibility_closure` (`M2`) — **AUTOMATED_VALIDATED, MERGED**:
  `AC-A11Y-1`..`4` closed and validated; `AC-A11Y-5` confirmed unregressed;
  merged to `main` via PR #85. Predecessors both complete: `M0` (architecture
  FROZEN, reviewed head `ba56d2b`) and `M1` (PCP.1 uuid4 test-defect repair,
  merged via PR #84).
- **`left_vertical_product_navigation` stays `in_progress`.** This is not a
  gap in M2 — its `accessibility_closure` criterion is `done`. The feature
  stays `in_progress` because its separate `availability_rule` criterion
  (the remaining three of the four capability predicates: entity
  applicability, evidence state, future authorization) is genuinely pending
  and owned by later movements (`M10`+), independent of M2's scope.

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
- No `D-NAV`/`PO-NAV` decision reopened by M2 or by this post-merge
  reconciliation. No parent-contract amendment beyond M0's own.

## 3. What M2 actually did (merged, PR #85)

- **Integration:** `--no-ff` merge of `origin/main` (`9a946fe`) into the NAV
  branch at pre-integration head `259874e`. Only the four expected
  authority/state files conflicted; reconciled holding both M0 and M1 as
  separate, non-duplicated `build_history.json` records.
- **`AC-A11Y-1`** — explicit `aria-label` on every rail button, independent
  of icon-only-mode visibility.
- **`AC-A11Y-2`** — `prefers-reduced-motion: reduce` zeroes the rail/chevron
  transitions only.
- **`AC-A11Y-3`** — `switchModule()`'s opt-in `moveFocus` moves focus to the
  activated panel's `<h1>` (`tabindex="-1"`) on real activation only.
- **`AC-A11Y-4`** — `role="group"` + `aria-labelledby` associates each
  group's children with its own toggle, surviving both rail states.
- **`AC-A11Y-5`** — confirmed unregressed (not M2-owned, merge-blocking).
- New `tests/test_m2_nav_accessibility_closure.py` — real-Chromium coverage,
  both shells.

## 4. Post-merge authority reconciliation (this closeout)

A prior session closed M2 as merged but left several files describing the
pre-merge state (`CURRENT_STATE.md` calling M2 "IN PROGRESS", `AI_START_HERE.md`
still calling the navigation contract "DRAFT — PRODUCT OWNER REVIEW REQUIRED",
this file's own "exact next action" still being "open the PR"). This session
corrected those stale statements without reopening any frozen decision,
duplicating a build record, or flipping `left_vertical_product_navigation`
to `done` (see §1 above for why that stays `in_progress`). Root cause: M2's
own closing session wrote its evidence assuming the PR would merge, then
closed before verifying the post-merge state of every operative file that
referenced the pre-merge condition.

## 5. Exact next action

1. **`M3`** — capability-state vocabulary + presentation contract
   (`nav_3_capability_state_vocabulary`). Map the ten UX capability-state
   semantics onto canonical states; settle the two `PO-NAV-7` concepts in
   their correct owning domains; own the `PO-NAV-6` colour/label contract.
   Prerequisite `M0` complete (it is). Must **not** alter the job lifecycle
   vocabulary. **New session.** `Sonnet 5, extended thinking (high)`.
2. Do **not**: reopen `AC-A11Y-1`..`5`, touch storage/enrollment/jobs/RBAC,
   or flip `left_vertical_product_navigation` to `done` (its
   `availability_rule` criterion is real, separate, owned by `M10`+).

## 6. Test delta

- Post-merge closeout: architecture convergence + navigation authority/state
  guards + project-metadata-warning check + build-history-index check +
  privacy gate + `git diff --check` — all green (state/doc-only change, no
  runtime/test/UI file touched).
- M2's own evidence (unchanged by this closeout): targeted 182 passed/1
  skipped/0 failed; full parallel suite (`py -m pytest -q -n auto --dist
  worksteal`, 4 workers) 1958 passed/23 skipped/0 failed, 29.46s.
- Post-merge CI on `main` (PR #85, workflow run `34016204567`, commit
  `081a976`): recorded in `project/build_history.json`'s
  `nav_1_accessibility_closure` record once terminal — see that record for
  the exact conclusion.

## 7. New risks / notes forward

- **`M5` is still the critical path.** Every collection job type is still
  `target_mode="none"`. Untouched by M2 or this closeout.
- **The local enrollment permission is conditioned on the loopback binding
  itself.** `M14` does not retroactively validate it. Untouched.
- **SQLite is local-only.** `pcp_storage_engine` stays open. Untouched.
- No new root/tab/module/state vocabulary, capability, enrollment, storage,
  job or authorization behavior introduced by M2 or this closeout. No visual
  redesign. No source/template/CSS/JS/test file touched by this closeout —
  state and documentation only.
