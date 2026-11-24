# Delivery ops scripts in Git (2026-11-24)

## Status

DRAFT -- engineering record of work the Product Owner approved on 2026-11-24 (delivery orchestration review, lane 3). It
introduces no device command, no network action and no new authority; it only moves existing local delivery scripts into the
repository, fixes the bugs both independent reviews found, and records how to switch the runners over. Review inputs (local
research notes, not in the repository): the Neon and Fable delivery-orchestration reviews and the comparison with the plan.

## 1. What this is

The lane runner, the ship runner and their helpers lived outside Git in a local scripts directory, with live bugs. Their
versioned copies are now in `tools/delivery/ops/`. The originals are untouched: the Product Owner session decides when to point
launchd at the repository copies. `tools/delivery/` is a guarded path, so this change merges through the Product Owner session.

| Script | Role |
|---|---|
| `ops_lib.sh` | shared paths (environment), helpers, the flaky allowlist check, the fail-closed vitest verdict, `lane_brief` |
| `lane-runner.sh` | keeps N lanes busy; one distinct outcome per finished lane |
| `ship-runner.sh` | batches finished lanes, ships them through `shipq3.sh`, handles failures |
| `shipq3.sh` | one ship: rebase, pretest in the background, preview, merge, deploy, rollback |
| `jt.sh`, `jt_java_results.py` | the Mac pretest (Java, project-state tools, vitest, tsc) |
| `main-green.sh` | fast checks on `main` after every change; sets `MAIN_RED` |
| `b-review.sh` | tier B pre-merge review |
| `watch-check.sh`, `status.sh`, `daily-metrics.py` | health check, compact status, daily numbers |
| `counter.sh`, `lanename.sh`, `reserve-migration.sh` | names, counters, Flyway numbers |
| `flaky_allowlist.txt` | the only tests that may be re-run |

Not moved (still in the state directory, referenced from there): `claude_task.sh`, `qwen_task.sh`, `argon_task_neon.py`,
`rollback-now.sh`, the `refill-*` and `lane-common-rules.md` templates. They are engine wrappers and prompts, not delivery logic.

## 2. Paths are configuration

Every path comes from `ops_lib.sh`; nothing is a literal home directory.

| Variable | Default | Meaning |
|---|---|---|
| `NEXUS_OPS_DIR` | directory of the scripts | where sibling scripts are found |
| `NEXUS_OPS_STATE` | `~/Codo/ops-scripts` | queues, logs, counters, batches, templates, helper wrappers |
| `NEXUS_OPS_CODO` | `~/Codo` | lane worktrees `nexus-q-<name>` and `nexus-shiprun` |
| `NEXUS_OPS_REPO` | `$NEXUS_OPS_CODO/nexus-ship` | the main checkout the runners fetch into |
| `NEXUS_FLAKY_ALLOWLIST`, `NEXUS_FLAKY_LOG` | allowlist beside the scripts, `flaky-tests.log` in the state dir | re-run policy and its log |
| `NEXUS_GRADLE`, `NEXUS_JT_TMP`, `NEXUS_LAUNCHCTL` | wrapper, `/tmp`, `launchctl` | overrides used by the tests |

To switch over, point the launchd jobs (`lane-runner.sh`, `ship-runner.sh`, `main-green.sh`, `watch-check.sh`) at the
repository copies and keep `NEXUS_OPS_STATE` at the existing state directory; the queue layout is unchanged. Run
`status.sh` first: it now lists `failed/`, `held/`, lane `hold/` and `FAILED_WORKTREE` with ages.

## 3. Bugs fixed (both reviews)

### In `tools/delivery/standalone_orchestrate.py`

1. **Merge errors.** After ANY error from the merge call (not only a 5xx), and after an exception from the call itself, the
   ship asks GitHub for the PR state. `MERGED` means the merge landed and the ship carries on. Only when GitHub cannot be
   read does it look whether the shipped change is on `origin/main` (ancestor, or every patch of `main..head` already
   upstream, which is what a rebase merge produces). A refusal on an OPEN pull request is still a failure and is not
   retried. Two merges landed on 2026-11-24 while the call errored; the runner re-queued them and the merged commit was not
   deployed until the next ship.
2. **Checks before the deploy lock.** `_wait_for_checks` runs before the deploy lock is taken. A stuck check (up to 45
   minutes) now holds only this ship's preview lock, not the lock every other ship's merge and deploy queue behind. Cost: the
   next ship's preview waits for this ship's checks, which is the order the unpipelined path always had.
3. **Missing or pending checks are re-run once.** A check that never appears within the grace period gets one close and
   reopen of the pull request (GitHub's `reopened` trigger). A check still pending at the timeout gets one cancel and re-run
   of the unfinished workflow runs of the PR head commit. Each happens at most once per ship, with a fresh wait window
   afterwards; a failed check is never re-run and a second timeout fails the ship as before.

### In the runner scripts

| Finding | Fix |
|---|---|
| `jt.sh`: vitest crashed before listing failed files, the empty list took the "flaky re-run passed" branch | `vitest_gate`: a non-zero exit or a failed summary with no failed file listed prints `vitest-FAIL` and fails |
| `jt.sh` diffed against `origin/main`, which can be stale (`ProjectPlanReaderTest` skipped) | takes the ref the ship fetched (`refs/shipq/<log>`, third argument); no or unknown ref runs the check |
| Gradle daemons from parallel worktrees | every call is `./gradlew --no-daemon ...` (`jt.sh`, the Java re-run); `ship-runner.sh` has no Gradle call left |
| Java: a gradle run that wrote no results and failed counted as "0 failures" | counted as one failure |
| `lane-runner.sh`: exit 3 meant "no change", a permission denial and an infrastructure error alike, ending silently in `done/` | `lane_outcome` gives one word per lane: `OK`, `NO_CHANGE`, `PERMISSION_DENIED` (held, `ALERT`), `INFRA_ERROR` (re-queued once, then held, `ALERT`), `QUOTA`, `FAILED` (`ALERT`); the outcome is written beside the brief in `done/` |
| `FAILED_WORKTREE` dropped a brief with no alert | the brief stays queued for one retry; the second failure becomes `done/<name>.brief.FAILED_WORKTREE` with an `ALERT` line, listed by `status.sh` and `watch-check.sh` |
| `b-review.sh`: every BLOCK went to `held/` and the PO session | the first BLOCK returns to the author: the entry moves to `ship-queue/returned/` and a fix lane is queued on the same branch (base = the lane branch, pinned to Claude, review text in the brief), its ship entry carries `review_returned=1`; only a second BLOCK, or a diff too large to review, goes to `held/` |
| state-sync lanes (13 per day) | the trigger in `ship-runner.sh` is replaced by `record_fold_hook`, a no-op; the deterministic record fold comes in a later lane. Existing `*-state-sync*` items still sort first |
| flaky re-run of any failed test | only tests listed in `flaky_allowlist.txt`, once; a test whose name looks security-relevant (security, authz, credential, redaction, sanitizer, privacy, secret, crypto, tls, masking, admission, lease, session, token, ...) is never re-run, even if listed; the same rule in `main-green.sh` |
| `status.sh` hid `failed/` | shows ship `failed/`, `held/`, lane `hold/` and `FAILED_WORKTREE` with ages |
| `reserve-migration.sh` exited when no open lane branch carried a migration | that case is not an error |

The allowlist ships with the Java classes and vitest files that `flaky-tests.log` shows failing and then passing on
2026-11-22..24. Each entry needs a debt lane that removes the cause; delete the entry then.

## 4. Behaviour that did not change

Batching, guarded paths, port and fix lanes, the pipelined two-lock ship, the preview, the security gate, rollback and the
window holds are as before; so is the lane cap logic (`lane-runner.sh <max>`; the lane-count change to 3 stays a launchd
argument). The harness, planner and Java integration on a HOST-A test namespace are separate lanes.

## 5. Tests

`tools/tests/test_delivery_ops_lane_runner.py`, `test_delivery_ops_ship_runner.py` and `test_delivery_ops_jt.py` source the
scripts and run their functions against temp directories with fake `gh`, `npx` and Gradle on `PATH`; no Bats, no network, no
real queue, no launchd. The orchestrator changes are covered in `test_standalone_orchestrate.py`, `test_ship_speed.py` and
`test_ship_pipelined_locks.py`. The scripts run their loop only when executed, not when sourced.

## 6. Open points

- Switching launchd to the repository copies (Product Owner session).
- `shipq3.sh`, `watch-check.sh` and `main-green.sh` loops have only syntax, path and function-level tests; their real
  behaviour is validated by running them, which is a real-environment step.
- The record fold, the planner, the lane harness and the HOST-A test namespace are later lanes.
