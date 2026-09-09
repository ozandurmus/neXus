# UI 2.0 — B1-1 skeleton, CI and Docker contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-10.**

This document specifies the mechanical implementation contract for
`ui2_b1_01_skeleton_ci_docker`. It creates no `ui2/` files, authorizes no
dependency installation, changes no Line-1 source or CI gate, and authorizes
no device contact.

## 1. Scope and authority

The implementing movement creates a Gradle multi-module project under `ui2/`,
an isolated UI 2.0 CI workflow, one production image built from one Java
artifact, and a Testcontainers PostgreSQL harness. It does not implement a
schema, capability, transport, login flow, job executor, scheduler, or user
interface.

Authority, highest first:

1. `AGENTS.md` and `PRIVACY_AND_DATA_HANDLING.md`.
2. `UI2_0_BASELINE_CONTRACT.md` §1 and §4, especially P-1: Java is the only
   UI 2.0 runtime and Line-1 is reference only.
3. `UI2_0_DEVELOPMENT_WORKFLOW.md` §3.2 and §5 B1-1.
4. Frozen `C1`, `C2`, `C3`, and `C4` contracts.
5. `UI2_0_ARCHITECTURE_DESIGN.md` as amended by `C5`, especially §8.3–§8.4.

The implementation must preserve the existing Line-1 tree and
`.github/workflows/validation.yml` byte-for-byte. No Python executable,
module, package, container, sidecar, subprocess, or queue consumer may enter
the UI 2.0 runtime or image.

## 2. Module map and dependency direction

All Java packages begin `com.securityexpert.nexus.ui2`. Modules are Gradle
subprojects below `ui2/`; `frontend` is a build-time TypeScript workspace and
is not a runtime process.

| Module | Responsibility and package home | Allowed project dependencies | Contract home |
| --- | --- | --- | --- |
| `platform-core` | Opaque identifiers, result/error types, clocks and shared ports in `.platform` | none | baseline P-1/P-4; architecture §8.4 |
| `persistence` | jOOQ access, transaction boundaries and Flyway resources in `.persistence`; migration path `service/src/main/resources/db/migration` is packaged from here into `service` | `platform-core` | C1 §§2–6 |
| `capability-registry` | C4 registry, gate resolution and closed action/step types in `.capability` | `platform-core` | C4 §§2–3 |
| `job-engine` | C2 job state machine, worker ports, scheduler ports and admission interfaces in `.jobs` | `platform-core`, `persistence`, `capability-registry` | C2 §§2–7 |
| `ldap-adapter` | UnboundID-backed operator-bind and re-validation adapter implementations in `.identity.ldap`; no web controller | `platform-core` | C3 §§2, 4.4 |
| `service` | Spring MVC API, session/RBAC interceptors, read models, static UI assets and composition root in `.service` | `platform-core`, `persistence`, `capability-registry`, `job-engine`, `ldap-adapter` | C1 service scope; C3 §§3–6; C4 read/query use |
| `worker` | Job claimant/execution process and composition root in `.worker`; transport interfaces only at B1-1 | `platform-core`, `persistence`, `capability-registry`, `job-engine` | C1 worker scope; C2; C4 §6 |
| `scheduler` | Due-schedule evaluation and job-request creation in `.scheduler`; never device contact | `platform-core`, `persistence`, `job-engine` | C1 scheduler scope; C2 §7 |
| `cli` | Thin typed command entry point using the same application ports in `.cli` | `platform-core`, `job-engine` | architecture §8.3 |
| `architecture-tests` | ArchUnit and artifact inspections; production code forbidden | every Java module as test inputs only | baseline P-1; architecture §8.4 |
| `integration-tests` | Testcontainers PostgreSQL, Flyway lifecycle and cross-component contract tests; production code forbidden | production modules as test fixtures only | C1 §§2, 3.5; C2 §9 |
| `frontend` | React/TypeScript source compiled to static assets consumed by `service`; build-time only | no Java project dependency and no Line-1 dependency | architecture §8.3; Material Design 3 baseline |

### 2.1 Direction rules

| Rule | Required direction | Forbidden edge |
| --- | --- | --- |
| `DIR-1` | adapters and entry points depend inward on ports/core | `platform-core` → any other module |
| `DIR-2` | web composition may call application ports | `service` → worker implementation or any transport implementation |
| `DIR-3` | worker composes execution behind `job-engine` ports | `job-engine` → `worker`, `service`, `scheduler`, or adapter implementation |
| `DIR-4` | scheduler creates jobs through `job-engine` | `scheduler` → transport or worker implementation |
| `DIR-5` | LDAP is an identity adapter only | `ldap-adapter` → `service`, `worker`, or `scheduler` |
| `DIR-6` | registry describes capabilities and gates | `capability-registry` → `service`, `worker`, `scheduler`, LDAP, or transport clients |
| `DIR-7` | persistence implements storage ports | domain/core modules → jOOQ, Flyway, JDBC, or Spring persistence packages |
| `DIR-8` | frontend emits static assets at build time | frontend → Python/Line-1, device transport, database, or runtime Node process |
| `DIR-9` | tests inspect production modules | any production module → `architecture-tests` or `integration-tests` |
| `DIR-10` | UI 2.0 remains one Java runtime | any UI 2.0 manifest, dependency, source or image layer → Line-1 Python/runtime paths |

Every component named by C1 has a distinct process or adapter home: service,
worker, scheduler, LDAP re-validation adapter, and migration apply step.

## 3. Build and reproducibility

### 3.1 Policies

- **Java:** Java 21 LTS toolchain, as recommended by architecture §8.3.
  Gradle must provision/verify the toolchain rather than compile against an
  ambient JDK. A change of major Java version requires a successor contract.
- **Gradle:** wrapper-only execution. The wrapper distribution URL includes
  its SHA-256 checksum and uses the newest supported Gradle 8.x patch proven
  compatible with Java 21 when B1-1 is implemented. Major upgrades require
  review; patch upgrades are dependency-maintenance changes.
- **Dependencies:** Kotlin DSL, one version catalog, locked dependency
  versions, Gradle dependency locking, and checked-in dependency-verification
  metadata. Dynamic versions, version ranges, changing repositories, and
  unverified artifacts are forbidden.
- **Testcontainers:** the PostgreSQL module is pinned through the version
  catalog to one exact stable release; Renovate/Dependabot-style upgrades are
  reviewed changes, never floating resolution.
- **Node:** an exact LTS release and package-manager version are pinned;
  `npm ci` uses a committed lockfile. Node is build/test tooling only.

### 3.2 Exact commands

Run from the repository root:

```text
./ui2/gradlew -p ui2 clean assemble
./ui2/gradlew -p ui2 unitTest
./ui2/gradlew -p ui2 integrationTest
./ui2/gradlew -p ui2 architectureTest
./ui2/gradlew -p ui2 check
docker build --file ui2/Dockerfile --tag nexus-ui2:local ui2
```

`check` depends on `unitTest`, `integrationTest`, `architectureTest`,
dependency verification, dependency-lock consistency, and frontend
`npm ci && npm test && npm run build`. No command invokes Python.

## 4. Test harness

`unitTest` contains no container, network, filesystem-runtime, or wall-clock
dependency. `integrationTest` owns container-backed tests and starts one
ephemeral PostgreSQL container per test class (reuse disabled in CI), waits
for PostgreSQL readiness, and then runs Flyway with the `ui2_migrate` DSN
before creating an application connection with the `ui2_app` DSN. Tests
never create schema through jOOQ, Hibernate, application startup, or ad-hoc
DDL.

The first B1-1 harness carries an empty/baseline migration location so the
lifecycle is executable before B1-2. B1-2 adds `V1__initial_schema.sql` at
`service/src/main/resources/db/migration/`; the harness discovers it through
the same classpath used by the image. A second Flyway invocation must be a
no-op and leave the same schema-history checksum.

The B1-2 test named `AuditContextIntegrationTest` must:

1. start PostgreSQL and apply Flyway;
2. issue a raw mutation as `ui2_app` without `SET LOCAL
   app.actor_fingerprint` and `SET LOCAL app.action_id`;
3. assert the transaction fails with `audit_context_missing` and no mutation
   commits;
4. repeat with both settings in the transaction and assert exactly one audit
   row commits;
5. roll back a context-bearing mutation and assert neither mutation nor audit
   row survives.

Container logs and failure output must not print DSNs or secret values.

## 5. P-1 architecture test specification

One test class, `Ui2ArchitectureTest` in `architecture-tests`, mirrors §2.1
one-to-one:

| Test method | Tool | Rule proved |
| --- | --- | --- |
| `dir1_core_has_no_project_dependencies` | Gradle dependency graph assertion | `DIR-1` |
| `dir2_web_cannot_reach_worker_or_transport_implementations` | ArchUnit | `DIR-2` |
| `dir3_job_engine_is_independent_of_entry_points_and_adapters` | ArchUnit | `DIR-3` |
| `dir4_scheduler_cannot_reach_device_transport` | ArchUnit | `DIR-4` |
| `dir5_ldap_adapter_is_identity_only` | ArchUnit | `DIR-5` |
| `dir6_registry_is_independent_of_entry_points_and_adapters` | ArchUnit | `DIR-6` |
| `dir7_domain_has_no_persistence_framework_dependency` | ArchUnit + dependency graph | `DIR-7` |
| `dir8_frontend_is_build_time_only` | manifest and image inspection | `DIR-8` |
| `dir9_production_modules_do_not_depend_on_test_modules` | Gradle dependency graph assertion | `DIR-9` |
| `dir10_no_line1_or_python_runtime_dependency` | repository dependency graph and image filesystem inspection | `DIR-10` |

`dir10` fails on a project dependency outside `ui2/`, Python executables or
libraries, references to the P-1-forbidden paths, a Python base-image layer,
or a container command/entrypoint invoking Python. Reading committed
sanitized fixtures at test time is allowed; importing or executing their
Line-1 producer is not.

## 6. CI contract

B1-1 adds `.github/workflows/ui2-validation.yml`; it does **not** alter
`.github/workflows/validation.yml`. The new workflow runs on pull requests
whose changed paths include `ui2/**`, its own workflow file, or this contract,
and on explicit `workflow_dispatch`. Permissions are `contents: read`;
concurrency is isolated under an `ui2-validation-*` group.

The job order is:

1. checkout with the history needed by repository privacy comparison;
2. set up pinned Java 21 and pinned Node LTS caches;
3. restore the dependency cache; on a cold cache, run
   `./ui2/gradlew -p ui2 dependencies` once against only the repositories,
   locks and verification metadata committed by the implementing movement,
   then run `./ui2/gradlew -p ui2 --offline dependencies`; both paths fail if
   verification metadata or locks disagree, and the offline pass proves the
   resolved cache is complete before tests begin;
4. run `./ui2/gradlew -p ui2 check`;
5. run `docker build --file ui2/Dockerfile --tag nexus-ui2:ci ui2`;
6. inspect the image as required by `dir8` and `dir10`;
7. run the repository privacy gate against `origin/main`;
8. run `git diff --check`.

The UI 2.0 workflow has its own required-check name and does not replace,
skip, weaken, rename, serialize with, or supply success for Line-1's
`validation.yml` `validate` job. A PR touching both product lines must pass
both workflows independently. CI uploads only unit/integration test reports,
architecture-test reports, and an SBOM; no database volume, container log
containing sensitive data, runtime data, or image tar is uploaded.

## 7. Docker image contract

One multi-stage Dockerfile builds frontend assets and the Java distribution,
then copies only the assembled application, static assets, runtime
configuration templates without values, CA-certificate roots, and required
licence/SBOM metadata into a digest-pinned distroless Java 21 runtime image.
The image contains the same jar for the `service`, `worker`, and `scheduler`
roles; a typed role argument selects the entry point.

The runtime image:

- runs as a fixed non-root numeric UID/GID;
- has a read-only root filesystem and only explicit writable temp/runtime
  mounts;
- exposes only the service health endpoint; worker and scheduler health are
  process/readiness checks, not HTTP device paths;
- has a healthcheck that proves process/database readiness without device
  contact;
- contains no shell, package manager, compiler, Gradle cache, Node runtime,
  source tree, test fixture, credential, secret, Line-1 code, Python runtime,
  database, runtime data, raw evidence, or backup artefact.

Secrets are injected at runtime exactly as C1 §6 requires. Each role resolves
its own `<COMPONENT>_<PURPOSE>_FILE`; the migration invocation alone receives
the `ui2_migrate` DSN, long-running roles receive only their scoped
`ui2_app`/adapter credentials, and missing/unreadable/empty files fail closed
without falling back. No build argument, image environment default, layer,
label, healthcheck, or log contains a secret value.

## 8. Implementing-movement acceptance checks

All checks are runnable from the repository root:

1. `test ! -e ui2/.python-version && ! grep -R -E 'main\.py|python|console/|_runner\.py|_collector\.py' ui2 --exclude-dir=build --exclude='*.md'`
2. `./ui2/gradlew -p ui2 projects` lists exactly the Java modules in §2.
3. `./ui2/gradlew -p ui2 architectureTest --tests '*Ui2ArchitectureTest'`
   passes all ten one-to-one `DIR-*` methods.
4. `./ui2/gradlew -p ui2 unitTest` passes without starting a container.
5. `./ui2/gradlew -p ui2 integrationTest` starts PostgreSQL, runs Flyway
   before application access, and proves a second migrate is a no-op.
6. `./ui2/gradlew -p ui2 integrationTest --tests
   '*AuditContextIntegrationTest'` proves C1 §3.5's fail-closed behavior once
   B1-2 supplies V1.
7. `./ui2/gradlew -p ui2 check` succeeds with dependency verification and
   locking enabled and no dynamic dependency.
8. `npm --prefix ui2/frontend ci && npm --prefix ui2/frontend test &&
   npm --prefix ui2/frontend run build` succeeds using only the lockfile.
9. `docker build --file ui2/Dockerfile --tag nexus-ui2:acceptance ui2`
   succeeds, and image history contains no secret build argument.
10. Image inspection confirms non-root UID/GID, Java 21, no shell/Python/Node,
    no Line-1 path, and only the assembled UI 2.0 application/SBOM/licenses.
11. Starting each `service`, `worker`, and `scheduler` role without its
    required secret file fails closed and reports only component/purpose.
12. Starting with synthetic mounted secret files reaches readiness without
    persisting those values into the container writable layer or logs.
13. `.github/workflows/validation.yml` is byte-identical to its pre-movement
    version and both workflow job names are distinct.
14. Using the already-validated project interpreter for the current workspace
    profile (`<validated-project-python>` below), run
    `<validated-project-python> main.py --repository-privacy-check
    --privacy-baseline-ref origin/main`; it reports zero new findings. The
    placeholder is resolved from the workspace/tool-specific runtime rule and
    is never replaced in this contract by a user-specific absolute path.
15. `git diff --check origin/main...HEAD` is clean.

## 9. Technology decisions and PO veto points

| Decision | Choice for B1-1 | Rationale / authority | PO veto consequence |
| --- | --- | --- | --- |
| Runtime | Java 21 LTS | architecture §8.3 recommendation; records/sealed types and virtual threads | choose Java 17 before skeleton implementation and restate affected architecture tests |
| Build | Gradle 8.x wrapper, Kotlin DSL | architecture §8.3; verification metadata and version catalog | Maven would require a successor contract and equivalent locks/verification |
| Web | Spring Boot 3.x, Spring MVC | architecture §8.3; matches C3 interceptor/security shape | alternate framework must prove equivalent filter, JDBC and LDAP boundaries |
| DB | PostgreSQL + jOOQ + Flyway Community | baseline STACK; C1 sole migration authority; architecture §8.3 | no ORM auto-DDL; Oracle remains deferred |
| LDAP | UnboundID LDAP SDK | frozen C3 §2 | no substitute without C3 successor |
| Frontend | React + TypeScript + MUI, theme constrained to the frozen Material Design 3 tokens | architecture leaves React/Vue open; React/MUI gives typed components and the closest maintained Material component base. MUI is implementation scaffolding, not authority for product semantics | Vue is acceptable only if selected before implementation with an M3-compatible component/theme mapping |
| Tests | JUnit 5, ArchUnit, Testcontainers PostgreSQL | architecture §8.3 and P-1 | equivalents must preserve every named runnable check |
| Image | one digest-pinned distroless Java 21 image, role-selected entry point | architecture §8.3 | a non-distroless base needs a documented operational requirement and equivalent attack-surface checks |

Version numbers are selected and pinned during implementation because this
DRAFT intentionally does not assert which patch release is current. The
policies above are binding after freeze; introducing any dependency remains
subject to the repository's dependency approval boundary.

## 10. Cross-references

- `docs/design/UI2_0_BASELINE_CONTRACT.md` §§1, 2, 4.
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §§3.2, 5 B1-1.
- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §§8.3–8.4, as amended by C5.
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §§2, 3.5, 6.
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §§2–7, 9.
- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §§2–6.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
  §§2–3, 6.
- `PRIVACY_AND_DATA_HANDLING.md`, “UI 2.0 database”.
- `.github/workflows/validation.yml` (coexisting Line-1 gate; unchanged).
