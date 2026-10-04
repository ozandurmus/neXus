# neXus documentation

The maintained product is **UI2: Java with a React/TypeScript frontend**, packaged
as container workloads. It brings inventory, configuration, compliance, backups
and controlled operations into one evidence platform:
**SEE → VERIFY → TRACE → RECOVER → OPERATE**.

Inventory describes observed runtime state; configuration describes configured
state; alignment compares intent with evidence. Missing evidence remains UNKNOWN.
Readiness and historical approvals do not authorize execution.

## Product and architecture

- [Product checkpoint](../CURRENT_STATE.md) and [delivery queue](../project/QUEUE.md):
  current scope, limitations and validation status.
- [Java architecture design](design/UI2_0_ARCHITECTURE_DESIGN.md): platform
  boundaries and design decisions; read it with the successor records below.
- [Java module definitions](../ui2/settings.gradle.kts): the maintained module map.
- [Java service](../ui2/service/), [worker](../ui2/worker/),
  [job engine](../ui2/job-engine/), [persistence](../ui2/persistence/) and
  [frontend](../ui2/frontend/): implementation entry points.
- [Historical Python architecture](ARCHITECTURE.md): retained for existing
  references; its CLI and static-report instructions describe the retired product.

## Operations and validation

These references describe procedures; running them requires the applicable
host, deployment and device authorization.

- [Kubernetes deployment runbook](operations/UI2_LOCAL_KUBERNETES_DEPLOYMENT.md).
- [Image build and integration manifests](../deploy/ui2-image-build/).
- [Security delivery gate](../deploy/security/README.md).
- [Masked AIView end-to-end verification](reference/E2E_SCREEN_TESTS.md).
- [Live delivery tooling](../tools/delivery/) and [Python test boundaries](../tests/README.md).
- [Frontend build and test scripts](../ui2/frontend/package.json) and
  [Java build definition](../ui2/build.gradle.kts).

## Security and privacy

- [Agent constitution](../AGENTS.md) and
  [privacy and data handling](../PRIVACY_AND_DATA_HANDLING.md).
- [Development protocol and network-command gate](AI_DEVELOPMENT_PROTOCOL.md).
- [Java action classes](../ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/ActionClass.java),
  [action registry](../ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/ActionRegistry.java)
  and [approved gate fixture](../ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml).

UI inspections and shared evidence use the masked `aiview` persona
(`role:replay_viewer`). Report deterministic pseudonyms and derived relationships,
not raw operational identities or secrets. Approved diagnostic reads and readiness
checks do not grant failover execution, write, approval or scheduling rights.

## Contracts and decision records

- [Generated decision-record successor index](design/DECISION_RECORD_SUCCESSOR_INDEX.md):
  begin here to trace amendments and successors before using a design record.
- [Design records](design/): follow each document's own status and the authority
  hierarchy. A draft or superseded document cannot grant implementation authority.
- [Build-history index](history/INDEX.md): generated historical build evidence;
  retained records may still support live contracts.
- [Engineering entry point](../AI_START_HERE.md) and
  [role dispatch](../roles/ENGINEER.md).

## Historical archive

- [Archive boundaries and optional reproduction](../history/README.md).
- [Documentation relocation map](../history/docs/RELOCATION.md): exact old and
  new paths for PR4 batches 1 and 2, plus the remaining scope.

Retired documentation moves to `history/docs/` with its original structure and
contents preserved. Active frozen contracts, command-gate records, Java mockup
build inputs and documents referenced by live code, tools, tests or project data
stay in place. Archived instructions are historical evidence, not current
operating procedures or device-command permission.
