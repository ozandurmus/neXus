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

- Date: 2026-09-06. `main` is at merge commit
  `06f73e7df7f80cf415fd146356a677b71e6522b7` (PR #87). A narrow post-merge
  fix (branch `claude/fix-workflow-yaml-syntax`) is in flight for a genuine
  YAML syntax defect PR #87 shipped (see item 3) — must land and be proven
  green via a real push-to-main run before this movement can close.
- Build: `parallelize_full_regression_execution` (`DEV.TEST.1`) —
  test-execution infrastructure only. The `full-regression` job's full
  suite is parallelized (`-n auto --dist worksteal`); the PR/push trigger
  split is unchanged (approved final policy, see below).
- Pure process improvement: no product behavior, capability-state
  vocabulary, navigation, registry, storage, enrollment, trust, or
  authorization change. Does **not** begin `M3`.

## 2. What is frozen (unchanged by this build)

Every frozen product/architecture contract is untouched. This build touches
only `.github/workflows/validation.yml`, one test file asserting the
workflow's own shape, `requirements-dev.txt` (new `pyyaml` dev dependency),
and documentation/project-state files.

## 3. What this build actually did

- **Root cause audit (the original serial gate)**: traced to one concrete
  defect — `scripts/render_uitest.py::render()` left three
  `utils.html_export` payload builders bare-rebound after use, already
  fixed (`try`/`finally` restore) and directly regression-tested regardless
  of worker/ordering
  (`tests/test_frontend_rendering_boundary.py::
  test_render_uitest_restores_the_builders_it_injects`).
- **Topology selected and kept**: one `pytest-xdist` process
  (`python -m pytest -q -n auto --dist worksteal`) for the
  `full-regression` job's full suite step, plus a worker-topology
  visibility step.
- **PR-trigger expansion attempted, then reverted (Product Owner
  direction)**: a follow-up commit removed `full-regression`'s
  `if: github.event_name != 'pull_request'` guard to get automatic
  pre-merge cloud proof. It was reverted back to the original approved
  policy (`pull_request` = fast `validate` only; push/`workflow_dispatch` =
  parallel `full-regression`) — a standalone decision, independent of the
  incident below.
- **Post-merge YAML-syntax incident — corrects an in-session
  misdiagnosis.** While chasing cloud proof, every check suite for every
  trigger (push, pull_request, workflow_dispatch) on every branch —
  including, after merge, the merge commit on `main` itself — completed
  with **zero jobs**. This was first misdiagnosed as an
  environment/automation-identity limitation preventing GitHub Actions
  from scheduling pull_request/workflow_dispatch jobs on a non-default
  branch. **That diagnosis was wrong.** Parsing the workflow file locally
  after merge found the real cause: the "Report worker topology" step's
  `run:` value was an unquoted plain YAML scalar containing a bare `: `
  inside an f-string label (`"...target): {os.cpu_count()}"`), which YAML
  parses as an illegal nested mapping. A file that fails to parse cannot
  schedule a job under any trigger, on any branch, regardless of who
  pushed it — exactly the symptom observed, and exactly why it looked
  identity/branch-related until someone ran the file through a real YAML
  parser. **Fixed** by rewording the f-string. Every place in this
  session's own docs/state that stated the wrong "automation-identity
  limitation" theory as fact has been corrected to describe the real cause
  (`.github/workflows/validation.yml` header, `docs/
  AI_DEVELOPMENT_PROTOCOL.md`, `CURRENT_STATE.md`, `AI_HANDOVER.md`,
  `project/roadmap.json`, `project/build_history.json`). The PR-trigger
  policy decision itself was not reopened by this correction — it stands
  on its own merits, decided before the misdiagnosis was made.
- **New regression guard**: `tests/test_ci_workflow_fast_pr_regression.py::
  test_workflow_yaml_parses` (new, `pyyaml>=6` added to
  `requirements-dev.txt`) actually parses `.github/workflows/validation.yml`
  on every test run, specifically so a future YAML syntax defect fails
  locally before push rather than shipping silently through review and a
  merge, as this one did.
- **Pre-merge evidence decision (Product Owner)**: two clean complete local
  parallel runs are accepted as this movement's pre-merge full-suite
  evidence in place of a PR-triggered cloud run — 1981 collected, 1957
  passed, 24 skipped, 0 failed, at 32.27s and 35.01s (4 workers,
  `-n auto --dist worksteal`).

## 4. Exact next action

1. Push the YAML fix (+ corrected docs/state + new parse-guard test) via a
   narrow follow-up PR into `main`; verify the diff is exactly that fix and
   nothing else.
2. Merge it once its own fast `validate` gate is green.
3. Sync local `main`, then observe the next real push-to-main
   `full-regression` run from its actual runtime log (not just the YAML
   text): exact command, worker-count line, collected-test count, duration,
   terminal conclusion. Compare against the `11m56s` serial baseline.
4. If green: amend the existing `parallelize_full_regression_execution`
   build-history record in place (remove placeholders, status →
   `automated_validated`), reconcile roadmap/CURRENT_STATE/AI_HANDOVER/
   INDEX via a narrow state-only follow-up if the evidence cannot exist
   inside the fix PR before its own merge. Do not duplicate the record.
5. If it still fails: do not mark the movement complete; classify the new
   failure on its own terms (do not assume it's the same root cause again).
6. **Then**: `M3` (`nav_3_capability_state_vocabulary`). **New session.**
   `Sonnet 5, extended thinking (high)`. Not started in this session.

## 5. Test delta

- Local: two clean full parallel runs (pre-fix), 1957 passed/24 skipped/0
  failed each (32.27s, 35.01s), 1981 collected both times — accepted as
  pre-merge evidence, unaffected by the YAML fix (no product/test-content
  change). `tests/test_ci_workflow_fast_pr_regression.py` now 8 tests (new
  `test_workflow_yaml_parses`), all green against the fixed workflow file.
- GitHub Actions: still no successful run recorded for this movement — the
  YAML defect meant every attempted trigger produced a zero-job failure.
  The next real push-to-main run (after the fix PR merges) is the first
  chance at genuine cloud proof.

## 6. New risks / notes forward

- The `parallelize_full_regression_execution` build-history record's
  `evidence`/`risks_forward` fields stay placeholders until a real
  push-to-main `full-regression` run (post-YAML-fix) is terminal and
  inspected from its actual log.
- Any future session touching `.github/workflows/validation.yml` should run
  `test_workflow_yaml_parses` (or `python -c "import yaml; yaml.safe_load(open(path))"`)
  locally before push — this exact defect class produces no useful error
  anywhere in the GitHub UI, only a silent zero-job check suite.
- No frozen decision reopened; `M3` remains not started and stays
  `now_next.next` unchanged throughout this movement.
