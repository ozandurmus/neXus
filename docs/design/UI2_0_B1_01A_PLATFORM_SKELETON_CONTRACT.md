# UI 2.0 — B1-1a platform skeleton contract

## Status

**FROZEN — 2026-09-12**, under the Product Owner's standing written
authorization to approve, revise or cancel UI 2.0 B1 contracts.

Supersedes `docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md`
(DRAFT, never frozen) together with its Amendments B1-1-A and B1-1-B and
Corrections C-1 and C-2. That predecessor was written before any of `ui2/`
existed and asserted a build it could not observe; four of its load-bearing
claims were disproved by the implementation (§10 below records each). It is
marked SUPERSEDED and retained for history only.

This contract is written the other way round: every clause in §§2-5 describes
an artifact that exists in `ui2/` at freeze time and is named by path, and
every clause in §6 is explicitly NOT BUILT and carries no authority over an
implementation until its own successor contract freezes.

### Why a successor instead of an amendment

The predecessor carried three amendment/correction layers over an unfrozen
draft, and the four FROZEN B1 contracts (B1-2, B1-3, B1-4, B1-4b) cited it as
authority sixteen times. `AGENTS.md` "Authority hierarchy" item 2 forbids a
DRAFT being cited as approving a schema or module model. A fourth patch layer
would have left that violation in place; a frozen successor written from
implementation reality removes it.

## 1. Scope and authority

**In scope.** The Gradle multi-module Java skeleton under `ui2/`, its
dependency direction rules and their architecture tests, the build and
reproducibility policies, and the test-harness carrier requirement.

**Out of scope, and NOT authorized by this contract.** The container image,
the CI workflow, the frontend runtime application, any Line-1 change, any
device contact, and any schema content (that is B1-2's). §6 states what each
of these needs before it may be implemented.

Authority above this contract: `AGENTS.md`; `docs/design/UI2_0_BASELINE_CONTRACT.md`;
the frozen `C1`-`C4` contracts. This contract never overrides a `C1`-`C4`
clause; where it appears to, `C1`-`C4` win and the conflict is a reportable
contradiction, not a local reconciliation.

## 2. Module map

All Java packages begin `com.securityexpert.nexus.ui2`. `ui2/settings.gradle.kts`
includes exactly eleven Java subprojects. `frontend` is a build-time TypeScript
workspace driven by root `Exec` tasks and is **not** a Gradle subproject — a
claim the predecessor's Amendment B1-1-A asserted and its own Correction C-1
withdrew.

| Module | Package home | Declared project dependencies (as built) | Contract home |
| --- | --- | --- | --- |
| `platform-core` | `.platform` | none | baseline P-1/P-4 |
| `persistence` | `.persistence` | `platform-core` | C1 §§2-6 |
| `capability-registry` | `.capability` | `platform-core` | C4 §§2-3 |
| `job-engine` | `.jobs` | `platform-core`, `persistence`, `capability-registry` | C2 §§2-7 |
| `ldap-adapter` | `.identity.ldap` | `platform-core` | C3 §§2, 4.4 |
| `service` | `.service` | `platform-core`, `persistence`, `capability-registry`, `job-engine`, `ldap-adapter` | C1 service scope; C3 §§3-6 |
| `worker` | `.worker` | `platform-core`, `persistence`, `capability-registry`, `job-engine` | C1 worker scope; C2 |
| `scheduler` | `.scheduler` | `platform-core`, `persistence`, `job-engine` | C1 scheduler scope; C2 §7 |
| `cli` | `.cli` | `platform-core`, `job-engine` | architecture §8.3 |
| `architecture-tests` | test-only | every Java module as test input | baseline P-1 |
| `integration-tests` | test-only | production modules as test fixtures | C1 §§2, 3.5; C2 §9 |

Flyway migration resources live at `service/src/main/resources/db/migration/`
and are applied by `FlywayMigrationRunner` in `persistence` — not in
`service`. A module declaring the same project dependency twice is a defect,
not a permitted duplicate.

## 3. Dependency direction

Ten rules, `DIR-1` through `DIR-10`, with the directions and forbidden edges
as stated in the predecessor §2.1, which the implementation satisfies
unchanged. They are proved one-to-one by `Ui2ArchitectureTest` in
`architecture-tests`, whose ten methods are named `dir1_…` through `dir10_…`
and import ArchUnit's `ClassFileImporter` to analyse compiled bytecode.

Two requirements on those tests, both learned from defects:

1. **Each rule must be proved to fail.** A rule is only counted as proved
   once a deliberately injected real violation has been observed to fail it.
   A test that passes because it analyses nothing proves nothing — the
   predecessor's implementation contained an "ArchUnit" test that never
   imported ArchUnit.
2. **A rule must not be tripped by its own conformant implementation.** The
   `dir10` check excludes `architecture-tests` sources, because the rule's
   own mandated method name contains the token it searches for. This was the
   predecessor's Correction C-2 and is restated here as a standing rule for
   any future textual check.

## 4. Build and reproducibility

As built, and binding:

- **Java 21** via a Gradle toolchain (`JavaLanguageVersion.of(21)`), not an
  ambient JDK. A major version change requires a successor contract.
- **Gradle wrapper only**, `gradle-8.14.3-bin.zip`, with
  `distributionSha256Sum` set. Patch upgrades are maintenance; major upgrades
  are reviewed.
- **One version catalog** (`ui2/gradle/libs.versions.toml`), **eleven
  `gradle.lockfile`s**, and checked-in dependency verification
  (`ui2/gradle/verification-metadata.xml`, 190 components). Dynamic versions,
  version ranges and unverified artifacts are forbidden. Verification
  metadata and locks are regenerated only by Gradle's own
  `--write-verification-metadata` / `--write-locks`; hand-editing a checksum
  or disabling verification is forbidden.
- **Node 22.x** pinned in `frontend/package.json` `engines`, installed from
  the committed lockfile. Node is build tooling only.
- Pinned library choices, carried forward from the predecessor §9 and now
  observable: jOOQ 3.19.11, Flyway 10.17.0, PostgreSQL JDBC 42.7.4, Spring
  Boot 3.3.4, JUnit 5.10.3, Testcontainers 1.20.1, UnboundID for LDAP.
- **No command in the `ui2/` build invokes Python**, and no acceptance check
  of this contract names an absolute filesystem path or a developer account.
  The predecessor's acceptance check 14 violated both.

Commands, run from the repository root:

```text
./ui2/gradlew -p ui2 clean assemble
./ui2/gradlew -p ui2 unitTest
./ui2/gradlew -p ui2 architectureTest
./ui2/gradlew -p ui2 integrationTest
./ui2/gradlew -p ui2 check
```

## 5. Test harness carrier

`unitTest` has no container, network, filesystem-runtime or wall-clock
dependency. `integrationTest` owns database-backed tests.

**The load-bearing requirement is a real PostgreSQL 16 server, not a
particular carrier.** The predecessor bound the tests to Testcontainers;
neither the Product Owner's workstation nor the hosted engineering
environment has a container runtime, so that binding made the tests
unrunnable everywhere and fourteen of them stayed `@Disabled`. Therefore:

1. An integration test acquires its database through one fixture, which
   resolves its carrier in this order: an external real PostgreSQL 16 server
   named by the `UI2_TEST_JDBC_URL` environment variable; otherwise a
   Testcontainers `PostgreSQL` container.
2. If neither carrier is available the test **fails**. It must never skip,
   pass, or report success — absence of a carrier is absence of evidence, and
   `AGENTS.md` requires fail-closed.
3. Per test class the fixture creates a fresh database, ensures the
   `ui2_migrate` / `ui2_app` roles, runs Flyway with the `ui2_migrate` DSN
   against the same migration location the application packages, and only
   then exposes an `ui2_app` connection. Tests never create schema through
   jOOQ, application startup or ad-hoc DDL.
4. No DSN, credential or secret value is printed by a fixture, a test, or a
   failure message.
5. PostgreSQL **16** is the proving version. A test passing only against a
   different major version is not evidence for this contract.

The predecessor §4 additionally mandated a single test named
`AuditContextIntegrationTest`. No such test exists: the frozen B1-2 §7
decomposed that behaviour into `AuditContextMissingFailsClosedTest`,
`AuditAtomicityTest`, `AuditCoverageCompletenessTest` and
`DirectAuditLogWriteDeniedTest`. **B1-2 §7 wins**; the single-test name is
withdrawn and no acceptance check may reference it.

## 6. Explicitly NOT BUILT

Nothing below exists at freeze time. This contract grants no authority to
implement any of it; each needs its own frozen successor. Naming them here is
what keeps a future reader from mistaking the predecessor's assertions for
delivered work.

| Item | Predecessor claim | Reality at freeze | Gate before implementation |
| --- | --- | --- | --- |
| Container image | §7, a full distroless image contract; `docker build --file ui2/Dockerfile` in §3.2 and check 9 | no `Dockerfile`, no `Containerfile` | a successor contract written against the Red Hat/OpenShift runtime actually in use, not Docker; must resolve the `restricted-v2` arbitrary-UID constraint, which defeats a fixed numeric UID and an `fsGroup` |
| CI workflow | §6, `.github/workflows/ui2-validation.yml` with an eight-step job order | file does not exist | a successor contract; it must also state how the gate runs where no container runtime exists |
| Frontend application | §9, React + TypeScript + MUI on frozen Material Design 3 tokens | workspace and build tasks exist; no product screens | B1-7 and B1-9, once their contracts freeze |
| Role entry points | §7, one jar with a typed role argument for service/worker/scheduler | `scheduler` and `cli` carry one Java source each | the deployment successor above |

## 7. Acceptance checks

Runnable from the repository root; none names an absolute path, a developer
account, or a Python interpreter.

1. `./ui2/gradlew -p ui2 projects` lists exactly the eleven Java modules of §2
   and no `frontend` subproject.
2. `./ui2/gradlew -p ui2 architectureTest` passes all ten `dir1_…`-`dir10_…`
   methods, and each has a recorded injected-violation failure.
3. `./ui2/gradlew -p ui2 unitTest` passes with no container and no database.
4. `UI2_TEST_JDBC_URL=<real PostgreSQL 16 DSN> ./ui2/gradlew -p ui2 integrationTest`
   passes; with the variable unset and no container runtime the same command
   **fails** rather than skipping.
5. `./ui2/gradlew -p ui2 check` succeeds with dependency verification and
   locking enabled.
6. `npm --prefix ui2/frontend ci && npm --prefix ui2/frontend test && npm --prefix ui2/frontend run build`
   succeeds from the committed lockfile alone.
7. No `ui2/` module declares the same project dependency twice.
8. `grep -R -E 'main\.py|python|console/|_runner\.py|_collector\.py' ui2 --exclude-dir=build --exclude='*.md'`
   matches nothing outside `architecture-tests` sources (§3 rule 2), and
   `ui2/.python-version` does not exist.
9. `.github/workflows/validation.yml` is byte-identical to its pre-movement
   version.
10. The repository privacy gate reports zero new findings against
    `origin/main`, invoked through the repository's own documented command.
11. `git diff --check origin/main...HEAD` is clean.

## 8. Cross-references

- `docs/design/UI2_0_BASELINE_CONTRACT.md` §§1, 2, 4.
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §§3.2, 5 B1-1.
- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §§8.3-8.4, as amended by C5.
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §§2, 3.5, 6.
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §§2-7, 9.
- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §§2-6.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §§2-3, 6.
- `docs/design/UI2_0_B1_02_SCHEMA_V1_CONTRACT.md` §7 (owns the audit test set).
- `PRIVACY_AND_DATA_HANDLING.md`, "UI 2.0 database".

## 9. What this contract does not prove

A frozen clause describing an existing artifact proves that the artifact
exists and is shaped as stated. It does not prove the artifact is correct, nor
that any behaviour is validated against a real device or a real environment.
At freeze time `ui2/` has no real-environment evidence of any kind, and
`REAL_ENV_VALIDATED` is unreachable for every B1 row until device contact is
separately authorized.

## 10. Predecessor claims disproved by the implementation

Recorded so the same assertions are not reintroduced:

1. `frontend` is a Gradle subproject — false; it is a root `Exec` workspace.
2. A single `AuditContextIntegrationTest` proves the audit behaviour — false;
   B1-2 §7 owns four separate tests.
3. `docker build --file ui2/Dockerfile …` is a runnable command and an
   acceptance check — false; neither the file nor a container runtime exists.
4. The privacy acceptance check is
   `/Users/<account>/…/.venv/bin/python main.py …` — an absolute path and a
   developer account in a contract, and a Python invocation inside a build
   whose own §3.2 forbade one.
