# UI2 Java Suite — First Local Run Baseline

Status: DRAFT

Movement: NXS-LOCAL-0123 (`ui2_java_suite_first_local_run`). Read-and-report
only. This document records what the B1-1a section 7 acceptance checks
actually did the first time they were run on this machine. It applies no
fix and changes no contract status.

Environment: JDK 21 (Temurin 21.0.12.1), `JAVA_HOME` unset, no Docker/
container runtime reachable, no `UI2_TEST_JDBC_URL` set.

## module_listing

Command: `./ui2/gradlew -p ui2 projects --quiet`
Exit code: 0

Listed exactly eleven modules: `architecture-tests`, `capability-registry`,
`cli`, `integration-tests`, `job-engine`, `ldap-adapter`, `persistence`,
`platform-core`, `scheduler`, `service`, `worker`. No frontend subproject
appeared in the Gradle project tree (a root-level `frontendCi` task exists
and runs `npm` separately — it is not a Gradle subproject and does not
appear in `projects` output).

## architecture_test

Command: `./ui2/gradlew -p ui2 architectureTest`
Exit code: 0 — BUILD SUCCESSFUL, 22 actionable tasks executed.

`Ui2ArchitectureTest`: 10 tests, 0 skipped, 0 failures, 0 errors. All ten
direction methods ran and passed:

- `dir1_core_has_no_project_dependencies()` — passed
- `dir2_web_cannot_reach_worker_or_transport_implementations()` — passed
- `dir3_job_engine_is_independent_of_entry_points_and_adapters()` — passed
- `dir4_scheduler_cannot_reach_device_transport()` — passed
- `dir5_ldap_adapter_is_identity_only()` — passed
- `dir6_registry_is_independent_of_entry_points_and_adapters()` — passed
- `dir7_domain_has_no_persistence_framework_dependency()` — passed
- `dir8_frontend_is_build_time_only()` — passed
- `dir9_production_modules_do_not_depend_on_test_modules()` — passed
- `dir10_no_line1_or_python_runtime_dependency()` — passed

`NoRoleConditionalRenderingInFrontendTest`: 1 test, 0 skipped, 0 failures,
0 errors (`frontendSourceContainsNoRoleTokenLiteralOrGroupReferenceString()`
— passed).

## unit_test

Command: `./ui2/gradlew -p ui2 unitTest`
Exit code: 0 — BUILD SUCCESSFUL, no container and no database used.

Real counts, summed across all `unitTest` JUnit XML reports emitted by this
run: **126 tests, 0 skipped, 0 failures, 0 errors.** `scheduler` and `cli`
reported `NO-SOURCE` for `unitTest` (no unit-test sources in those modules).

## check

Command: `./ui2/gradlew -p ui2 check`
Exit code: 1 — BUILD FAILED.

`check` runs every module's `test`/`unitTest`/`architectureTest` (all
passing, matching the `architecture_test`/`unit_test` results above) plus
`frontendCi` (ran; emitted an `EBADENGINE` warning for Node 24 vs. the
declared `22.x` engine requirement, and a `whatwg-encoding` deprecation
warning — neither failed the task) and `:integration-tests:test`, which
failed: **34 tests completed, 29 failed.** First failure:

```
AuditContextMissingFailsClosedTest > initializationError FAILED
    java.lang.IllegalStateException at AuditContextMissingFailsClosedTest.java:35
```

Underlying cause (from the JUnit XML): `Ui2PostgresFixture` could not reach
a real PostgreSQL 16 — `UI2_TEST_JDBC_URL` is unset and no container
runtime could start a `postgres:16` container (Testcontainers: "Previous
attempts to find a Docker environment failed"). This is the same
integration-database gap reported under `integrationTest` below, surfacing
through `check`'s `test` task rather than a skip.

No `--offline`, `--refresh-dependencies`, `--write-locks`, `--update-locks`,
or any verification/locking-weakening flag was passed. Dependency
verification and locking ran as configured; nothing in the failing output
indicates a verification or locking failure — the failure is confined to
`:integration-tests:test` and its database dependency.

## integration_test (dedicated task, run in addition to `check`)

Command: `./ui2/gradlew -p ui2 integrationTest`
Exit code: 1 — BUILD FAILED.

`integrationTest` cannot run in this environment: it requires a real
PostgreSQL 16, and neither `UI2_TEST_JDBC_URL` nor a working container
runtime is available here (no Docker environment could be found). Invoked
directly, it did what the contract requires — it **failed** (exit code 1,
34 tests completed, 29 failed) rather than skipping or reporting green.
Same first failure and cause as under `check` above
(`AuditContextMissingFailsClosedTest`, `Ui2PostgresFixture` container
start failure). Standing up a database to make this pass is out of this
movement's scope (deployment movement).

## privacy_gate

Command: `python3 scripts/repository_privacy_check.py`
Exit code: 0

```
Files scanned:        1543
Files skipped:        14204
Findings:             0
Gate:                 PASS
```

## architecture_convergence

Command: `python3 -m pytest tests/test_architecture_convergence.py -q`
Exit code: 0 — 23 passed.

## Summary

| Check | Exit | Result |
|---|---|---|
| module_listing | 0 | 11/11 modules listed, no frontend subproject |
| architecture_test | 0 | 11/11 tests passed, all ten `dir*` methods pass |
| unit_test | 0 | 126/126 tests passed, 0 skipped |
| check | 1 | passes except `:integration-tests:test` (database gap) |
| integrationTest | 1 | fails closed on missing PostgreSQL 16, as required |
| privacy_gate | 0 | 0 findings |
| architecture_convergence | 0 | 23/23 passed |

Locus for a later movement: the `integration-tests` module's fail-closed
behavior is correct per contract; the only actionable gap is environmental
— no PostgreSQL 16 (via `UI2_TEST_JDBC_URL` or a container runtime) is
available on this machine. That provisioning belongs to the deployment
movement, not this one.
