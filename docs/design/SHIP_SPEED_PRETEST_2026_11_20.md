# Ship pretest speed — 2026-11-20

Status: DRAFT (engineering record, not a contract). Lane `q-14-ship-speed-pretest`. CI on main is unchanged.

## What the pretest runs
`ship*.sh` starts `jt.sh <worktree> <scope-json>` in the background beside the preview e2e. The scope comes from
`tools/delivery/pretest_scope.py`; it selects `:<module>:test` for the changed modules, their dependents and
`:architecture-tests:test`, or the full suite (`gradlew test -x :integration-tests:test`, 12 `test` tasks) when a path
is outside the closed scope. Then vitest and `tsc --noEmit` run serially after Gradle when the frontend is in scope.

Scope verified (pinned in `tools/tests/test_ship_speed_1117.py`):
- frontend-only (`ui2/frontend/src|tests|e2e`): `:architecture-tests:test` + vitest. Kept on purpose:
  `NoRoleConditionalRenderingInFrontendTest` reads the frontend sources.
- docs-only (`*.md` outside `ui2/ tools/ deploy/ scripts/`): `:architecture-tests:test` only, no vitest.
  The architecture tests read only `ui2/**` files, so a docs-only diff could skip Gradle entirely, but `jt.sh` reads an
  empty task list as "run everything"; see Recommendations.
- build files, lockfile, `ui2/frontend/package.json`, tools, deploy, scripts, unknown paths: full suite (fail closed).

## Measurements (10-core Mac, load average 26-115 from other lanes, so absolute times are inflated)
Task set `:service:test :worker:test :architecture-tests:test` (the three heavy modules, ~2,000 tests, ~980 s summed class time):

| Run | Wall |
|---|---|
| Cold, `--no-build-cache` (before) | 5m24s |
| Same task set, build cache hit (module inputs unchanged) | 1.2 s |
| `:architecture-tests:test` alone, executed | 9-12 s |
| `npm run build` (frontend) | 17 s |
| `frontendBuild` after this change, restored from cache | 1.2 s |
| `npm ci` (warm npm cache) | 2.7 s |

Notes: the build cache was already on (`org.gradle.caching=true`) and is keyed by relative paths, so the fresh
`pretest-wt` worktree hits cache entries stored by earlier runs. A changed module always re-executes; there the floor is
the slowest class in the module (worker `CheckPointPolicyCollectorTest` 112 s, service `PolicyHygieneDifferentialTest` 76 s
under load), not the fork count. The configuration cache was measured and rejected: it is compatible with this build
but saves about 1 s, and a fresh worktree never reuses an entry.

## Changes
- `ui2/build.gradle.kts`: `frontendBuild` is cacheable (`outputs.cacheIf`) and `frontend/public` is now a declared input.
  Saves ~17 s per fresh worktree whose frontend inputs were seen before.
- `ui2/build.gradle.kts`: the architecture test tasks declare the repository files they read as inputs, so a build-cache
  hit cannot replay a stale result (they read files that are not on the test classpath).

## Recommendations (outside this repository, in the ops wrapper `jt.sh`)
1. Start vitest in the background at the same time as Gradle and wait for both (the two are independent; the Java run is
   the long pole, vitest is about a minute).
2. Drop the final `npx tsc --noEmit`: `npm run build` (run by Gradle's `frontendBuild`) already starts with `tsc --noEmit`.
3. Let `pretest_scope.py` mark a docs-only diff with an explicit `"scope": "none"` and have `jt.sh` skip Gradle for it;
   today an empty task list means "full".
4. Known flake under heavy host load: worker `InteractiveShellSessionReadTest.readsThreeMegabytesWithoutChangingOutput`
   (2 s `assertTimeout`) failed once at load average 115; it passes alone.
