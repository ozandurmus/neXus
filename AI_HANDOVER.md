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

- Date: 2026-09-06. This is a narrow, same-session correction to
  `DEV.TEST.1` — the movement's own build-history/roadmap record is amended
  in place, not duplicated.
- Build: `parallelize_full_regression_execution` (`DEV.TEST.1`) —
  **AUTOMATED_VALIDATED, final CI topology now matches Product Owner
  direction.** Test-execution infrastructure only.
- Pure process improvement: no product behavior, capability-state
  vocabulary, navigation, registry, storage, enrollment, trust, or
  authorization change. Does **not** begin `M3`.

## 2. What changed in this correction

Prior state (already merged, PR #87/#88/#89): `full-regression` ran
automatically on push-to-`main` and via `workflow_dispatch`; `pull_request`
ran `validate` only. That contradicted the Product Owner's final policy.

**Final topology, this correction:**
- `pull_request` → `validate` (fast gate) — automatic, unchanged.
- `workflow_dispatch` → `full-regression` (parallel full suite) — **on
  demand only**.
- **No `push:` trigger at all** — removed, because with `full-regression`
  withheld from automatic triggers and `validate` restricted to
  `pull_request`, a push-to-`main` event would match no job's `if:`
  condition and produce an empty, zero-job workflow run.
- `full-regression`'s `if:` changed from `github.event_name != 'pull_request'`
  to `github.event_name == 'workflow_dispatch'` (explicit, not a double
  negative now that `push` no longer exists as a possible event).
- Local/agent-cloud parallel execution stays the default and is normally
  sufficient evidence; a GitHub-hosted `full-regression` dispatch is now
  documented as an exceptional, materially justified action, not a routine
  step.
- Workflow run `34020356372` (the push-to-main run from the now-superseded
  intermediate design) is preserved on record as one-time proof the
  parallel command works on GitHub-hosted infrastructure — explicitly
  **not** reinterpreted as authorization for automatic full-regression.

## 3. Exact next action

1. Open a narrow PR with this correction, inspect its own fast `validate`
   check, merge once green, sync `main`.
2. Verify after merge that the push-to-main event did **not** start a
   `full-regression` run (there is no `push:` trigger to fire one).
3. **Then**: `M3` (`nav_3_capability_state_vocabulary`). **New session.**
   `Sonnet 5, extended thinking (high)`. Not started by this or any prior
   `DEV.TEST.1` session.

## 4. Test delta

- `tests/test_ci_workflow_fast_pr_regression.py`: rewritten to prove the
  final topology — `pull_request` schedules `validate` and not
  `full-regression`; `workflow_dispatch` schedules `full-regression`;
  no `push:` trigger exists; the full-suite command remains
  `python -m pytest -q -n auto --dist worksteal`; the YAML parses.
- `tests/test_architecture_convergence.py`: green, confirms project-state
  cross-authority agreement after the roadmap/build-history amendment.
- No full local or GitHub Actions full-regression run performed for this
  correction (workflow/docs-only change; the movement's prior real cloud
  proof, run `34020356372`, stands as evidence parallel execution works).

## 5. New risks / notes forward

- `full-regression` has no automatic trigger of any kind now. There is no
  post-merge integration safety net beyond `validate`'s fast gates unless a
  session explicitly dispatches it for a materially justified reason —
  this is intentional Product Owner policy, not an oversight.
- The YAML-syntax-defect misdiagnosis lesson from the prior session still
  applies: run `test_workflow_yaml_parses` locally before touching this
  workflow file.
- No frozen decision reopened; `M3` remains not started and is
  `now_next.next`, unchanged by this correction.
