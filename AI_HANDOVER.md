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

- Date: 2026-09-03. Branch `claude/kaizen-fast-pr-ci-yx6oe3` (base `main`,
  which already carries PRs #34/#35/#36/#37).
- This session ran the `dev_kaizen_fast_pr_ci` PO-requested engineering
  Kaizen: no product/network behavior change, CI/test-infrastructure only.

## 2. What changed this session

`.github/workflows/validation.yml` split into two jobs:

- **`validate`** (`pull_request` only) — import/compileall sanity,
  repository privacy gate, project-state consistency
  (`tests/test_architecture_convergence.py`), build-history-index check, a
  small fixed PR smoke/safety-regression set
  (`tests/test_credential_redaction.py`,
  `tests/test_dev0_4_repository_privacy_gate.py`,
  `tests/test_known_safety_gaps.py`,
  `tests/test_frontend_rendering_boundary.py`), whitespace/conflict-marker
  check. **Does not run the full pytest suite.**
- **`full-regression`** (`push` to `main`, `workflow_dispatch`) — the same
  gates plus the full serial `pytest -q` suite. Substance unchanged from
  the previous single-job workflow.

Job id kept as `validate` for the PR-triggered job specifically so an
existing branch-protection required-status-check named `validate` keeps
matching — this repository session had no branch-protection API tool
available to confirm the exact required-check name independently; verify
after the PR opens that the required check still reports.

Canonical policy statement added once: `docs/AI_DEVELOPMENT_PROTOCOL.md`
new "CI validation policy" section (the two jobs, and the full-regression
trigger list a PR must self-apply: dependencies, shared test infra,
schema/storage/migrations, concurrency/shared state, security/auth
boundary, broad common domain behavior, CI infra itself, release
milestone, explicit PO/contract requirement). `AI_START_HERE.md`'s
existing "Validation ladder" full-regression bullet now points at that
section instead of restating it. `AGENTS.md`/`CLAUDE.md` untouched — no
duplication needed there.

New `tests/test_ci_workflow_fast_pr_regression.py` (7 tests): text/regex
assertions over the workflow file's shape — the PR job never contains the
unrestricted full-suite line, the full-regression job does, triggers cover
`pull_request`/`push:branches:[main]`/`workflow_dispatch`, both jobs keep
the cheap gates, and the two jobs' `if:` conditions don't both fire on the
same `pull_request` event. Deliberately text-based, not YAML-parsing — no
`pyyaml` dependency added (none existed as a repository pattern before
this).

Project-state bookkeeping: `project/roadmap.json` (`current_build` +
`now_next.now`) and `project/build_history.json` (new newest record,
`status: in_progress`) both point at `dev_kaizen_fast_pr_ci`;
`docs/history/INDEX.md` regenerated; `CURRENT_STATE.md` "Active build" /
"Exact next build" / test-baseline sections updated (still ≤200 lines).

## 3. Exact next action

Push this branch, open the PR, and let the new `validate` job run for real
CI evidence. Once `validate` is green (and, since this build itself trips
the CI-infrastructure full-regression trigger, also run `full-regression`
via `workflow_dispatch` on this branch or confirm it green on the first
`main` push after merge), advance `dev_kaizen_fast_pr_ci`'s status
`in_progress` → `automated_validated` in `project/build_history.json` /
`project/roadmap.json` / `CURRENT_STATE.md`, citing the run URL — same
pattern S2/S3 used. After that: return to `OP.0b` — `now_next.next`
(`op0b_0_close_d_v3a_d_v7b_pre_class2`) is the next real implementation
movement, unchanged by this Kaizen. Recommended reasoning: `Sonnet 5,
normal` for the status-advance commit; `Sonnet 5, extended thinking (high)`
when `OP.0b` D-V3a/D-V7b closure actually starts.

## 4. Test delta

New: `tests/test_ci_workflow_fast_pr_regression.py` (+7). No existing test
changed or removed. Full suite this session (serial, full dependency set
installed session-local: `fastapi`/`lxml`/`paramiko`/`cryptography`, not a
repository dependency change): **1215 passed / 24 skipped / 0 failed** —
superseding the prior CI baseline of 1153/28/0 only because this
container's local dependency set differs slightly from PR #36's exact CI
run; no regression, no new skip.

## 5. New risks / debt

Branch-protection required-status-check name could not be independently
verified in this session (no such tool available) — mitigated by keeping
the PR job's id as `validate`, but confirm on the actual PR that the
required check still reports and gates merge as before. No other new
risk: no dependency, schema, product, or network-device behavior changed;
`collectors/`, `utils/failover/`, PAN/CP projection, UI, transport and
schemas were not touched, per this build's own file boundary.

## 6. Continue or fresh chat

New session recommended once the PR is open and CI evidence exists, per
this Kaizen's own governing instructions (`SESSION: NEW SESSION after
merge`) — matches the repository's established S1→S2→S3 pattern.

## 7. main.py / UI effect

None. This is a CI/test-infrastructure-only change; no runtime code,
template, static asset, or payload builder was touched.
