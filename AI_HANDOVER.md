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

- Date: 2026-09-03. Two independent PRs this session, both off `main` (which
  already carried #34/#35/#36/#37):
  - **PR #38** `dev_kaizen_fast_pr_ci` — **MERGED** (6778ee9), status
    `automated_validated`.
  - **PR #39** `cp_remote_collection_done_marker_diagnostics` — open,
    branch `claude/cp-remote-collection-done-marker`, merged `main` into it
    this session to resolve the bookkeeping conflict #38's merge created
    (both PRs touched `CURRENT_STATE.md`/`project/roadmap.json`/
    `project/build_history.json`/`docs/history/INDEX.md`); source files
    (`checkpoint/cp_runner.py`, its tests) merged clean, no conflict there.

## 2. What changed this session

**PR #38 (merged):** split `.github/workflows/validation.yml` into a fast
`validate` job (`pull_request` only, no full suite) and a `full-regression`
job (`push` to `main` + `workflow_dispatch`, full serial suite). Canonical
policy in `docs/AI_DEVELOPMENT_PROTOCOL.md` "CI validation policy". New
`tests/test_ci_workflow_fast_pr_regression.py` (+7). No product/network/
dependency change. Local full regression: 1215 passed/24 skipped/0 failed.
CI on the PR was green (`validate` succeeded, `full-regression` correctly
skipped on the PR event); merged via merge commit **6778ee9**.

Post-merge on `main` (908a8f1): advanced `dev_kaizen_fast_pr_ci`
`in_progress` → `automated_validated` in `build_history.json`/
`roadmap.json`/`CURRENT_STATE.md`, citing PR #38's CI + the local
regression. Also corrected `now_next.next`: it was
`op0b_0_close_d_v3a_d_v7b_pre_class2`, but the frozen `OP.0b.0` contract's
own dependency order is `S0 → S1 → (S2, S3) → S4 → (S5, S6) → S7 → S8` (S9
independent after S7). With S1/S2/S3 already `AUTOMATED_VALIDATED`, the
actual next implementable slice is **S4** — the docs-only `OP.0b.1`
command-gate package (CP `A4`–`A9` / PAN `P3`–`P5`) — not D-V3a/D-V7b
closure, which the contract's own text already scopes as CLASS-2-time
blockers independent of S1–S9/S4. `now_next.next` is now
`op0b_s4_command_gate_package`; D-V3a/D-V7b closure moved to
`now_next.upcoming`, **unchanged and still genuinely unresolved** — not
reopened, not closed. No S4 implementation work was started this session
(explicitly out of scope — "forget about next slice").

**PR #39 (open):** the user hit a real-run `RuntimeError('CP remote
collection ended without DONE marker')`, `exit_status=0`.
`checkpoint/scripts/cp_inventory.sh`'s last statement is a bare
`echo "DONE"` with no early-exit path before it, so **root cause stays
`UNKNOWN`** — not reproduced, no device access this session. Source
inspection found `checkpoint/cp_runner.py`'s `_run_remote_collection`
channel-drain loop reading only one recv()/recv_stderr() chunk per outer
pass before its exit-status break check, unlike
`checkpoint/direct_ssh_probe.py`'s already-proven `_run_session_command`
tight-drain idiom. Aligned `_run_remote_collection` with that idiom
(behavior-preserving hardening, not a confirmed fix) plus one defensive
final drain, and enriched the `RuntimeError` with safe diagnostic fields
(`exit_status`/`processed_gw`/`total_gw`/`stderr_bytes`/`last_marker`).
New `tests/test_phase0_4_1_cp_automation.py` `FakeChannel` tests (+2).
Full local regression on this branch (pre-merge): 1210 passed/24 skipped/0
failed.

**This session's merge-conflict resolution on PR #39's branch:** merged
`main` in (no rebase, no force-push — a merge commit keeps the branch's own
history valid). All 5 conflicts were in shared bookkeeping files
(`AI_HANDOVER.md`, `CURRENT_STATE.md`, `docs/history/INDEX.md`,
`project/build_history.json`, `project/roadmap.json`); resolved by keeping
both builds' `build_history.json` records (CP diagnostics as newest,
kaizen as `automated_validated` predecessor), pointing `roadmap.json`
`now_next.now`/`current_build` at the CP build while carrying over main's
S4 correction verbatim in `now_next.next`/`upcoming`, regenerating
`docs/history/INDEX.md` from the resolved `build_history.json` (never
hand-edited), and rewriting `CURRENT_STATE.md` to describe both builds
(still ≤200 lines) plus this `AI_HANDOVER.md`.

## 3. Exact next action

- **PR #39**: push this merge commit, then re-check CI (`validate`) and
  mergeable state on the branch. Once green, merge (repository convention:
  merge commit) — this build stays `in_progress` even after merge; it is
  diagnostic hardening, not a closed fix, so do not advance it to
  `automated_validated` as "the fix." Only a real recurrence (or a watched
  real run) with the new diagnostic fields justifies that.
- After PR #39 is settled: the next real implementation movement is
  `op0b_s4_command_gate_package` (`OP.0b` S4) per the roadmap correction
  above — but per this session's explicit instruction, do not start it yet;
  that is future-session work. Recommended reasoning when it does start:
  `Sonnet 5, extended thinking (high)` — security boundary (new
  network-device command candidates).

## 4. Test delta

PR #38: +7 (`tests/test_ci_workflow_fast_pr_regression.py`). PR #39: +2
(`tests/test_phase0_4_1_cp_automation.py` `FakeChannel` cases). No existing
test changed or removed by either. Both branches' full-suite runs were
green this session; no regression, no new skip.

## 5. New risks / debt

- PR #39: root cause of the original DONE-marker-loss remains `UNKNOWN`.
  If it recurs, read the new diagnostic fields off the `RuntimeError`
  before making any further change — do not guess again from source alone.
- PR #38 (closed risk): branch-protection required-status-check name for
  `validate` could not be independently confirmed via API in this session;
  the PR's own CI ran and reported normally through merge, so this did not
  block, but it was never positively confirmed either.
- No other new risk: no dependency, schema, product, or network-device
  behavior changed in either build.

## 6. Continue or fresh chat

New session recommended once PR #39 is merged and its state-advance (if
any) is settled — matches this repository's established pattern.

## 7. main.py / UI effect

Neither build changes visible `main.py`/UI behavior. PR #38 is CI/test
infrastructure only. PR #39 changes only `_run_remote_collection`'s
internal drain loop and its failure-path error text — the success path and
return value are unchanged.
