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

- Date: 2026-09-03. Two independent branches this session, both off `main`
  (which already carries PRs #34/#35/#36/#37):
  - `claude/kaizen-fast-pr-ci-yx6oe3` → PR #38 (CI/test-infrastructure only)
  - `claude/cp-remote-collection-done-marker` (CP live-collection diagnostic
    hardening) — PR opened this session, see below for its number/status

## 2. What changed this session

**Build 1 — `dev_kaizen_fast_pr_ci` (PR #38):** split
`.github/workflows/validation.yml` into a fast `validate` job
(`pull_request` only, no full suite) and a `full-regression` job (`push` to
`main` + `workflow_dispatch`, full serial suite). Canonical policy in
`docs/AI_DEVELOPMENT_PROTOCOL.md` "CI validation policy". New
`tests/test_ci_workflow_fast_pr_regression.py` (+7). No product/network/
dependency change. Full local regression: 1215 passed/24 skipped/0 failed.

**Build 2 — `cp_remote_collection_done_marker_diagnostics`:** the user hit
`RuntimeError('CP remote collection ended without DONE marker')`
(`exit_status=0`) on a real run. `checkpoint/scripts/cp_inventory.sh`'s last
statement is a bare `echo "DONE"` with no early-exit path before it, so
**root cause stays `UNKNOWN`** — not reproduced, no device access in this
session. Source inspection found `checkpoint/cp_runner.py`'s
`_run_remote_collection` channel-drain loop reading only one
recv()/recv_stderr() chunk per outer pass before its exit-status break
check, unlike `checkpoint/direct_ssh_probe.py`'s already-proven
`_run_session_command` tight-drain idiom. Aligned `_run_remote_collection`
with that idiom (behavior-preserving hardening, not a confirmed fix) plus
one defensive final drain, and enriched the `RuntimeError` with safe
diagnostic fields (`exit_status`/`processed_gw`/`total_gw`/`stderr_bytes`/
`last_marker`). New `tests/test_phase0_4_1_cp_automation.py` `FakeChannel`
tests (+2) prove the multi-chunk drain and that no device identity leaks
into the error message. Full local regression: 1210 passed/24 skipped/0
failed (this branch's baseline, one file fewer than build 1's since the two
branches haven't merged into each other).

Both builds updated `project/roadmap.json`/`project/build_history.json`/
`CURRENT_STATE.md`/`docs/history/INDEX.md` for their own build id.

## 3. Exact next action

- **PR #38** (`dev_kaizen_fast_pr_ci`): CI came back green
  (`validate` succeeded, `full-regression` correctly skipped on the PR
  event), no review comments, `mergeable_state=clean`. User authorized
  merging both branches to `main` if green — merge this one, then advance
  its build_history/roadmap status to `automated_validated` citing the PR's
  CI run, push that state-advance directly to `main` (established S2/S3/
  post-merge pattern), and sync local `main`.
- **CP branch PR**: push + open PR, wait for its `validate` CI, then apply
  the same merge-if-green + state-advance treatment. This build stays
  `in_progress` even after merge — it is diagnostic hardening, not a closed
  fix; do not mark it `automated_validated` as "the fix" until a real
  recurrence (or a watched real run) is observed with the new fields.
- After both: return to `OP.0b` — `now_next.next`
  (`op0b_0_close_d_v3a_d_v7b_pre_class2`).

## 4. Test delta

Build 1: +7 (`tests/test_ci_workflow_fast_pr_regression.py`). Build 2: +2
(`tests/test_phase0_4_1_cp_automation.py` `FakeChannel` cases). No existing
test changed or removed in either. Both full-suite runs green this session
(counts above); no regression, no new skip.

## 5. New risks / debt

- PR #38: branch-protection required-status-check name for `validate`
  could not be independently confirmed in this session (no such API tool
  available) — kept the job id unchanged as the safest bet; watch the PR
  page to confirm the required check still gates merge.
- CP branch: root cause of the original DONE-marker-loss remains
  `UNKNOWN`. If it recurs, read the new diagnostic fields off the
  `RuntimeError` before making any further change — do not guess again
  from source alone.

## 6. Continue or fresh chat

New session recommended once both PRs are merged and their post-merge
state-advance commits are pushed, per the kaizen build's own governing
instructions and this repo's established pattern.

## 7. main.py / UI effect

Build 1: none (CI/test-infrastructure only). Build 2: none visible in
normal operation — `_run_remote_collection`'s drain behavior and error
message changed, but the success path and its return value are unchanged;
only a currently-failing run's error text differs.
