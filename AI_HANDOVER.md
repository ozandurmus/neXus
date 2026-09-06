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

- Date: 2026-09-06. `main` still at merge commit
  `081a976a3e1cc16fb57af7f0c9aa0e0501a7625b` (PR #85, `M2`) — this session's
  work is on branch `claude/parallelize-full-regression-hw0xon`, PR #87, not
  yet merged.
- Build: `parallelize_full_regression_execution` (`DEV.TEST.1`) —
  test-execution infrastructure only. The `full-regression` job's full
  suite is parallelized (`-n auto --dist worksteal`); the PR/push trigger
  split is **unchanged** (approved final policy, see below).
- Pure process improvement: no product behavior, capability-state
  vocabulary, navigation, registry, storage, enrollment, trust, or
  authorization change. Does **not** begin `M3`.

## 2. What is frozen (unchanged by this build)

Every frozen product/architecture contract is untouched. This build touches
only `.github/workflows/validation.yml`, one test file asserting the
workflow's own shape, `scripts/render_uitest.py` history (already fixed,
not re-touched this build), and documentation/project-state files.

## 3. What this build actually did

- **Root cause audit**: the `full-regression` job's own long-standing
  "serial on purpose" comment traced to one concrete defect —
  `scripts/render_uitest.py::render()` left three `utils.html_export`
  payload builders bare-rebound after use, already fixed (`try`/`finally`
  restore, `_injected_builders`) and directly regression-tested regardless
  of worker/ordering (`tests/test_frontend_rendering_boundary.py::
  test_render_uitest_restores_the_builders_it_injects`).
- **Topology selected and kept**: one `pytest-xdist` process
  (`python -m pytest -q -n auto --dist worksteal`) for the `full-regression`
  job's full suite step, plus a worker-topology visibility step. Unchanged
  from first implementation.
- **PR-trigger expansion attempted, then reverted (same session):** to get
  automatic pre-merge cloud proof without a manual Product Owner action, a
  follow-up commit removed `full-regression`'s
  `if: github.event_name != 'pull_request'` guard so it would also run on
  every pull_request. Investigation (workflow_dispatch 403s on non-default
  branches, zero-job check suites on both the branch's push event and the
  PR's pull_request event, contrasted against historically successful PRs
  from earlier sessions) established this environment's automation
  identity cannot get GitHub Actions to schedule pull_request/
  workflow_dispatch jobs on a non-default branch at all — a tooling/
  identity limitation, not a defect in the parallel design. Per Product
  Owner direction, that trigger expansion was **reverted**
  (`tests/test_ci_workflow_fast_pr_regression.py` reverted to match); the
  approved final policy is unchanged from before this build started:
  - local full suite: parallel by default;
  - `pull_request`: existing fast `validate` job only;
  - push to `main`: parallel `full-regression`;
  - `workflow_dispatch`: parallel `full-regression`;
  - no serial full-suite default anywhere.
  The limitation itself is recorded in `.github/workflows/validation.yml`'s
  header comment and `docs/AI_DEVELOPMENT_PROTOCOL.md`'s "CI validation
  policy" section, not silently dropped.
- **Pre-merge evidence decision (Product Owner, this session):** two clean
  complete local parallel runs are accepted as this movement's pre-merge
  full-suite evidence in place of a PR-triggered cloud run — 1981 collected,
  1957 passed, 24 skipped, 0 failed, at 32.27s and 35.01s (4 workers,
  `-n auto --dist worksteal`). No further local full-suite run is to be
  performed for this movement.
- **Changed vs preserved**: changed = `.github/workflows/validation.yml`
  (`full-regression`'s full-suite step parallelized; PR-trigger guard
  restored after the reverted experiment; header comments document the
  environment limitation), `tests/test_ci_workflow_fast_pr_regression.py`
  (`FULL_SUITE_LINE` + assertions matching the final restored shape),
  `docs/AI_DEVELOPMENT_PROTOCOL.md`/`AI_START_HERE.md`/`CLAUDE.md` (parallel
  as the default local/CI command). Preserved = the `validate`/
  `full-regression` PR-vs-push/dispatch trigger split, every other CI gate,
  `scripts/pytest_one_shot.ps1` (untouched), every targeted/subsystem test
  invocation that was already serial.

## 4. Exact next action

1. Push the reverted-policy commit to PR #87; verify the diff contains only
   the intended parallelization + isolation fix + tests + docs + state
   changes (no leftover PR-trigger-expansion artifacts).
2. Verify PR #87 is still mergeable, then merge it (no manual Product Owner
   GitHub action required — this session merges directly).
3. Sync local `main`.
4. Observe the automatic post-merge push-to-main `full-regression` run
   (this trigger path has historically worked reliably, including for this
   same automation identity — unlike pull_request/workflow_dispatch on a
   non-default branch). Verify from the actual runtime log (not just the
   YAML text): the exact command, an xdist `N/N workers` / equivalent
   worker-count line, collected-test count, duration, and terminal
   conclusion.
5. If green: amend the existing `parallelize_full_regression_execution`
   build-history record in place (remove placeholders, status →
   `automated_validated`), reconcile roadmap/CURRENT_STATE/AI_HANDOVER/
   INDEX, and merge that reconciliation via a narrow follow-up PR if the
   post-merge evidence cannot truthfully exist inside PR #87 before its own
   merge. Do not duplicate the build-history record.
6. If it fails: do not mark the movement complete; classify the failure;
   fix via a narrow follow-up PR if in scope, or prepare a clean revert PR
   if parallel execution itself proves unsafe. Never fall back silently to
   serial.
7. **Then**: `M3` (`nav_3_capability_state_vocabulary`). **New session.**
   `Sonnet 5, extended thinking (high)`. Not started in this session.

## 5. Test delta

- Local (accepted as this movement's pre-merge evidence, see item 3 above):
  two clean full parallel runs, 1957 passed/24 skipped/0 failed each
  (32.27s and 35.01s), 1981 collected both times.
  `tests/test_ci_workflow_fast_pr_regression.py` reverted and re-verified
  green (7 passed) against the final restored workflow shape.
- GitHub Actions: no pull_request/workflow_dispatch run available on this
  branch (environment limitation, see item 3 above). The post-merge
  push-to-main `full-regression` run is the real cloud proof — pending as
  of this handover.

## 6. New risks / notes forward

- The `parallelize_full_regression_execution` build-history record's
  `evidence`/`risks_forward` fields stay placeholders until the post-merge
  push-to-main run is terminal and inspected from its actual log.
- The environment limitation (no pull_request/workflow_dispatch job
  scheduling on non-default branches for this automation identity) is now
  documented in the workflow file and `docs/AI_DEVELOPMENT_PROTOCOL.md` —
  a future session attempting the same PR-trigger expansion should check
  whether this still holds before repeating the experiment.
- No frozen decision reopened; `M3` remains not started and stays
  `now_next.next` unchanged throughout this movement.
