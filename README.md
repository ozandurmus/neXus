# neXus

neXus is a multi-vendor firewall evidence and operations platform. The maintained
product is **UI2: Java 21 and Spring Boot with a React/TypeScript and MUI frontend**,
deployed as container workloads on Kubernetes/k3s.

**SEE → VERIFY → TRACE → RECOVER → OPERATE**

It brings inventory, configuration, compliance, encrypted backup and controlled
recovery with execution transcripts, HA/failover readiness, policy viewing for
Check Point MDS and Palo Alto Panorama, and gated diagnostics into one workspace.
Inventory describes observed runtime facts; configuration describes configured
state; alignment compares intent with actual evidence. Missing evidence remains
`UNKNOWN` rather than becoming a guessed answer.

## Architecture

```text
Browser — React / TypeScript / MUI (masked AIView inspection)
    |
    v
Spring service — authentication, RBAC, API, packaged frontend
    |                                      |
    v                                      v
job-engine / scheduler                 persistence
    |                                      |
    v                                      v
worker / capability registry           PostgreSQL + Flyway
    |
    v
Gated SSH / HTTPS read-only device adapters
    |
    v
Parsed evidence → safe projections / encrypted artefact storage
```

This diagram shows the collection path. Controlled recovery and failover
execution use separate gated workflows; readiness does not authorize execution.
The browser submits typed intent, never device commands or transport parameters.
Device access and command authorization stay server-side.

Deployment uses k3s on a single host, kaniko image builds, a Trivy/Semgrep/gitleaks
security gate, and in-cluster Playwright e2e verification. The frontend is served
by Java; no long-running Node service is required.

## Modules

The eleven Java modules are declared in [settings.gradle.kts](ui2/settings.gradle.kts).
The frontend is a build-time npm workspace, not a Gradle module.

| Path | Responsibility |
| --- | --- |
| [ui2/service](ui2/service/) | Spring API, authentication, RBAC, masking and frontend packaging |
| [ui2/worker](ui2/worker/) | Vendor collection and controlled job execution |
| [ui2/job-engine](ui2/job-engine/) | Job lifecycle and execution contracts |
| [ui2/persistence](ui2/persistence/) | PostgreSQL persistence adapters; Flyway SQL lives in [service resources](ui2/service/src/main/resources/db/migration/) |
| [ui2/scheduler](ui2/scheduler/) | Scheduling support |
| [ui2/platform-core](ui2/platform-core/) | Shared platform types and boundaries |
| [ui2/capability-registry](ui2/capability-registry/) | Capability definitions and command-gate resolution |
| [ui2/ldap-adapter](ui2/ldap-adapter/) | Directory integration |
| [ui2/cli](ui2/cli/) | Administrative CLI |
| [ui2/architecture-tests](ui2/architecture-tests/) | Architecture boundary checks |
| [ui2/integration-tests](ui2/integration-tests/) | Database integration gates |
| [ui2/frontend](ui2/frontend/) | React application, Vitest tests and Playwright e2e |

## Build and test

Use the established development runtime and installed frontend dependencies:

```sh
cd ui2/frontend
npx tsc --noEmit -p .
npx vitest run
npm run build
```

Run Java in the approved build/integration container, from the repository root:

```sh
./ui2/gradlew --no-daemon --console=plain -p ui2 -PfrontendPrebuilt=true unitTest architectureTest
./ui2/gradlew --no-daemon --console=plain -p ui2 -PfrontendPrebuilt=true :service:bootJar
./ui2/gradlew --no-daemon --console=plain -p ui2 -PfrontendPrebuilt=true :integration-tests:integrationTest
```

`frontendPrebuilt=true` requires the frontend build output to be present.
Integration tests require the approved PostgreSQL test environment; unavailable
infrastructure is not a passing result.

Run retained Python tooling tests and local repository checks from the root:

```sh
python3 -m pytest -q -n auto --dist worksteal
python3 tools/privacy/repository_privacy_check.py
git diff --check
```

Default pytest discovery covers `tools/tests/`. See [Python test boundaries](tests/README.md)
for prerequisites and historical opt-in tests. Automated tests do not substitute
for required real-environment validation.

## Deploy

The delivery flow is **PR → security gate → rollout → masked in-cluster e2e**.
Kaniko builds the candidate image; Trivy, Semgrep and gitleaks check the required
source, history and image surfaces before the approved image is rolled out.
Playwright then verifies the deployed application as `aiview`; page/API 4xx
responses fail verification.

Use the [deployment runbook](docs/operations/UI2_LOCAL_KUBERNETES_DEPLOYMENT.md),
[workload manifests](deploy/ui2/) and [image-build manifests](deploy/ui2-image-build/).
Delivery entry points are [tools/delivery/hosta_deploy.sh](tools/delivery/hosta_deploy.sh),
[tools/delivery/hosta_integration.sh](tools/delivery/hosta_integration.sh) and
[tools/e2e/hosta_e2e.sh](tools/e2e/hosta_e2e.sh). Follow their runbook arguments and
authorization requirements; credentials and endpoint values stay outside source
control. See the [security gate runbook](deploy/security/README.md) for scanner
configuration and evidence requirements.

## Security and privacy

Live action authority is Java:
[ActionClass](ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/ActionClass.java)
defines the risk classes, and
[ActionRegistry](ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/ActionRegistry.java)
defines registered actions. Classes span `CLASS_0` reads, `CLASS_1` recovery
writes (including `CLASS_1B` controlled restore), `CLASS_2` operational state
changes, `CLASS_3` configuration writes and `CLASS_4` policy deployment.
A class definition is not permission to execute it. Commands require exact
approved gate rows in migrations and the
[gate registry fixture](ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml),
plus the applicable contract, role and execution authorization.

All UI inspections and shared verification evidence use `aiview`
(`role:replay_viewer`) and deterministic server-side pseudonyms such as
`FW-TANGO-04` and `CLS-ROMEO-01`. IP pseudonyms use the reserved IPv4 block
`240/4` (first octet 240–255); they are masked presentation values, never device
endpoints or identity join keys. AIView may
run approved diagnostic reads and readiness checks; it cannot execute failover
or writes, grant approvals, or create schedules.

RBAC, transport trust policy, command gates, execution ledgers and encrypted
artefact storage enforce distinct boundaries. Secrets, raw operational identities
and sensitive vendor responses do not belong in Git, shared logs, screenshots
or chat. Inspect relationships and safe derived states. Raw-evidence retention
requires an explicit evidence contract and defined privacy handling.

Fail closed: absence of evidence is not evidence of absence. Unproven semantics
remain `UNKNOWN`, `INSUFFICIENT_EVIDENCE` or `UNSUPPORTED`; a successful collection
does not prove semantic correctness. Readiness is distinct from authorization.
The operational action classes are separate from the privacy data-sensitivity
classification. See [AGENTS.md](AGENTS.md) and
[Privacy and data handling](PRIVACY_AND_DATA_HANDLING.md) for authoritative rules.

## Repository map and history

| Path | Contents |
| --- | --- |
| [ui2/](ui2/) | Maintained Java product and React frontend |
| [deploy/](deploy/) | k3s workloads, build Jobs and security delivery definitions |
| [tools/delivery/](tools/delivery/) | Orchestration, governance, build and integration helpers |
| [tools/security/](tools/security/) | Security scanners and report projection |
| [tools/e2e/](tools/e2e/) | E2e wrappers and offline canary helpers |
| [tools/privacy/](tools/privacy/) | Standalone repository privacy gate and scanner |
| [tools/tests/](tools/tests/) | Retained Python tooling and Java-delivery tests |
| [security/](security/) | Security acceptance baseline |
| [project/](project/) | Current project state and Java Project Plan inputs |
| [docs/README.md](docs/README.md) | Current documentation index and active contract entry points |
| [history/](history/README.md) | Retired Python product, tests, packaging, documents and scratch archive |

The Python product is preserved in this repository under `history/`, outside
the Java build and default pytest discovery. The [document relocation map](history/docs/RELOCATION.md)
records moved documentation. Active contracts and referenced evidence remain in
`docs/`; archived commands and approvals grant no present execution permission.

## Roadmap

Follow [project/QUEUE.md](project/QUEUE.md) and [CURRENT_STATE.md](CURRENT_STATE.md)
for current scope, sequencing and validation status across vendor coverage,
evidence quality, recovery and controlled operations. Planned features and
automated tests are not claims of live validation.
