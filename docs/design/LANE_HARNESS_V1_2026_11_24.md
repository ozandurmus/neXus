# Design Note: Lane Harness v1

**Status:** `DRAFT -- DO NOT FREEZE` (until the Product Owner session wires it into the lane runner and the ship pretest)
**Date:** 2026-11-24
**Backlog Item:** `p1-delivery-lane-harness` (lane G560, day 81)
**Inputs:** the two independent delivery reviews of 2026-11-24 and the plan that merged them (Product Owner approved 2026-11-24: three lanes, a planner, a runner-enforced lane harness that must not lengthen delivery, heavy tests on a HOST-A test namespace, a deterministic record fold, delivery scripts into Git).
**Code:** `tools/delivery/lane_harness.py`, `tools/delivery/checks.yaml`, `tools/tests/test_lane_harness.py`.

## 1. What it is

A lane used to be "done" when the agent exited 0 and HEAD moved. Nothing verified it before the ship queue, and the Mac
pretest ran a different, smaller set than GitHub `validate` / `integration`, so some failures could only appear after a
20-minute wait. The harness is the one gate between the two: the runner calls it after the agent exits, it runs the checks
in cost order, and it writes `harness-report.json`, bound to the tree id of the branch it judged.

The harness is a gate and a bookkeeper, not an orchestrator. It never pushes, opens a PR, deploys, or reaches a host; the
only commit it makes is a derived-file re-render on the lane's own branch. Wiring (lane runner, ship pretest, repair turn)
is the Product Owner session's change to scripts outside this repository and is not part of this build.

## 2. Steps (cheapest first, one manifest)

`tools/delivery/checks.yaml` lists the steps in run order; this table is its reading. A step that fails stops the run
(`--keep-going` runs the rest; with groups see 2.2). `warn` steps never stop it.

| # | Step | What it decides | Result |
|---|---|---|---|
| 1 | `agent_outcome` | From `--agent-log` / `--agent-exit`: a permission auto-denial or an infrastructure marker (quota, rate limit, empty conversation id, timeout) on a run that exited non-zero or committed nothing | 40 / 41 |
| 2 | `preconditions` | A commit exists, the diff is not empty, the tree is clean; commits without a `Co-Authored-By` trailer are listed | 30 / 10 |
| 3 | `main_conflict` | `git merge-tree --write-tree` of fresh main and HEAD (read only). A conflict only on rendered files is not a lane conflict | 12 |
| 4 | `guarded_paths` | Tier C (the ship runner's GUARD regex) is held unless `allow_paths=1` / `--allow-paths`; records tier A / B / C for the reviewer | 20 |
| 5 | `declared_paths` | Changed paths must sit inside the meta's `paths=`; the derived files' own paths are implicit | 10 |
| 6 | `derived_files` | Successor index, build-history index, project queue, repo inventory rows for live tests, route decision table: re-rendered when stale and committed (`--no-commit-derived` fails instead) | 0 |
| 7 | `whitespace` (warn), `privacy_gate`, `secret_scan`, `forbidden_tokens` | `git diff --check`; the repository privacy gate baselined on the fresh main, run over an export of `HEAD` (section 2.1); built-in high-signal secret shapes plus `gitleaks` when installed (file:line only, never the value); the DIR-10 words under `ui2/` | 10 |
| 8 | `migration_rule` | New Flyway versions above main's highest, no edit or delete of an existing migration, no clash with a queued entry, and no in-flight ship already carrying a higher one. The report carries `migration.required_order` | 10 |
| 9 | `test_integrity` (warn), `queue_overlap` (warn) | Deleted tests, removed assertions, new skips, production change without test change; shared files with queued or in-flight branches (suggest `after=<entry>`) | warn |
| 10 | project-state and tooling tests | The same selection GitHub `validate` runs, minus the Playwright browser test; the tooling suite is the `tools` group (section 2.2) | 10 |
| 11 | `java_scoped` (group `java`) | `pretest_scope.py` (module, dependents, architecture tests; full suite when unsure), `--no-daemon` by default; a failure listing from the JUnit XML | 10 |
| 12 | `frontend_build`, `frontend_tsc`, `frontend_vitest` (group `frontend`) | Only when the scope has frontend. vitest fails closed: a non-zero exit is a failure even with no `FAIL` line, and exit 0 needs a passing `Test Files` summary | 10 |
| 13 | `integration` | When the workflow's own path rule would run it (changed path under `ui2/` or `docs/operations/sql/` outside `ui2/frontend/`, or the workflow file). Carriers below | 10 / 50 |
| 14 | `accept` | Each `accept=` line of the meta, run in the worktree | 10 |

### 2.1 What the privacy gate scans

The gate judges what the commit contains, the way GitHub `validate` sees a clean checkout. The step passes
`--scan-root <export of HEAD>` (a `git archive HEAD` extracted to a temporary directory, removed after the run) and
`--git-cwd <worktree>` (for the baseline's merge-base) to `tools/privacy/repository_privacy_check.py`; without the two
flags the script scans its own checkout as before. First real run: an ignored `ui2/frontend/dist/**` and
`ui2/service/build/**` produced four false findings that a clean checkout cannot have. Tests cover an ignored build
directory holding a secret-shaped string (no finding) and the same file committed (finding, value never echoed).

### 2.2 Groups, the CPU budget and cancellation

Steps may carry `group:` (`tools`, `java`, `frontend`). Cheap gates stay first and sequential; the consecutive grouped
steps that follow form one block whose groups run at the same time, at most `--jobs` (default 3) at once, each group in
its manifest order. `--jobs 1` is the plain manifest order. Rules:

* One group is one resource. Only the `java` group runs Gradle (`java_scoped`, `java_project_plan_reader`, `integration`,
  one after another). While the groups overlap, the frontend bundle is built once (a lock) and Gradle is told it is
  prebuilt (`-PfrontendPrebuilt=true`), because `:service:processResources` otherwise drives `npm ci` / `npm run build`
  in the directory vitest is reading.
* The tooling suite keeps the workflow's selection and `--dist worksteal`; the worker count is `{pytest_workers}`: with
  `--jobs 3` half the cores (at most 6; 5 on a 10-core Mac), alone (`--jobs 1`) cores minus two (at most 8), or
  `--pytest-workers N`. The workflow's `validate` job uses `-n 2` because its runner has two cores; its `full-regression`
  job already uses `-n auto`.
* Results are written in manifest order whatever finished first, and the exit code is the first stopping step in that
  order. Without `--keep-going` a failure kills the commands of the other groups (status `cancelled`, steps not started
  are `skipped`); with `--keep-going` the others finish and report.
* SIGTERM / SIGINT kill every running command (process groups), mark the unfinished steps `cancelled` and exit 50 with a
  report that says it was terminated; the export of `HEAD` is removed.
* Each group appends a transcript to `<report>-logs/group-<name>.log`; every step keeps its own log. The report carries
  `parallel` (`jobs`, `pytest_workers`, `cpu_count`, `concurrent`, per-group `busy_s`, `wall_s`).

### Meta fields

The lane `.meta` stays key=value: existing keys are untouched; the harness reads `paths=` (globs or directory prefixes,
comma or space separated, may repeat), `area=`, `backlog_id=`, one `accept=` per command, and `allow_paths=1`.

### Migration numbers

Flyway runs with out-of-order off, so an entry that merges after a higher number has already reached main breaks the next
start. The harness therefore (a) refuses a version at or below main's highest, (b) refuses a clash with any queued or
in-flight entry, (c) refuses a lower version than one an in-flight ship already carries, and (d) reports the ascending
order every queued entry must merge in (`migration.required_order`, status `warn`). A gap above the next free number is
a warning (`migrations.gap_policy: block` makes it a failure). The ship runner still has to merge queued entries in that
order; the harness only reports it.

### Integration carriers

* `--integration local` (default): a throwaway PostgreSQL 16 started from the Zonky binaries Gradle already caches
  (`io.zonky.test.postgres`), unpacked once under `~/.cache/nexus-lane-harness`, on a free loopback port with trust
  authentication and the workflow container's server settings. `UI2_TEST_JDBC_URL` carries it into the existing
  `Ui2PostgresFixture`; no Docker and no test change. Missing binaries is exit 50, never a pass.
* `--integration hosta`: runs `tools/delivery/hosta_integration.sh` (the in-cluster job, `ui2-build` namespace). Exit 2
  of that script (setup failure) is exit 50, not a test result.
* `--integration skip`: recorded as skipped and the report says `complete: false`; such a report never satisfies a reuse.

## 3. Exit codes

| Code | Name | Runner action (proposed) |
|---|---|---|
| 0 | `PASS` | write the ship entry; start the tier B review when `tier` is B |
| 10 | `CHECK_FAILED` | one repair turn to the author lane with `repair_hint` (at most 80 lines), then `failed/` |
| 12 | `CONFLICT_WITH_MAIN` | one repair turn: rebase on fresh main and resolve while the agent still has context |
| 20 | `HELD_FOR_PO` | straight to `held/` with the guarded paths; no pipeline time spent |
| 30 | `NO_CHANGE` | legitimate no-op only when the agent exited cleanly; archive |
| 40 | `AGENT_PERMISSION_DENIED` | `held/` with an explicit alert; never conflated with 30 |
| 41 | `AGENT_INFRA_ERROR` | retry once with backoff or switch engine; pause the runner after two in a row |
| 50 | `HARNESS_ERROR` | harness, tool or carrier problem (not in the reviews' table): retry, never blame the lane |

## 4. The report and its reuse

`harness-report.json` (`schema: lane-harness-report/1`) holds `result`, `exit_code`, `tree_id` (`HEAD^{tree}` after any
derived-file commit), `head`, `base_ref`, `base_sha`, `manifest_sha256`, `tier`, `changed_files`, `derived_commits`,
`complete`, one record per step (`status`, `detail`, `duration_s`, the last lines of a failing output, `tree_bound`,
`ci_twin`), `migration`, `queue_overlap` and, on failure, `repair_hint`. Full command output goes to
`<report>-logs/<step>.log` next to the report; keep the report outside the worktree so the clean-tree check holds.

`--reuse-report FILE` lets the ship pretest skip work: a report is reusable only when its `exit_code` is 0, its `tree_id`
equals the current tree id and its `manifest_sha256` equals the current manifest's. Then every `tree_bound` step that
passed in that report is marked `reused` and not run; steps that depend on main (conflict, privacy baseline, migration
order, derived files) are never reused and run again in a profile that lists them. `--profile pretest` selects exactly the
steps the pretest ran (`jt.sh`: project-state checks, scoped Java, vitest, tsc) plus the build and the integration suite.

## 5. Manifest and CI parity

The manifest, not prose, is the contract: a `validate` step with no `ci_twin` and no `ci_exempt` entry, a `ci_twin` that
names no workflow step, a `live_tooling_regression` selection that differs from the workflow's apart from the browser
test, an integration path rule that differs from the workflow's, and a forbidden-word list that differs from
`Ui2ArchitectureTest` all fail `tools/tests/test_lane_harness.py`. A CI-only failure is then a defect of the manifest and
is fixed there once.

## 6. Not in v1 (named so nobody assumes it)

The repair-turn loop itself, the lane cap and area locks, the planner, the two-slot test semaphore, deterministic record
fragments and the model review (tier B). The harness records the facts those need (tier, overlaps, `repair_hint`).

## 7. Open issues for the Product Owner session

1. **Tree identity.** A reuse needs the shipped tree to equal the lane's tree. The ship runner today rebases onto fresh main
   and squashes up to three lanes into one commit, so the tree usually differs and the pretest runs again. Reuse pays off
   for a ship of one on an unmoved main (`ONE_BY_ONE`), or when the pretest is changed to reuse per lane. Until wired, the
   harness adds its minutes to the lane and removes none from the ship. The saving is the rework avoided, not the time.
2. **Full Java scope on `tools/` changes.** `pretest_scope.py` returns the full suite for any path outside `ui2/<module>/`
   (a lone `tools/delivery/*.py` change included), so such a lane runs about 8-10 minutes of Java it cannot affect. That is
   today's pretest behaviour carried over unchanged; narrowing it is a separate, frozen-contract-free change in
   `pretest_scope.py`.
3. **Tooling suite cost (fixed in the first-run follow-up).** `validate` runs all of `tools/tests`. Measured before: 2534
   passed, 18 skipped, 9 minutes at `-n 6` and about 12 minutes at `-n 2` on a 10-core Mac under other lanes' load. Four
   `test_orchestrator_install_hooks` tests set the wall time (155-246 s each at `-n 5`): each made two full local clones of
   a checkout with 100k loose objects (a bare clone alone took 50 s). Their mirrors now use `git clone --shared`, so the five
   tests take about 20 s together; the suite is no longer bound by them. Skipping the suite by changed path would still break
   the "equals CI" property (thirty of the tests read `ui2/` files), so v1 does not.

### 7.1 First real run and what changed (2026-11-25)

The Product Owner session ran the harness on the log detection engine P1 lane (large Java + frontend + migration change):
**15 min 26 s** in one sequence (`live_tooling_regression` 572 s, `java_scoped` 187 s, `frontend_vitest` 106 s,
`frontend_build` 21 s, `frontend_tsc` 18 s, `privacy_gate` 16 s, the rest under 3 s). It also found two real defects (a stale
aiview leak-audit `routes.json`, a stale successor index), which stay caught. Findings fixed here: the privacy gate reported
four findings in ignored build output (section 2.1), and the serial order (section 2.2).

Measured after, on the harness's own branch (a change under `tools/`, so `pretest_scope.py` answers the full Java suite:
6519 tests, plus all 289 vitest files), 10-core Mac with other lanes running (load average 17-25), `--jobs 3`,
`--integration skip`, `--keep-going`:

| Group | Before (serial, that run) | After (concurrent) |
|---|---|---|
| tools (`live_tooling_regression`, `-n 5`) | 572 s | 145 s |
| java (`java_scoped`) | 187 s (scoped change) | 103 s (full scope, warm Gradle cache) |
| frontend (`build` 34 s, `tsc` 37 s, `vitest` 171 s) | 145 s | 241 s (slower than alone: it shares the cores) |
| privacy gate | 16 s | 7 s |
| **wall time of the run** | **926 s** | **251 s** |

The three groups overlapped completely; the frontend group (build, tsc, vitest in sequence) was the longest. The two runs
are different changes, so the comparison is an order of magnitude, not a benchmark: the tooling suite fell by the
`--shared` fix and the worker count, and the rest of the gain is the overlap (wall time is the longest group instead of
their sum). `--integration` was skipped here; the integration step stays in the `java` group after `java_scoped`, so it adds
its own minutes to that group's critical path when `ui2/` changes outside the frontend.

4. **Gradle daemons.** `--no-daemon` (both reviews) trades about 10 seconds of JVM start per run for no leaked
   `ui2/.gradle` in removed worktrees. `LANE_HARNESS_GRADLE_FLAGS` overrides it.
5. **`gitleaks`** is not installed on the lane Mac; the built-in scan is a small set of high-signal shapes, and the deploy
   gate still runs `gitleaks`. Install it to make the lane check equal to the gate.
6. **Flaky retry.** The old pretest re-ran any failed Java class once. v1 re-runs only classes in `flaky_allowlist`
   (empty) and never a name that looks security-relevant. Expect more red until the allowlist is curated from
   `flaky-tests.log`.
7. **Guarded files.** This build itself touches `tools/delivery/` and `docs/repo_inventory.csv`; the ship runner holds it
   for a person by its own rule.

## 8. Wiring checklist (for the Product Owner session)

1. Lane runner, after the agent exits: call `lane_harness.py --worktree <wt> --meta <meta> --queue-dir <ship-queue> --fetch
   --lane-name <name> --agent-log <log> --agent-exit <rc> --report <logs>/harness-<name>.json`; branch on the exit code per
   section 3; write the ship entry only on 0 and put `harness_report=` in it.
2. Ship pretest (`jt.sh` / `shipq3.sh`): `lane_harness.py --profile pretest --worktree <rebased wt> --reuse-report <report>`
   in place of the hand-written test list; its exit code is the verdict.
3. Migration order: read `migration.required_order` when batching.
4. Move the delivery scripts under `tools/delivery/` with tests (separate lane).
