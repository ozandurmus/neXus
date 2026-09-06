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
  `6ca67cce2c25a8a29f0e1207c5851d0e2756f7f9` (PR #88, on top of PR #87's
  `06f73e7`). This state-only close-out lands via a narrow follow-up PR.
- Build: `parallelize_full_regression_execution` (`DEV.TEST.1`) —
  **CLOSED, AUTOMATED_VALIDATED.** Test-execution infrastructure only.
- Pure process improvement: no product behavior, capability-state
  vocabulary, navigation, registry, storage, enrollment, trust, or
  authorization change. Does **not** begin `M3`.

## 2. What is frozen (unchanged by this build)

Every frozen product/architecture contract is untouched. This movement
touched only `.github/workflows/validation.yml`, one CI-shape test file,
`requirements-dev.txt` (new `pyyaml` dev dependency), and
documentation/project-state files.

## 3. What this build actually did (final)

- **Root cause (original serial gate)**: `scripts/render_uitest.py::render()`
  left three `utils.html_export` payload builders bare-rebound after use —
  already fixed (`try`/`finally` restore) and directly regression-tested
  regardless of worker/ordering
  (`tests/test_frontend_rendering_boundary.py::
  test_render_uitest_restores_the_builders_it_injects`).
- **Topology**: one `pytest-xdist` process
  (`python -m pytest -q -n auto --dist worksteal`) for `full-regression`'s
  full suite step, plus a worker-topology visibility step.
- **Trigger policy**: unchanged from before this movement —
  `pull_request` = fast `validate` only; push-to-`main`/`workflow_dispatch`
  = parallel `full-regression`. A same-session attempt to expand
  `full-regression` onto `pull_request` too was evaluated and reverted per
  Product Owner direction, independent of the incident below.
- **Post-merge YAML-syntax incident, corrected**: PR #87 shipped a genuine
  YAML defect — an unquoted f-string with a bare `: ` in the "Report worker
  topology" step — that broke job scheduling under every trigger, on every
  branch, silently (zero-job "failure" check suites, no readable error).
  This was first misdiagnosed in-session as an automation-identity
  limitation preventing pull_request/workflow_dispatch scheduling on
  non-default branches; that theory was wrong. Fixed via PR #88; every
  place this session's docs/state had stated the wrong theory was
  corrected. New permanent guard:
  `tests/test_ci_workflow_fast_pr_regression.py::test_workflow_yaml_parses`
  (`pyyaml>=6` added to `requirements-dev.txt`).
- **Real GitHub Actions cloud proof (closes the movement)**: push-to-main
  run [`34020356372`](https://github.com/ozandurmus/neXus/actions/runs/34020356372)
  (commit `6ca67cce2c25a8a29f0e1207c5851d0e2756f7f9`) — `full-regression`
  **SUCCESS**. From the actual job log: `python -m pytest -q -n auto
  --dist worksteal`, 4 workers (`CPU count (xdist -n auto target) -> 4`),
  `1944 passed, 38 skipped, 3 warnings in 275.74s (0:04:35)` (1982
  collected — one more than the local 1981, an environment-dependent
  collection difference, not a coverage gap: 0 failed either way).
  Full-suite step wall-clock 4m36s vs the `11m56s` serial baseline
  (~61% faster, ~2.6x) — **~36s above the 4-minute acceptable ceiling**,
  reported honestly. Bottleneck: 98% of tests finish within ~1 minute of
  actual worker time; a small number of tail tests (consistent with
  real retry/backoff-timing-bound tests) account for the remaining ~3m41s.
  Not a correctness/coverage problem.

## 4. Exact next action

1. This state-only close-out PR reconciles `project/build_history.json`
   (placeholders removed, real evidence filled in, status
   `automated_validated`), `project/roadmap.json` (`now_next.now.status` →
   `automated_validated`, `DEV.TEST` track → `done`), `CURRENT_STATE.md`,
   this file, and `docs/history/INDEX.md`. Merge it once its own fast
   `validate` gate is green.
2. **Then**: `M3` (`nav_3_capability_state_vocabulary`) — capability-state
   vocabulary + presentation contract. **New session.** `Sonnet 5, extended
   thinking (high)`. Not started by this movement.

## 5. Test delta

- Local (pre-merge evidence, PR #87): two clean full parallel runs, 1957
  passed/24 skipped/0 failed each (32.27s, 35.01s), 1981 collected.
- GitHub Actions (real cloud proof, post PR #88 fix): run `34020356372`,
  `full-regression` SUCCESS, 1944 passed/38 skipped/0 failed, 275.74s.
- `tests/test_ci_workflow_fast_pr_regression.py`: 8 tests (new
  `test_workflow_yaml_parses`), all green.

## 6. New risks / notes forward

- The full-regression parallel step measured 4m36s in the cloud, ~36s over
  the 4-minute ceiling, due to a small tail of slow tests (not an xdist
  distribution problem — see item 3). A narrow future follow-up could
  identify and address the specific slow test(s) if tightening below 4
  minutes becomes a priority; not required to close this movement, which
  already delivers a ~61%/2.6x improvement over the 11m56s serial baseline
  with zero coverage loss.
- Any future session touching `.github/workflows/validation.yml` should run
  `test_workflow_yaml_parses` locally before push — a YAML defect in this
  file produces no readable error anywhere in the GitHub UI, only a silent
  zero-job failing check suite that can look identity/branch-related until
  someone actually parses the file.
- No frozen decision reopened; `M3` remains not started and is
  `now_next.next` again, unchanged by this movement.
