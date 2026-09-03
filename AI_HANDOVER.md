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

- Date: 2026-09-03. Branch `claude/cp-stderr-classification`, base `main`
  (at `79449bb`, which already carries PRs #34-#39). `dev_kaizen_fast_pr_ci`
  and the first pass of `cp_remote_collection_done_marker_diagnostics` are
  merged; this branch is a second pass on the same still-open build.

## 2. What changed this session

The user reported the **same** `RuntimeError('CP remote collection ended
without DONE marker')` recurring on a real run, but now carrying PR #39's
new diagnostic fields: `exit_status=0, processed_gw=0, total_gw=None,
stderr_bytes=658, last_marker=None`. This is genuinely informative: it
disproves the channel-drain-race theory (PR #39's fix didn't stop the
recurrence) and shows the failure happens **before**
`checkpoint/scripts/cp_inventory.sh` line ~94 (`TOTAL_GW` is never echoed),
alongside 658 bytes of real stderr the code was discarding.

Rather than guess again from source alone (risky on a live security
appliance with no way to test the guess), added a bounded, privacy-safe
stderr classifier: `checkpoint/cp_runner.py` gains `_STDERR_CLASSIFIERS` (a
closed list of generic shell/environment error regexes —
`no_such_file_or_directory`, `command_not_found`, `permission_denied`,
`not_a_tty`, `unbound_variable`, `syntax_error`,
`connection_reset_or_broken_pipe`) and `_classify_stderr_sample()`.
`_run_remote_collection` now captures up to 8192 chars of stderr in memory,
classifies it, and discards the raw text immediately — the classification
tokens (never the raw text) are included in both `RuntimeError` messages
(missing-DONE and nonzero-exit-status) and the success-path stderr warning.
+3 tests in `tests/test_phase0_4_1_cp_automation.py`, including one that
asserts device names/IPs never appear in the exception message. Updated the
existing `cp_remote_collection_done_marker_diagnostics` build_history
record (same build, second pass) and `CURRENT_STATE.md`/`roadmap.json` to
match; regenerated `docs/history/INDEX.md`.

## 3. Exact next action

Push this branch, open a PR, wait for `validate` CI, merge if green
(same posture as PR #38/#39). Then **the user needs to re-run `main.py`
once more** — the next failure's `RuntimeError` will report
`stderr_classification=[...]` instead of just a byte count. That
classification is the next real evidence:
- `no_such_file_or_directory` → prime suspect is `cp_inventory.sh` line 3's
  `. /opt/CPshared/5.0/tmp/.CPprofile.sh` sourcing (unconfirmed without MDS
  filesystem access).
- `unclassified` → the closed category list missed it; do not add
  categories speculatively, extend only from the next real observed text.
- Anything else → follow the specific category.

Do not attempt a third guess-based source change before that evidence
comes back — this build's own risk note says so explicitly.

## 4. Test delta

+3 (`tests/test_phase0_4_1_cp_automation.py`: two classifier unit tests,
one leak-check on `_run_remote_collection`'s exception message). No
existing test changed or removed. Full suite this session: 1220 passed /
24 skipped / 0 failed (up from 1217 pre-session, +3 new).

## 5. New risks / debt

None new beyond what pass 1 already carried (root cause still `UNKNOWN`).
The classifier is a closed, hand-picked list — a real cause outside it
reports `unclassified` rather than vanishing, which is the fail-safe
behavior, but doesn't itself name the cause.

## 6. Continue or fresh chat

Continue this chat once the user has the next real run's
`stderr_classification` value — that's a quick, evidence-driven follow-up,
not a new investigation. A fresh session is fine too since state is fully
recorded here and in `CURRENT_STATE.md`/`build_history.json`.

## 7. main.py / UI effect

None on the success path. On the specific failure path this build targets,
the error message a real run prints now includes `stderr_classification`.
