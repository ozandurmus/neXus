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
- **Changed**: `.github/workflows/validation.yml` — `full-regression`
  job's "Full test suite" step is now parallel (plus a worker-topology
  visibility step), AND its `if: github.event_name != 'pull_request'` guard
  is removed (see item 1 below — permanent trigger correction, not a
  workaround); `tests/test_ci_workflow_fast_pr_regression.py`
  (`FULL_SUITE_LINE` + assertions updated to match both changes, plus a new
  test asserting `validate`/`full-regression` intentionally run together on
  the same PR event); `docs/AI_DEVELOPMENT_PROTOCOL.md` ("Test execution
  economy", "CI validation policy"), `AI_START_HERE.md` (validation ladder),
  `CLAUDE.md` ("Test economy") — all now recommend the parallel command as
  the default, with `-n0`/`-Serial` documented as an explicit diagnostic
  override only.
- **Preserved**: `validate` job unchanged (still the cheap PR fast-fail
  gate); every other CI gate (privacy, project-state consistency,
  build-history index, whitespace check); no `continue-on-error` added
  anywhere new; every targeted/subsystem test invocation that was already
  serial (unaffected).

## 4. Exact next action

1. **Trigger design corrected (2026-09-06, same session, PO-directed):**
   attempting `workflow_dispatch` on this exact branch/head hit a hard
   tooling blocker — the session's GitHub token/App installation returns
   `403 Resource not accessible by integration` for `workflow_dispatch` on
   any non-default branch (confirmed via both the GitHub MCP tool and a
   direct REST call; works fine for `main`, fails for every other branch
   tried). Rather than require a Product Owner to operate GitHub Actions
   manually, the durable fix was applied instead: `full-regression` no
   longer carries `if: github.event_name != 'pull_request'` — it now runs
   unconditionally on every pull_request, push-to-main, and
   workflow_dispatch event alike, alongside `validate`. This removes the
   blocker permanently (a `pull_request` event always fires regardless of
   token branch-write scope) and is a real design correction, not a
   workaround: the original PR/push split existed only because the serial
   suite was too slow to afford on every PR; now parallelized and fast
   enough, withholding it from PRs was an obsolete policy.
2. Push this workflow-trigger correction to PR #87. The resulting
   `pull_request` (synchronize) event will run the new `full-regression` job
   automatically on the PR's own exact head SHA — no manual dispatch needed.
3. Verify that run: head SHA, workflow definition, parallel command, worker
   count, collected-test accounting, duration, and terminal result.
4. If green, record the evidence in
   `project/build_history.json`'s `parallelize_full_regression_execution`
   record (currently a placeholder), compare against the `11m56s` baseline
   (run `34016204567`), push the final state update, confirm the updated
   head stays green, then merge PR #87 and sync local `main`.
5. Flip `now_next.now`'s status to `automated_validated` alongside the
   roadmap `DEV.TEST` track's status once merged.
6. **Then**: `M3` (`nav_3_capability_state_vocabulary`) — capability-state
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
