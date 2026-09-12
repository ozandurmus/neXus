# UI 2.0 — B1-1b CI workflow contract

## Status

**FROZEN — 2026-09-12**, under the Product Owner's standing written
authorization to approve, revise or cancel UI 2.0 B1 contracts.

This is the successor contract that `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md`
§6 requires before a UI 2.0 CI workflow may be implemented, including its
explicit extra requirement that the successor state how the gate runs where no
container runtime exists. It creates no workflow file by itself; the movement
that implements it does.

Provenance, not authority: an eight-step UI 2.0 job order was first sketched in
§6 of `docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md`, which is
SUPERSEDED, was never frozen, and is historical only — not authority for any
clause here. Its steps 5 and 6 (`docker build`, image inspection) named a
`Dockerfile` that does not exist and are withdrawn; §5 below records where that
material goes instead.

## 1. Scope

**In scope.** One isolated GitHub Actions workflow for Line 2 (`ui2/`): its
triggers, permissions, concurrency, job order, the carrier by which
`integrationTest` obtains a real PostgreSQL 16 in CI, dependency
verification/locking enforcement, artefact and secret rules, and the register of
checks this contract deliberately defers.

**Out of scope, and not authorized here.** Any change to Line 1's
`.github/workflows/validation.yml`; the container image and any image-derived
check; the frontend product application; any schema content; any device
contact. Build commands, module map, dependency-direction rules and the test
harness are owned by B1-1a §§2-5 and are cited, never restated, below.

Authority above this contract: `AGENTS.md`; `docs/design/UI2_0_BASELINE_CONTRACT.md`;
the frozen `C1`-`C4` contracts; `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md`.
Where this contract appears to conflict with any of them, they win and the
conflict is a reportable contradiction, not a local reconciliation.

## 2. Trigger and isolation

- Workflow file: `.github/workflows/ui2-validation.yml`. Workflow `name`:
  `ui2-validation`. One job, id `ui2-check`.
- Triggers: `pull_request` restricted by changed paths to `ui2/**`, the
  workflow's own file, and this contract's path; plus `workflow_dispatch`. No
  `push:` trigger — the same reasoning Line 1 recorded in its own file header:
  a trigger no job can serve produces an empty run.
- `permissions: contents: read` at workflow level. No write scope, no
  `id-token`, no `packages` scope.
- Concurrency: group `ui2-validation-${{ github.workflow }}-${{ github.ref }}`,
  `cancel-in-progress: true`. The group name is distinct from Line 1's
  `validation-*` group so neither line can cancel or queue behind the other.
- Isolation law. This workflow does **not** replace, rename, weaken, serialize
  with, or supply success for Line 1's `validation.yml` `validate` job. Its
  required-check name is its own (`ui2-validation / ui2-check`). A pull request
  touching both product lines must pass both workflows independently, and
  neither may be a substitute for the other.
- The implementing movement must leave `.github/workflows/validation.yml`
  byte-identical (acceptance check 1), which is also B1-1a §7 check 9.

## 3. Job order

`ui2-check`, `runs-on: ubuntu-latest`, with an explicit job `timeout-minutes`.
Every step below is unconditional: no step carries `continue-on-error`, a
step-level `if:`, `|| true`, or any other construct that can turn a failure
into a pass. Commands are exactly B1-1a §4's, run from the repository root.

1. **Checkout** with `fetch-depth: 0`, then `git fetch origin +main:refs/remotes/origin/main`,
   so `origin/main` exists as a resolvable ref for the privacy-gate baseline
   (§7) and for `git diff --check`.
2. **Java 21 toolchain.** `actions/setup-java@v4` with `java-version: '21'` and
   a pinned distribution, plus Gradle dependency caching. The build's own
   toolchain declaration (B1-1a §4) remains the authority on the language
   version; this step only makes a matching JDK available. Gradle is invoked
   only through the committed wrapper.
3. **Node 22.x.** `actions/setup-node@v4` with `node-version: '22'`, cache
   keyed on `ui2/frontend/package-lock.json`. Node is build tooling only.
4. **Dependency verification and locking** (§4).
5. `./ui2/gradlew -p ui2 clean assemble`.
6. `./ui2/gradlew -p ui2 check` — which aggregates `unitTest`,
   `architectureTest` and `frontendCheck`, and does **not** include
   `integrationTest` (see §6 note 2).
7. `./ui2/gradlew -p ui2 integrationTest`, with `UI2_TEST_JDBC_URL` supplied as
   required by §5 of this contract. This is a separate step precisely because
   `check` does not cover it; a green `check` is never evidence that the
   database-backed tests ran.
8. **Deferred-subject tripwire** (§6.4).
9. **Repository privacy gate** against `origin/main` (§7).
10. `git diff --check origin/main...HEAD`.
11. **Artefact upload** (§8), `if: always()` limited to the permitted artefact
    set only. This is the single permitted conditional in the workflow and it
    governs upload, never a check's verdict.

Step 6 precedes step 7 so that a compile or architecture failure is reported
before a database is provisioned, and step 9 is positioned after the build
steps knowingly: unlike Line 1, the UI 2.0 build writes only to `ui2/build/`
and `ui2/frontend/node_modules/`, both gitignored; if the privacy gate is ever
observed to flag build output, the gate moves to first position rather than
being narrowed.

## 4. Dependency verification and locking

B1-1a §4 requires one version catalog, eleven `gradle.lockfile`s and checked-in
dependency verification metadata, and forbids dynamic versions, version ranges
and unverified artifacts. In CI:

- Verification and locking stay **enabled**. The workflow passes no
  `--write-verification-metadata`, no `--write-locks`, no
  `--update-locks`, no `--refresh-dependencies` and no
  `-Dorg.gradle.dependency.verification=lenient|off`. CI never regenerates or
  repairs verification metadata or a lockfile; a disagreement is a failure to
  be fixed in a commit by a human-reviewed movement.
- Step 4 resolves dependencies for every configuration of every module and
  fails on any checksum, signature, lock or dynamic-version disagreement,
  before any test runs.
- After step 4, `git diff --exit-code -- ui2/gradle ui2/**/gradle.lockfile`
  must be clean: if resolution mutated committed verification or lock state,
  the job fails rather than proceeding on rewritten inputs.
- The Gradle wrapper distribution is validated by its committed
  `distributionSha256Sum`; the workflow does not download Gradle by any other
  means.

`UNKNOWN`: the exact Gradle invocation that resolves *all* configurations
offline in one pass is not asserted here. The predecessor proposed
`--offline dependencies`; with locking and verification enabled the
implementing movement must determine empirically which invocation actually
fails on a lock/metadata disagreement and use that one. A step that passes
because it resolved nothing would be the same class of defect B1-1a §3 rule 1
records. The requirement is the observed failure behaviour, not a command
string this contract cannot yet prove.

## 5. How `integrationTest` gets PostgreSQL 16 in CI

B1-1a §5 is the authority on the harness and is not restated: one fixture, two
carriers, fail-closed when neither exists, PostgreSQL **16** as the proving
version, no DSN or credential printed. This section states only the CI carrier.

- CI uses the **first** carrier of B1-1a §5, the external server named by
  `UI2_TEST_JDBC_URL`, provided by a GitHub Actions **service container**
  running a pinned `postgres:16` image with a health check, the job waiting on
  healthy before step 7.
- The resolution is deliberate: a container runtime is absent on the Product
  Owner's workstation and in the hosted engineering environment, but GitHub
  Actions has one. The second carrier (Testcontainers) therefore remains the
  local path and is not what CI relies on; CI does not depend on Docker being
  reachable from inside the Gradle process.
- `UI2_TEST_JDBC_URL` is set at job or step level to the service DSN. If it is
  unset, or the service is not healthy, the job **fails**. There is no
  `if:` that skips step 7, no `continue-on-error`, and no fallback that lets
  the step report success without a database. A skipped database test is
  absence of evidence and is forbidden by `AGENTS.md` "UNKNOWN / fail-closed
  law".
- A non-16 major version is not a permitted substitute: B1-1a §5 clause 5
  makes evidence from another major version not evidence for that contract, so
  the service image tag is pinned to 16.
- Credentials: the service's user and password are ephemeral CI-local values
  created for the run, never repository secrets, never printed, and never
  written to an artefact (§8, §9). They carry no production meaning.

## 6. What this contract defers, and how a deferred check is represented

### 6.1 The decision

The image build and every image-derived check are **deferred**, not skipped,
not stubbed, and not represented by any step in this workflow. They wait on the
container-image successor contract that B1-1a §6 requires — the one to be
written against the Red Hat/OpenShift runtime actually in use, and which must
resolve the `restricted-v2` arbitrary-UID constraint that defeats a fixed
numeric UID. No image check may be implemented before that contract freezes,
and this contract grants no authority to add one.

Reasoning. CI *could* build an image, because GitHub Actions has a container
runtime. It must not, for two independent reasons. First, there is no
`Dockerfile` or `Containerfile` and no frozen contract saying what the image
must contain, which user it runs as, or what a pass looks like — a build
against an uncontracted artefact would produce a green check with no defined
meaning, and would make the implementing movement the de facto author of the
image contract. Second, an image built on a Docker-capable GitHub runner is
not evidence about the Red Hat/OpenShift runtime the product actually targets;
proving it there would be management-plane-style evidence standing in for the
runtime that matters. Deferring is the honest state.

### 6.2 Representation rule

A deferred check is represented by **absence plus an explicit register**, never
by a skipped, stubbed, neutral or conditionally-passing step:

1. **No step exists** for a deferred check. A deferred check has no step id, no
   step name, no job, no `if: false`, no placeholder, and no `success()`
   shortcut. There is nothing in the workflow whose green state could be read
   as the check having run.
2. **The register is this section's table** (§6.3), which names each deferred
   check and the contract it waits on. The workflow file carries a header
   comment pointing at this section by path and number; it states nothing about
   the deferred checks beyond that pointer, so the register cannot drift into
   two versions.
3. **The passing check name is scoped.** `ui2-validation / ui2-check` is
   declared, here and in the workflow header, to prove exactly the checks of §3
   and nothing else. It is not "UI 2.0 is validated", and no project-state
   record, build-history summary or status line may read it that way.
4. **A deferred subject appearing without its contract fails the job** (§6.4).
   The anti-drift device is a tripwire that fails, not a marker that passes.

### 6.3 Deferred register

| Deferred check | Why it cannot run now | Contract it waits on |
| --- | --- | --- |
| Container image build | no `Dockerfile`/`Containerfile` exists and no frozen image contract defines its content, user model or pass condition | the container-image successor required by B1-1a §6 |
| Image filesystem inspection | there is no image to inspect, and the target runtime is not the CI runner's | same |
| `dir8` image half | `DIR-8`'s image-layer assertion has no image | same |
| `dir10` image half | `DIR-10`'s image-filesystem assertion has no image | same |
| Arbitrary-UID / `restricted-v2` conformance | a runtime property of an image under a platform SCC; unprovable without both | same |
| SBOM generation | `UNKNOWN` — no SBOM generator is declared in `ui2/gradle/libs.versions.toml` or any `ui2/` build script at freeze time, so there is nothing to run | a movement that adds and freezes an SBOM generator; until then §8 forbids claiming an SBOM artefact |
| Any real-environment evidence for `ui2/` | B1-1a §9: no real-environment evidence of any kind exists; `REAL_ENV_VALIDATED` is unreachable for every B1 row | separate authorization for device contact |

### 6.4 Deferred-subject tripwire (step 8)

Step 8 fails the job when a deferred subject appears in the repository while
its contract has not frozen. Concretely it fails if any of
`ui2/Dockerfile`, `ui2/Containerfile`, `ui2/**/Dockerfile`,
`ui2/**/Containerfile` exists, or if the workflow file itself contains an
image-build or image-inspection invocation. Rationale: the failure mode this
contract exists to prevent is a check that is green because it skipped its
hardest step; the symmetric failure mode is an image appearing and quietly
never being checked. Both are caught by failing loudly instead of passing
quietly. When the image contract freezes, it amends or replaces this tripwire
as part of its own freeze — removing it is that contract's act, not a
movement's convenience.

### 6.5 Architecture tests in CI when no image exists

`architectureTest` runs in full in CI (step 6, via `check`) and no method is
skipped. What it proves is narrower than `DIR-1`-`DIR-10` read as a whole, and
the narrowing must be stated wherever a status is recorded:

- `dir1`-`dir7` and `dir9` are proved over compiled bytecode with ArchUnit's
  `ClassFileImporter`, exactly as B1-1a §3 states. CI proves these in full.
- `dir8` and `dir10` each have two halves. The **repository/manifest half** —
  the frontend source tree and the wider `ui2/` manifest and source tree,
  including the `settings.gradle.kts` no-traversal assertion — is proved in CI
  today by direct file-tree inspection in `Ui2ArchitectureTest`. The **image
  half** is not provable today and is deferred per §6.3.
- Therefore a green `architectureTest` in CI proves `DIR-1`-`DIR-7`, `DIR-9`,
  and the repository halves of `DIR-8` and `DIR-10`. It is not evidence for the
  image halves, and no report may state `DIR-8` or `DIR-10` as fully proved
  until the image contract's checks exist and pass.
- Contradiction, reported and not reconciled here: B1-1a §3 states that all ten
  rules "are proved one-to-one by `Ui2ArchitectureTest` … whose ten methods …
  import ArchUnit's `ClassFileImporter` to analyse compiled bytecode", while
  the implementation's own class documentation states that `dir8` and `dir10`
  are proved by direct file-tree inspection because their subject is not JVM
  bytecode. Both cannot be literally true of `dir8`/`dir10`. This contract
  describes CI against the implementation as it is and leaves the wording
  conflict to the owner of B1-1a.

Note 2, recorded because it changes what a green run means: the root `check`
task aggregates `unitTest`, `architectureTest` and `frontendCheck` only.
`integrationTest` is not part of `check`, which is why §3 runs it as its own
step. Any claim that "`check` passed" must not be read as covering the
database-backed tests.

## 7. Privacy gate in CI

- The repository privacy gate runs as its own step (§3 step 9), invoked through
  the repository's own documented gate command with a privacy baseline ref of
  `origin/main`, the same baseline Line 1 uses. A finding already present at
  the merge base is pre-existing repository debt; a genuinely new finding fails
  the job.
- The gate is a repository-wide tool and is not part of the `ui2/` build. B1-1a
  §4's prohibition is on the `ui2/` build invoking Python; running a
  repository gate in a CI job is not a build step and does not breach it. No
  acceptance check of this contract invokes an interpreter for the Java build.
- A privacy finding is reported as file, location and classification — never
  the matched value — per `AGENTS.md` "Sensitive identity reporting law".

## 8. Artefacts

Permitted uploads, and nothing else:

- JUnit XML and HTML reports for `unitTest` and `integrationTest`.
- The `architectureTest` report.
- The frontend test report, if the frontend tooling emits one.
- An SBOM **only** once an SBOM generator exists and is frozen (§6.3). Until
  then the workflow uploads no SBOM and claims none.

Forbidden, explicitly: a database volume or database dump; a container log or
any log containing sensitive data; runtime data; raw vendor or device evidence;
a backup artefact; an image tar; `ui2/frontend/node_modules`; a Gradle
dependency cache containing credentials; any file under `data/`, `output/` or
`logs/`. Test reports are uploaded on the understanding of B1-1a §5 clause 4
and §9 below: no DSN, credential or identity value reaches a failure message,
so no report can carry one. If a report is ever observed to contain such a
value, the fixture is fixed; the artefact rule is not widened.

## 9. Secrets

- No repository secret is referenced by this workflow. It needs none: the
  build resolves only public dependency coordinates under committed
  verification metadata, and the database is an ephemeral service container
  with run-local credentials.
- No secret, credential, DSN or device identity appears in a log line, a step
  name, an environment default, a Gradle build argument, a cache key, or an
  artefact.
- The workflow does not echo its environment (`env`, `printenv`, `set -x` over
  a step carrying credentials) and does not enable Gradle or Actions debug
  logging by default.
- `permissions: contents: read` is the whole token scope (§2); no step
  requests more, and no step writes to the repository, a package registry or a
  release.
- Forks: nothing here requires a secret, so a fork pull request runs the same
  checks with no secret exposure. `pull_request_target` is forbidden.

## 10. Acceptance checks

Runnable from the repository root. None names an absolute filesystem path, a
developer account, or a Python interpreter for the Java build — the shape
B1-1a §10 item 4 records as a predecessor defect and which must not return.

1. `git diff --exit-code origin/main -- .github/workflows/validation.yml` is
   clean: Line 1's gate is byte-identical to its pre-movement version.
2. `.github/workflows/ui2-validation.yml` parses as a workflow and its run on a
   pull request touching `ui2/**` reports the check name
   `ui2-validation / ui2-check`, separate from and additional to Line 1's
   `validate`.
3. `grep -n -E 'continue-on-error|\|\| true|if: false|pull_request_target' .github/workflows/ui2-validation.yml`
   matches nothing.
4. The only `if:` expression in `.github/workflows/ui2-validation.yml` is the
   artefact-upload step's `if: always()`; no check step carries a condition.
5. `grep -n -E 'docker build|podman build|buildah|Dockerfile|Containerfile|docker (inspect|run)' .github/workflows/ui2-validation.yml`
   matches nothing outside a comment that points at §6 of this contract.
6. `grep -n -E 'write-verification-metadata|write-locks|update-locks|refresh-dependencies|verification=(lenient|off)' .github/workflows/ui2-validation.yml`
   matches nothing.
7. With the PostgreSQL service healthy and `UI2_TEST_JDBC_URL` set,
   `./ui2/gradlew -p ui2 integrationTest` passes in CI and its report shows
   zero skipped tests.
8. With `UI2_TEST_JDBC_URL` unset and no container runtime reachable, the same
   command **fails**; no run of it reports success or a skip. (B1-1a §7 check 4
   is the same requirement locally.)
9. `./ui2/gradlew -p ui2 architectureTest` passes in CI with all ten
   `dir1_…`-`dir10_…` methods executed and none skipped.
10. After the dependency step, `git diff --exit-code -- ui2/gradle` and
    `git diff --exit-code -- ui2` report no modification to any
    `gradle.lockfile` or to `verification-metadata.xml`.
11. No `ui2/Dockerfile` and no `ui2/Containerfile` exists; the §6.4 tripwire
    step fails the job if one appears.
12. The workflow uploads no artefact outside §8's permitted list, and no SBOM
    while §6.3 records the generator as `UNKNOWN`.
13. `grep -n -E 'secrets\.|printenv|set -x' .github/workflows/ui2-validation.yml`
    matches nothing.
14. The repository privacy gate reports zero new findings against
    `origin/main`, invoked through the repository's own documented command.
15. `git diff --check origin/main...HEAD` is clean.

## 11. Cross-references

- `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §§2-6, 7, 9, 10.
- `docs/design/UI2_0_BASELINE_CONTRACT.md` §§1, 2, 4.
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §§3.2, 5 B1-1.
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §§2, 3.5.
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §9.
- `docs/design/UI2_0_B1_02_SCHEMA_V1_CONTRACT.md` §7.
- `PRIVACY_AND_DATA_HANDLING.md`, "UI 2.0 database".
- `docs/AI_DEVELOPMENT_PROTOCOL.md`, "CI validation policy".
- `docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` §6 — SUPERSEDED,
  historical only, cited for provenance of the eight-step sketch and never as
  authority.

## 12. What this contract does not prove

Freezing it proves that the CI shape described is buildable from artifacts that
exist today; it proves nothing about the image, the deployment runtime, the
frontend application, or any real-environment behaviour. A green
`ui2-validation / ui2-check` is evidence for §3's checks at §6.5's stated
resolution and for nothing beyond them. No row may advance past
`AUTOMATED_VALIDATED` on the strength of this workflow alone.
