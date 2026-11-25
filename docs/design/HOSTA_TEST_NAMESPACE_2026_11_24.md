# HOST-A test namespace: heavy lane tests on HOST-A instead of the Mac

## Status

DRAFT, 2026-11-24 (PO request, lane `p1-delivery-hosta-test-namespace`). Built and tested offline only. Nothing has been
applied to HOST-A. The first real run is done by the PO session, which answers the questions in the last section. This
document adds no device command, no product schema and no product write. It is test equipment beside the product.

## Why

PO 2026-11-24: HOST-A has 16 cores, about 30 % in use, and 60 GB of memory, with 48 GB free. The Mac runs up to three
lanes, the ship pretest and `main-green` on 32 GB. The plan the PO approved on 2026-11-24 includes a lane harness whose
report replaces the ship pretest for the same tree, and it moves heavy Java and integration tests to a HOST-A test
namespace. This document covers that second item. The harness is a separate lane and calls this tool.

## What runs where

| Step | Where | Tool |
|---|---|---|
| Diff scope (which `:module:test` tasks) | Mac | `tools/delivery/pretest_scope.py`, unchanged |
| Ship the candidate | Mac → HOST-A over the existing SSH path | `tools/delivery/hosta_test_run.py`: `git bundle` of one temporary ref, as `tools/security/hosta_security_pregate.sh` does |
| Check out and verify `commit` has `tree` | HOST-A, private temp directory | `tools/delivery/hosta_test_run_remote.py`, sent as the ssh command text from the Mac's checkout. It is never installed on the host and never taken from the candidate |
| One Job, namespace `ui2-test` | HOST-A k3s | template `deploy/ui2-test/40-test-run-job.yaml` (from the Mac's checkout) |
| Scoped or full Java, `:integration-tests:test` against a PostgreSQL 16 sidecar, optional vitest | test pod, no network | `tools/delivery/test_runner_pod.sh` and `TestRunSummary.java`, copied into the pod from the Mac's checkout |
| Sanitized JSON summary and exit code | pod → host → Mac | one `TESTRUN_SUMMARY` line, then one `RESULT` line, then one JSON line on the Mac. The pod, the host and the Mac each validate it |
| Test-runner image, built once | HOST-A `ui2-build`, the existing kaniko path | `tools/delivery/hosta_test_runner_image.py build --commit <loaded context> --record`, `ui2/Containerfile.test-runner` |

Only the candidate's committed `ui2/`, `docs/operations/sql/` and `deploy/ui2/` enter the pod (the last because
`DatabaseRoleGrantMatrixIntegrationTest` runs the in-cluster hook SQL it reads from `deploy/ui2/10-configmap.yaml`). No host-local config, cache or
credential does. Raw Gradle and vitest output stays in the pod's emptyDirs and is gone when the pod is deleted.

### Exit codes (Mac)

| Exit | Status | Caller action |
|---|---|---|
| 0 | `PASS` | Accept the result for this tree |
| 1 | `FAIL` | A test or compile failure in the candidate. Return the failed names to the lane |
| 2 | `ERROR` / `TIMEOUT` | Runner, host or transport problem, not the lane's fault. Run the same scope on the Mac |
| 3 | `NOT_CONFIGURED` / `IMAGE_STALE` | The host cannot run this tree yet. Run on the Mac. The PO session rebuilds the image |

The output JSON carries `status`, `reason`, `run`, `commit`, `tree`, `scope`, `wall_s`, plus `queued_s` and `host_s`
when the host answered. When the pod ran, it also carries `summary.phases[]` with `name`, `status`, `reason`,
`exit_code`, `duration_s`, `tests`, `failures`, `errors`, `skipped`, and `failed`: at most 40 entries, each
`Class.method (ExceptionSimpleName)` or `file > title` in a closed character set. Failure messages never leave the pod;
the exception's simple class name does (a code identifier), so a timeout, a bind error or a missing file reads differently
from a failed assertion.

## Capacity

| Limit | Value | Where |
|---|---|---|
| Namespace CPU (requests = limits) | 8 cores, half of HOST-A | `10-resource-quota.yaml` |
| Namespace memory | 24 GiB, half of the free 48 GiB | same |
| Concurrent test pods | 3. A fourth Job's pod waits for quota | same, `pods: "3"` |
| One pod, maximum | 13 CPU burst (runner 8 + database 4), 8 GiB, 10 GiB ephemeral. Three always fit | `20-limit-range.yaml`, `10-resource-quota.yaml` (`limits.cpu` 39) |
| Runner container | request 1 CPU / 6.75 GiB, limit 8 CPU / 6.75 GiB. The java, integration and vitest phases share it (see "Phases run side by side") | `40-test-run-job.yaml` |
| PostgreSQL sidecar (integration only) | request 500m / 1 GiB, limit 4 CPU / 1 GiB. Measured 2026-11-25: 681 s at 0.5 core, 321 s at 4 cores (Mac 296 s) | same |
| Time | `--timeout` 300 to 3600 s, default 2700. The host side stops 60 s earlier and the Job's `activeDeadlineSeconds` 30 s before that | `hosta_test_run.py` |

Production always wins. PriorityClass `ui2-test` is -1000, below `ui2-low` (-100), below the default (0) and below every
production class. Its `preemptionPolicy` is `Never`. Under memory pressure the kubelet evicts a test pod first, and
the run reports `ERROR preempted`.

## Isolation

- **Network.** `default-deny` covers ingress and egress for every pod in the namespace. The only egress allowed is
  in-namespace PostgreSQL pods (TCP 5432; today the database is a loopback sidecar, so this rule only keeps a future
  Service form working) and the cluster registry in `kube-system` (TCP 5000). There is no DNS, no build proxy, no
  namespace `ui2`, no product database, no device and no internet. Image pulls are made by the node and are not affected.
- **No Secrets.** The quota allows zero Secrets, Services, PersistentVolumeClaims, NodePorts and LoadBalancers. The
  throwaway database password is generated by an init container into a memory emptyDir. PostgreSQL listens on
  127.0.0.1 only.
- **Identity.** ServiceAccount `ui2-test-runner` mounts no token, and no Role or RoleBinding names it. kubectl runs
  only on the host side, as the deploy account.
- **Pod hardening.** Pod Security `restricted` is enforced on the namespace. Every container runs non-root (999)
  with a read-only root filesystem, `allowPrivilegeEscalation: false`, all capabilities dropped and seccomp
  `RuntimeDefault`. Nothing uses hostPath or host namespaces.
- **What the candidate controls.** The candidate controls only the test code and source inside the pod. The Job
  template, runner script, summary program and host-side script all come from the Mac's checkout (the ship runs
  main's copy), as the security pre-gate does.
- **Offline dependencies.** The test-runner image carries the Gradle distribution, a read-only Gradle dependency cache
  (`GRADLE_RO_DEP_CACHE`, shared by concurrent pods without lock contention) and `node_modules`. Gradle runs with
  `--offline`. Each pod's writable `GRADLE_USER_HOME` is an emptyDir. The PO asked for a "Gradle cache volume". It is
  in the image, not in a PVC: a PVC would need a seeding pod with proxy egress in this namespace, which default-deny
  forbids.
- **Staleness.** The image records a fingerprint of every dependency-defining file: build scripts, lockfiles, the
  version catalog, verification metadata, the wrapper properties and the frontend `package*.json`. A tree with a
  different fingerprint answers `IMAGE_STALE` and runs nothing.

## Phases run side by side

`test_runner_pod.sh` first builds the frontend bundle once, then starts the java, integration and vitest phases
concurrently and waits for all of them (2026-11-25: java 183 s then integration 321 s in sequence made 551 s; the target
is under 6 minutes).

- **Bundle step.** The pod runs Gradle with `-PfrontendPrebuilt=true`, which skips `frontendCi` and `frontendBuild`; the
  image has `node_modules` but a fresh workspace has no `frontend/dist`, so `:service:processResources` packaged no
  `static/` and `FrontendBundleFreshnessTest` failed on HOST-A while passing on the Mac. Whenever a phase packages
  `:service` (a full run, the integration phase, or `:service:test` in the scoped tasks) the pod runs `npm run build`
  (`tsc --noEmit && vite build`, offline) first. A failed build answers `ERROR frontend_build` with no phase (exit 2: run
  the same scope on the Mac, which shows the compiler output). The product test is unchanged and really runs.
- **Separate project directories and caches.** The java phase runs in `ui2/`, the integration phase on a copy,
  `ui2-it/` (taken after the bundle exists), each with its own `GRADLE_USER_HOME` (`/gradle-home/java`,
  `/gradle-home/integration`). Two Gradle builds in one project directory would share `build/` outputs and `.gradle`
  locks. The shared read-only dependency cache is unaffected. When only one Gradle phase runs, no copy is made.
- **Reporting.** Every phase writes its own result line (exit code, own duration, reason, report paths); the lines are
  collected in the fixed order java, integration, vitest, so the summary keeps its shape and per-phase `duration_s`.
- **Memory.** The runner limit is 6.75 GiB (was 6): the largest allowed with the 1 GiB database and the one-shot init
  container inside the 8 GiB pod maximum. Two Gradle builds and their test workers are the new peak. Measure it (question 11).

## Failure modes

| Failure | Answer | Cleanup |
|---|---|---|
| No `~/.config/nexus/test-runner-image`, or no namespace | `NOT_CONFIGURED` (3) | No Job was created |
| Dependency files changed since the image | `IMAGE_STALE` (3) | Job deleted |
| Image pull error | `ERROR image_pull` (2) | Job deleted |
| Database sidecar or password init fails | `ERROR database` (2) | Job deleted |
| Quota full (three runs active) | Heartbeats until the deadline, then `TIMEOUT pod_not_started` (2) | Job deleted |
| Pod evicted or preempted | `ERROR preempted` (2) | Job deleted |
| Gradle cannot resolve offline | `ERROR offline_miss` (2), so the image needs a rebuild | Job deleted |
| Compile error in the candidate | `FAIL compile` (1) | Job deleted |
| Frontend bundle does not build (`tsc` or `vite`) | `ERROR frontend_build` (2), no phase. Run the scope on the Mac | Job deleted |
| Missing, duplicate or invalid summary line | `ERROR no_summary` (2) | Job deleted |
| Integration test skipped (no database carrier) | that phase `FAIL` | Job deleted |
| Session deadline | `TIMEOUT session_deadline` (2) | The session closes, so the host side deletes the Job. A bounded second ssh call deletes it by name |
| SIGHUP/INT/TERM on the Mac | `ERROR interrupted`, exit 128+signal | The same two cleanup paths. The temporary ref is always removed |
| ssh session lost | The host side's next heartbeat write fails | The host side deletes the Job and its temp directory |
| Host side killed outright | — | The Job's `activeDeadlineSeconds` and `ttlSecondsAfterFinished: 600` bound it |

## Applying it (PO session, not this lane)

1. Copy `deploy/ui2-test/00..30-*.yaml` to HOST-A and `kubectl apply` them. They create the namespace, PriorityClass,
   ServiceAccount, quota, LimitRange and NetworkPolicies. `40-test-run-job.yaml` is a template and is never applied.
2. After a deploy, run `python3 /opt/nexus/tools/delivery/hosta_test_runner_image.py build --commit <deployed sha>
   --record` once. This needs a deploy that carries this lane, so the context claim holds `ui2/Containerfile.test-runner`.
3. On the Mac: `python3 tools/delivery/hosta_test_run.py --repo <worktree> --java scoped --integration`.
4. Wiring it into the lane harness and the ship runner (`~/Codo/ops-scripts/`) is the PO session's work. When this
   tool exits 2 or 3, the caller runs the same scope on the Mac.

## Measured questions (first real run, PO session)

1. Bundle size and transfer time. The bundle carries the full history, as the pre-gate's does. If it is slow, a thin
   bundle against the host's deployed commit is the next step.
2. Does the UBI 9 `nodejs:22` module install on the pinned JDK base through the build proxy? Does the image build
   finish within its 3600 s deadline, and how large is the image?
3. Does the seed init script (`tools/delivery/test_runner_seed.init.gradle.kts`) cover every artifact? Run one full
   Java and one integration run, and count `offline_miss` answers. Does the read-only cache serve plugin resolution?
4. Does any test need DNS or another network path that default-deny blocks, for example `InetAddress.getLocalHost`
   or a test binding a non-loopback address?
5. Do the `:service` tests pass with `-PfrontendPrebuilt=true` and no built `frontend/dist`? The CI integration job
   builds the frontend first.
6. Wall time for scoped Java, full Java, integration and vitest at 2 CPU, compared with the Mac pretest (8 to 10
   minutes). Is 2 CPU per runner the right size, or does it need fewer, larger pods?
7. Does vitest run from the symlinked, read-only `node_modules`? Vite's `cacheDir` is `.cache/vite` inside the
   writable workspace.
8. Production impact while three test pods run: service p95 latency and worker job durations in `ui2`, with no
   eviction of production pods.
9. Should the registry egress rule stay? The pods do not use it today. It is in the manifest because the PO's
   specification names it.
10. Does a reference from a previous image survive a registry garbage collection (`~/Codo/ops-scripts/reg-gc.sh`)?
    If not, the recorded reference must be protected.

### Answers from the first HOST-A runs (2026-11-25), and the fixes of the `fix-ui2-test-env-failures` lane

Four tests failed on HOST-A that pass on the Mac. The pod keeps no failure messages, so each cause below is read from
the test and the runner files, not from output; where that is not enough it says so.

- **Question 4, network (answered).** No test needed a path the default-deny policy blocks. `AdfsSamlAuthenticationTest`
   (`initializationError`) booted the whole application, which opens the fixed machine port 8086
   (`MachinePortConfiguration`); `HttpSurfaceCharacterizationTest` owns 8086 and boots in another Gradle fork at the same
   time, so whichever starts second fails to start. It passed in the third run because the forks did not overlap. Not a
   network block: the sweep test already removes that connector for the same reason, and the ADFS test now does too
   (`dropMachineConnector`). The test asserts exactly what it did.
- **Question 5, frontend bundle (answered).** No: with `-PfrontendPrebuilt=true` and no `frontend/dist`,
   `FrontendBundleFreshnessTest.packagedFrontendCarriesAStringFromCurrentSource` fails. Fixed by the bundle step above;
   it is the CI integration job's "build the frontend first", done inside the pod.
- **Hook SQL file.** `DatabaseRoleGrantMatrixIntegrationTest.inClusterHookSqlCreatesNoLoginRolesAndTheGrantStatementRuns`
   reads `deploy/ui2/10-configmap.yaml` from the repository root; the pod copied only `ui2/` and `docs/operations/sql/`.
   Fixed: `deploy/ui2` is in the archive. No database privilege was involved: the sidecar's `postgres` user is the same
   superuser as the CI service container's.
- **Broker role.** `ScriptRunnerDatabaseRoleIntegrationTest.theBrokerRoleRunsItsWholeDatabasePathAndReachesNothingElse`
   creates `ui2_script_runner` itself, after the migrations. V261 grants `EXECUTE` on `ui2_job_owner_valid` (revoked from
   PUBLIC) to that role only `IF EXISTS` at migration time, so on a fresh server the heartbeat is denied. On the Mac the
   persistent PostgreSQL still had the role from an earlier run, so it passed there. The live server has the role from
   the in-cluster hook before any migration. Fixed on the runner side: the sidecar's first start creates the role
   `NOLOGIN` (init SQL written by the `db-password` init container into `/docker-entrypoint-initdb.d`). The test itself
   stays order-dependent on a bare server; a fixture that creates the role before migrating would remove that, and is
   product-test work, not runner work. This cause is deduced from the SQL, not from a failure message: confirm it in the
   next run (the failed name now carries the exception class, expected `PSQLException`).
- **`AiviewRawVersusMaskedSweepTest.noSeededRawValueSurvivesInAnyAiviewGetBody`.** Cause **not established offline**.
   It differs from the Mac in two environment facts: no frontend bundle on the classpath (this test, unlike
   `HttpSurfaceCharacterizationTest`, has no fixture-bundle fallback) and the default-deny network. The first is now
   removed by the bundle step. The second can only matter if a GET route opens a connection to a seeded address
   (`192.0.2.x`, dropped silently, so no fast refusal as on the Mac), which would show as an `HttpTimeoutException`.
   The failed name now carries the exception class: `AssertionFailedError` means the sweep's content checks failed
   (leak, coverage or refusal sets), `HttpTimeoutException` or `ConnectException` means the network.
   Re-run once with the bundle step before opening anything further.
- **Named environment exclusions.** None needed: no test in the four had to be excluded from a no-network pod. If one
   is ever excluded, it must be reported as a named exclusion in the summary, never as a pass.
- **New question 11, memory and wall time of the concurrent phases.** For the next run: peak resident memory of the runner container
  with java and integration side by side, and the wall time of the whole run (target under 6 minutes; the integration
  phase alone was 321 s at a 4-core database, so the floor is that plus the bundle build).
