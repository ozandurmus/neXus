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
  work is on branch `claude/parallelize-full-regression-hw0xon`, not yet
  merged.
- Build: `parallelize_full_regression_execution` (`DEV.TEST.1`) —
  test-execution infrastructure only. Local validation and every
  workflow/doc/test edit are complete; GitHub Actions `workflow_dispatch`
  proof on this exact branch/head is still owed before merge (see `project/
  build_history.json` head record for live status).
- Pure process improvement: no product behavior, capability-state vocabulary,
  navigation, registry, storage, enrollment, trust, or authorization change.
  Does **not** begin `M3`.

## 2. What is frozen (unchanged by this build)

Every frozen product/architecture contract is untouched: `docs/design/
NAVIGATION_INFORMATION_ARCHITECTURE.md`, `docs/design/
LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md`, `docs/history/phase/
OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`, and every other frozen
decision register entry. This build touches only `.github/workflows/
validation.yml`, one test file asserting the workflow's own shape, and
documentation/project-state files.

## 3. What this build actually did

- **Root cause audit**: the `full-regression` job's own long-standing
  "serial on purpose" comment traced to one concrete defect —
  `scripts/render_uitest.py::render()` left three `utils.html_export`
  payload builders bare-rebound (not `monkeypatch.setattr`) after use, so an
  in-process caller sharing the same worker could see leaked fixture state.
  That is already fixed (`try`/`finally` restore, `_injected_builders`) and
  directly regression-tested regardless of worker/ordering
  (`tests/test_frontend_rendering_boundary.py::
  test_render_uitest_restores_the_builders_it_injects`) — confirmed by
  reading both files. No other shared-mutable-resource pattern was found.
- **Topology selected**: Candidate A, one `pytest-xdist` process
  (`python -m pytest -q -n auto --dist worksteal`) — the exact command
  already proven locally and already the default in
  `scripts/pytest_one_shot.ps1`/`requirements-dev.txt`. One master process
  yields one aggregate exit code across every worker.
- **Changed**: `.github/workflows/validation.yml` (`full-regression` job's
  "Full test suite" step, now parallel, plus a worker-topology visibility
  step); `tests/test_ci_workflow_fast_pr_regression.py` (`FULL_SUITE_LINE`
  + assertions updated to match); `docs/AI_DEVELOPMENT_PROTOCOL.md` ("Test
  execution economy", "CI validation policy"), `AI_START_HERE.md`
  (validation ladder), `CLAUDE.md` ("Test economy") — all now recommend the
  parallel command as the default, with `-n0`/`-Serial` documented as an
  explicit diagnostic override only.
- **Preserved**: the PR-vs-push/dispatch trigger split, every other CI gate
  (privacy, project-state consistency, build-history index, whitespace
  check), and every targeted/subsystem test invocation that was already
  serial (unaffected — this movement only touches the *full-suite*
  invocation).

## 4. Exact next action

1. Push this branch, open a PR into `main`, and trigger the repository's
   `workflow_dispatch` on this exact branch/head SHA to get real GitHub
   Actions timing/topology/accounting evidence for the new parallel
   `full-regression` job (a PR alone does not run it — the job's `if` guard
   is `github.event_name != 'pull_request'`).
2. Record that run's ID, conclusion, elapsed time, and test totals in
   `project/build_history.json`'s `parallelize_full_regression_execution`
   record (currently a placeholder pending this evidence), compare against
   the `11m56s` baseline (run `34016204567`), then merge only once green.
3. After merge, sync local `main` and flip `now_next.now`'s status to
   `automated_validated` alongside the roadmap `DEV.TEST` track's status.
4. **Then**: `M3` (`nav_3_capability_state_vocabulary`) — capability-state
   vocabulary + presentation contract. **New session.** `Sonnet 5, extended
   thinking (high)`. Do not begin it in the same session as this movement's
   close (out of scope for `DEV.TEST.1`).

## 5. Test delta

- Local: `python3 -m pytest -q -n auto --dist worksteal` (4 CPUs) — 1957
  passed, 24 skipped, 0 failed, 35.01s wall-clock (this sandbox; same 1981
  collected total as the prior 1958/23 baseline — the one skip/pass split
  difference is sandbox Chromium availability, not a coverage change).
  `tests/test_ci_workflow_fast_pr_regression.py` re-verified green (7
  passed) against the updated workflow shape.
- GitHub Actions parallel `full-regression` run: not yet executed — see
  "Exact next action" above.
- Repository privacy gate, project-state consistency, build-history-index
  `--check`, `git diff --check`: run and green as part of this session's own
  validation ladder before commit (see `project/build_history.json` head
  record for the exact evidence once finalized).

## 6. New risks / notes forward

- The `parallelize_full_regression_execution` build-history record's
  `evidence`/`risks_forward` fields are placeholders until the GitHub
  Actions `workflow_dispatch` run is terminal — do not treat this build as
  `automated_validated` until that record is filled in and the PR merged.
- No frozen decision reopened; `M3` remains not started and stays
  `now_next.next` unchanged throughout this movement.
